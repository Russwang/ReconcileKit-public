#!/usr/bin/env python3
"""Small safety regressions for the deployment probe; no Docker or ledger writes."""
from copy import deepcopy
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

CHECKER = Path(__file__).with_name("check-deployment.py")
CODE = runpy.run_path(str(CHECKER), run_name="deployment_probe_tests")


def config():
    return {"services": {
        "app": {"image": "reconcilekit:test", "environment": {
            "SERVER_ADDRESS": "0.0.0.0", "PORT": "8080", "DB_PASSWORD": "synthetic-private"},
            "ports": [{"host_ip": "127.0.0.1", "protocol": "tcp", "target": 8080, "published": "18098"}],
            "read_only": True, "cap_drop": ["ALL"], "tmpfs": ["/tmp"],
            "security_opt": ["no-new-privileges:true"]},
        "database": {"image": "postgres:17.11@sha256:" + "a" * 64,
                     "environment": {"POSTGRES_PASSWORD": "synthetic-private"}}},
        "volumes": {"database-data": {"name": "rk-test_database-data"}}}


def container(service):
    cfg = config()["services"][service]
    return {"Id": "b" * 64, "Image": "sha256:" + "c" * 64,
            "Config": {"Image": cfg["image"], "User": "10001:10001" if service == "app" else "",
                       "Labels": {"com.docker.compose.project": "rk-test",
                                  "com.docker.compose.service": service},
                       "Env": [f"{key}={value}" for key, value in cfg["environment"].items()]},
            "State": {"Running": True, "Health": {"Status": "healthy"}},
            "HostConfig": {"Privileged": False, "NetworkMode": "rk-test_default", "PidMode": "",
                           "ReadonlyRootfs": True, "CapDrop": ["ALL"], "CapAdd": None,
                           "Tmpfs": {"/tmp": ""}, "SecurityOpt": ["no-new-privileges:true"]},
            "NetworkSettings": {"Ports": {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "18098"}]}
                                if service == "app" else {"5432/tcp": None}},
            "Mounts": [] if service == "app" else [{"Type": "volume", "Name": "rk-test_database-data",
                                                   "Destination": "/var/lib/postgresql/data"}]}


