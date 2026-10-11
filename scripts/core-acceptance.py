#!/usr/bin/env python3
"""Run the fixed core demo and exact replay across isolated Compose recreation.

Usage: scripts/core-acceptance.sh --project rk-m07-unique --env-file PATH
       --baseline-commit FULL_COMMIT_SHA
The environment file supplies the password/database/port. The app image tag is
isolated to the selected project. Reports stay under the ignored .runtime tree;
the project and data remain available afterward, including on failure.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def source_identity():
    files = [ROOT / relative for relative in (
        "backend/pom.xml", "backend/Dockerfile", ".dockerignore", "infra/compose.yaml",
        "scripts/core-acceptance.sh", "scripts/core-acceptance.py", "scripts/core-demo.py",
        "scripts/restart-check.py", "scripts/snapshot-ledger.sql", "scripts/check-invariants.sql",
        "scripts/start.sh", "scripts/with-docker.sh")]
    files.extend(path for path in (ROOT / "backend/src/main").rglob("*") if path.is_file())
    files.extend(path for path in (ROOT / "examples").rglob("*") if path.is_file())
    manifest = "\n".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(ROOT)}"
                         for path in sorted(files)) + "\n"
    return manifest, hashlib.sha256(manifest.encode("utf-8")).hexdigest()


def run(args):
    import os
    require(re.fullmatch(r"rk-[a-z0-9][a-z0-9_-]*", args.project) is not None
            and len(args.project) <= 63, "Use a lowercase rk-* project name (maximum 63 characters)")
    require(re.fullmatch(r"[0-9a-f]{40}", args.baseline_commit) is not None,
            "Supply the full 40-character code commit SHA")
    env_file = args.env_file.resolve()
    require(env_file.is_file(), "Provided environment file is missing")
    runner_env = os.environ.copy()
    runner_env["IMAGE_TAG"] = args.project
    docker = [str(ROOT / "scripts/with-docker.sh"), "docker"]
    compose = docker + ["compose", "--project-name", args.project, "--env-file", str(env_file),
                        "--file", str(ROOT / "infra/compose.yaml")]

    def execute(command, input_text=None, log=None, timeout=1800):
        result = subprocess.run(command, cwd=ROOT, env=runner_env, input=input_text,
                                text=True, capture_output=True, timeout=timeout)
        if log:
            log.write_text(result.stdout + result.stderr, encoding="utf-8")
        require(result.returncode == 0, f"Command failed with exit {result.returncode}; "
                + (f"see {log.name}" if log else "check the selected project/environment"))
        return result.stdout.strip()

    require(not execute(docker + ["ps", "--all", "--quiet", "--filter",
                                  f"label=com.docker.compose.project={args.project}"]),
            "Selected project already has containers; choose a new project")
    volume_names = execute(docker + ["volume", "ls", "--format", "{{.Name}}"]).splitlines()
    require(f"{args.project}_database-data" not in volume_names, "Selected project volume already exists")
    require(not execute(docker + ["volume", "ls", "--quiet", "--filter",
                                  f"label=com.docker.compose.project={args.project}"]),
            "Selected project already has a volume; choose a new project")
    network_names = execute(docker + ["network", "ls", "--format", "{{.Name}}"]).splitlines()
    require(f"{args.project}_default" not in network_names, "Selected project network already exists")
    config = json.loads(execute(compose + ["config", "--format", "json"]))
    database = config["services"]["database"]
    app = config["services"]["app"]
    require(not database.get("ports"), "Database must not publish a host port")
    ports = app.get("ports", [])
    require(len(ports) == 1 and ports[0]["host_ip"] == "127.0.0.1"
            and int(ports[0]["target"]) == 8080, "App must publish only loopback port 8080")
    port = int(ports[0]["published"])
    require(1 <= port <= 65535, "Invalid app port")
    require(app["environment"]["SERVER_ADDRESS"] == "0.0.0.0", "Invalid container server address")
    require(config["volumes"]["database-data"]["name"] == f"{args.project}_database-data"
            and not config["volumes"]["database-data"].get("external"), "Volume must be project scoped")
    db_user = database["environment"]["POSTGRES_USER"]
    db_name = database["environment"]["POSTGRES_DB"]
    require(database["environment"]["POSTGRES_PASSWORD"] != "replace-with-a-long-random-password",
            "Replace the example password")
    base = f"http://127.0.0.1:{port}"
    psql = compose + ["exec", "-T", "database", "psql", "-U", db_user, "-d", db_name,
                      "-X", "-v", "ON_ERROR_STOP=1"]
    output = ROOT / ".runtime/core-acceptance" / args.project / uuid.uuid4().hex
    output.mkdir(parents=True, exist_ok=False)
    manifest, source_sha = source_identity()
    (output / "source-manifest.txt").write_text(manifest, encoding="utf-8")
    report = {"result": "IN_PROGRESS", "selectedProject": args.project,
              "baselineCommit": args.baseline_commit, "sourceManifestSha256": source_sha,
              "startedAtUtc": datetime.now(timezone.utc).isoformat(), "baseUrl": base,
              "imageTag": args.project, "outputDirectory": str(output),
              "verificationScope": "local core demo, independent SQL, Compose recreation and exact replay"}

    def save():
        (output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    def identity():
        identities = {}
        for service in ("app", "database"):
            container_id = execute(compose + ["ps", "--quiet", service])
            item = json.loads(execute(docker + ["inspect", container_id]))[0]
            image = json.loads(execute(docker + ["image", "inspect", item["Image"]]))[0]
            identities[service] = {"containerId": item["Id"], "containerImageId": item["Image"],
                                   "imageId": image["Id"], "imageReference": item["Config"]["Image"],
                                   "platform": image["Os"] + "/" + image["Architecture"],
                                   "health": item["State"]["Health"]["Status"],
                                   "user": item["Config"]["User"], "ports": item["NetworkSettings"]["Ports"],
                                   "volumes": [mount["Name"] for mount in item["Mounts"] if mount["Type"] == "volume"]}
        return identities

    def snapshot(name):
        raw = execute(psql + ["-qAt"], (ROOT / "scripts/snapshot-ledger.sql").read_text(encoding="utf-8"))
        result = json.loads(raw)
        (output / name).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        return result

    save()
    try:
        report["phase"] = "build-and-start"
        save()
        execute([str(ROOT / "scripts/start.sh"), args.project, str(env_file)], log=output / "start.log")
        report["runtimeBefore"] = identity()
        require(report["runtimeBefore"]["app"]["user"] == "10001:10001", "App runtime must be nonroot")
        require(report["runtimeBefore"]["database"]["ports"] == {"5432/tcp": None},
                "Database port unexpectedly published")
        require(report["runtimeBefore"]["app"]["ports"] == {
            "8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": str(port)}]}, "Unexpected app port binding")
        report["dockerVersion"] = json.loads(execute(docker + ["version", "--format", "{{json .}} "]))
        report["composeVersion"] = execute(docker + ["compose", "version", "--short"])
        execute(compose + ["exec", "-T", "app", "java", "-version"], log=output / "java-version.log")
        report["javaRuntimeVersion"] = (output / "java-version.log").read_text(encoding="utf-8").strip()
        report["databaseInitiallyEmpty"] = snapshot("database-empty.json")
        require(all(report["databaseInitiallyEmpty"][key] == 0 for key in (
            "invoices", "payments", "allocations", "audits", "idempotencyRecords", "importBatches")),
            "The new project must have a completely empty ledger")
        report["phase"] = "core-demo"
        save()
        execute([sys.executable, "-I", str(ROOT / "scripts/core-demo.py"), "--base-url", base,
                 "--require-empty"], log=output / "demo.json")
        demo = json.loads((output / "demo.json").read_text(encoding="utf-8"))
        require(demo["result"] == "PASS", "Core demo failed")
        execute(psql, (ROOT / "scripts/check-invariants.sql").read_text(encoding="utf-8"),
                log=output / "invariants-before.log")
        report["databaseBefore"] = snapshot("database-before.json")
        report["phase"] = "recreate-containers-preserve-volume"
        save()
        execute(compose + ["down"], log=output / "down.log")
        execute(compose + ["up", "--detach", "--no-build", "--wait", "--wait-timeout", "180"],
                log=output / "restart.log")
        report["runtimeAfter"] = identity()
        for service in ("app", "database"):
            before, after = report["runtimeBefore"][service], report["runtimeAfter"][service]
            require(before["containerId"] != after["containerId"], f"{service} container was not recreated")
            require(before["containerImageId"] == after["containerImageId"], f"{service} image changed")
            require(before["volumes"] == after["volumes"], f"{service} volume changed")
        report["phase"] = "exact-replay-and-independent-checks"
        save()
        execute([sys.executable, "-I", str(ROOT / "scripts/restart-check.py"), "--demo-json",
                 str(output / "demo.json"), "--base-url", base], log=output / "restart-check.json")
        report["databaseAfter"] = snapshot("database-after.json")
        require(report["databaseBefore"] == report["databaseAfter"], "Database counts/response hash changed after replay")
        execute(psql, (ROOT / "scripts/check-invariants.sql").read_text(encoding="utf-8"),
                log=output / "invariants-after.log")
        require(source_identity()[1] == source_sha, "Source changed during verification; use a clean export")
        report.update(result="PASS", phase="complete", exactSavedResponseReplay=True,
                      databaseCountsAndResponseHashUnchanged=True, volumeDeleted=False,
                      finishedAtUtc=datetime.now(timezone.utc).isoformat())
        save()
        print(json.dumps(report, indent=2))
    except Exception as error:
        report.update(result="FAIL", error=str(error), finishedAtUtc=datetime.now(timezone.utc).isoformat())
        save()
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--project", required=True)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--baseline-commit", "--code-commit", dest="baseline_commit", required=True,
                        help="Full baseline commit SHA; the exact tested candidate is identified by its source manifest")
    args = parser.parse_args()
    try:
        run(args)
    except (AssertionError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f"Core acceptance failed: {error}", file=sys.stderr)
        sys.exit(1)