class DeploymentProbeSafetyTests(unittest.TestCase):
    def test_selected_loopback_config_and_runtime_are_accepted(self):
        cfg = config()
        self.assertEqual(18098, CODE["validate_config"](cfg, "rk-test"))
        for service in ("app", "database"):
            result = CODE["validate_runtime"](container(service), service, "rk-test", cfg["services"][service], 18098)
            self.assertNotIn("Env", result)
            self.assertNotIn("synthetic-private", str(result))

    def test_public_app_or_any_database_publication_is_rejected(self):
        for service in ("app", "database"):
            with self.subTest(service=service):
                cfg = config()
                cfg["services"][service]["ports"] = [{"host_ip": "0.0.0.0", "target": 8080, "published": "18098"}]
                with self.assertRaises(AssertionError):
                    CODE["validate_config"](cfg, "rk-test")

    def test_runtime_publication_environment_or_restriction_drift_is_rejected(self):
        mutations = [lambda item: item["NetworkSettings"]["Ports"]["8080/tcp"][0].update(HostIp="0.0.0.0"),
                     lambda item: item["Config"]["Env"].append("DB_PASSWORD=other-private"),
                     lambda item: item["HostConfig"].update(ReadonlyRootfs=False),
                     lambda item: item["HostConfig"].update(CapAdd=["SYS_ADMIN"]),
                     lambda item: item["Config"].update(User="0"),
                     lambda item: item["Config"]["Labels"].update({"com.docker.compose.project": "rk-other"})]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                item = deepcopy(container("app"))
                mutate(item)
                with self.assertRaises(AssertionError):
                    CODE["validate_runtime"](item, "app", "rk-test", config()["services"]["app"], 18098)

    def test_failed_docker_output_is_never_relayed(self):
        secret_output = "DB_PASSWORD=synthetic-private\n"
        completed = subprocess.CompletedProcess(["docker"], 1, secret_output, secret_output)
        with patch.object(CODE["subprocess"], "run", return_value=completed):
            with self.assertRaisesRegex(AssertionError, "Docker query failed") as caught:
                CODE["execute"](["docker", "compose", "config"])
        self.assertNotIn("synthetic-private", str(caught.exception))

    def test_health_accepts_standard_groups_but_rejects_details_or_unexpected_groups(self):
        opener = MagicMock()
        response = opener.open.return_value.__enter__.return_value
        response.status = 200
        with patch.dict(CODE["health_check"].__globals__, {"build_opener": lambda *args: opener}):
            for body in ({"status": "UP"}, {"status": "UP", "groups": ["liveness", "readiness"]}):
                response.read.return_value = json.dumps(body).encode()
                result = CODE["health_check"](18098)
                self.assertFalse(result["detailsExposed"])
            for body in ({"status": "UP", "components": {"db": {"password": "synthetic-private"}}},
                         {"status": "UP", "groups": ["private-db"]}, {"status": "DOWN"}):
                response.read.return_value = json.dumps(body).encode()
                with self.assertRaises(AssertionError) as caught:
                    CODE["health_check"](18098)
                self.assertNotIn("synthetic-private", str(caught.exception))

    def test_invalid_project_missing_or_public_environment_fail_before_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / "public.env"
            env.write_text("DB_PASSWORD=synthetic-private\n")
            env.chmod(0o644)
            for project, env_file in (("other", env), ("rk-test", Path(directory) / "missing"), ("rk-test", env)):
                result = subprocess.run([sys.executable, "-I", str(CHECKER), "--project", project,
                                         "--env-file", str(env_file)], text=True, capture_output=True)
                self.assertEqual(1, result.returncode)
                self.assertEqual("", result.stdout)
                self.assertNotIn("synthetic-private", result.stderr)
                self.assertNotIn("Docker query failed", result.stderr)

    def test_config_only_result_is_not_runtime_or_vps_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / "private.env"
            env.write_text("DB_PASSWORD=synthetic-private\n")
            env.chmod(0o600)
            stdout = io.StringIO()
            calls = []

            def query(command):
                calls.append(command)
                return json.dumps(config())

            with patch.dict(CODE["run"].__globals__, {"execute": query, "source_identity": lambda: "a" * 64}):
                with redirect_stdout(stdout):
                    CODE["run"](SimpleNamespace(project="rk-test", env_file=env, config_only=True, compose_override=None))
            result = json.loads(stdout.getvalue())
            self.assertEqual("CONFIG_PASS", result["result"])
            self.assertFalse(result["runtimeVerified"])
            self.assertFalse(result["vpsVerified"])
            self.assertFalse(result["externalReachabilityVerified"])
            self.assertEqual(1, len(calls))
            self.assertNotIn("synthetic-private", stdout.getvalue())

    def test_missing_or_directory_override_is_rejected_before_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / "private.env"
            env.write_text("DB_PASSWORD=synthetic-private\n")
            env.chmod(0o600)
            for override in (Path(directory) / "missing.json", Path(directory)):
                result = subprocess.run([sys.executable, "-I", str(CHECKER), "--project", "rk-test",
                                         "--env-file", str(env), "--compose-override", str(override)],
                                        text=True, capture_output=True)
                self.assertEqual(1, result.returncode)
                self.assertEqual("", result.stdout)
                self.assertIn("existing regular file", result.stderr)
                self.assertNotIn("synthetic-private", result.stderr)
                self.assertNotIn("Docker query failed", result.stderr)

    def test_explicit_override_is_selected_after_base_and_reported_without_contents(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / "private.env"
            env.write_text("DB_PASSWORD=synthetic-private\n")
            env.chmod(0o600)
            override = Path(directory) / "images.json"
            override.write_text(json.dumps({"services": {"app": {"image": "sha256:" + "c" * 64}}}))
            original = override.read_bytes()
            calls = []

            def query(command):
                calls.append(command)
                cfg = config()
                cfg["services"]["app"].update(json.loads(override.read_bytes())["services"]["app"])
                return json.dumps(cfg)

            stdout = io.StringIO()
            with patch.dict(CODE["run"].__globals__, {"execute": query, "source_identity": lambda: "a" * 64}):
                with redirect_stdout(stdout):
                    CODE["run"](SimpleNamespace(project="rk-test", env_file=env, config_only=True,
                                                 compose_override=override))
            report = json.loads(stdout.getvalue())
            self.assertEqual({"path": str(override.resolve()), "sha256": hashlib.sha256(original).hexdigest()},
                             report["composeOverride"])
            files = [calls[0][index + 1] for index, arg in enumerate(calls[0]) if arg == "--file"]
            self.assertEqual(str(override.resolve()), files[-1])
            self.assertTrue(files[0].endswith("infra/compose.yaml"))
            self.assertEqual("CONFIG_PASS", report["result"])
            self.assertNotIn("synthetic-private", stdout.getvalue())

    def test_explicit_sha_image_configuration_accepts_only_matching_runtime_references(self):
        cfg = config()
        for service in ("app", "database"):
            cfg["services"][service]["image"] = "sha256:" + "c" * 64
        self.assertEqual(18098, CODE["validate_config"](cfg, "rk-test"))
        for service in ("app", "database"):
            item = container(service)
            item["Config"]["Image"] = cfg["services"][service]["image"]
            CODE["validate_runtime"](item, service, "rk-test", cfg["services"][service], 18098)
            item["Config"]["Image"] = "sha256:" + "d" * 64
            with self.assertRaisesRegex(AssertionError, "image reference differs"):
                CODE["validate_runtime"](item, service, "rk-test", cfg["services"][service], 18098)
        cfg["services"]["database"]["image"] = "postgres:17.11"
        with self.assertRaisesRegex(AssertionError, "pinned"):
            CODE["validate_config"](cfg, "rk-test")

    def test_publication_override_or_override_changed_during_query_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / "private.env"
            env.write_text("DB_PASSWORD=synthetic-private\n")
            env.chmod(0o600)
            override = Path(directory) / "override.json"
            for unsafe_publication in (True, False):
                override.write_text(json.dumps({"services": {"app": {
                    "ports": [{"host_ip": "0.0.0.0", "target": 8080, "published": "18098"}]}}})
                                    if unsafe_publication else "{}")

                def query(command):
                    cfg = config()
                    if unsafe_publication:
                        cfg["services"]["app"].update(json.loads(override.read_bytes())["services"]["app"])
                    else:
                        override.write_text('{"changed":true}')
                    return json.dumps(cfg)

                stdout = io.StringIO()
                with patch.dict(CODE["run"].__globals__, {"execute": query, "source_identity": lambda: "a" * 64}):
                    with redirect_stdout(stdout):
                        with self.assertRaisesRegex(AssertionError,
                                                    "only container TCP" if unsafe_publication else "override changed"):
                            CODE["run"](SimpleNamespace(project="rk-test", env_file=env, config_only=True,
                                                         compose_override=override))
                self.assertEqual("", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
