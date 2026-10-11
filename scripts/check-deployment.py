#!/usr/bin/env python3
"""Inspect one selected Compose deployment without changing containers or data.

Only the sanitized JSON report reaches stdout. Compose configuration and full
container inspection include credentials and must never be saved or printed.
--config-only checks intended configuration; it does not verify a running image.
--compose-override selects the same explicit additional Compose file used to
create the containers, including recovery's content-ID image references.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import stat
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def execute(command):
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=30)
    # Do not relay command output: config/inspect/errors can contain credentials.
    require(result.returncode == 0, "Docker query failed; inspect the selected project privately")
    return result.stdout.strip()


def source_identity():
    paths = [ROOT / relative for relative in (
        "backend/pom.xml", "backend/Dockerfile", ".dockerignore", "infra/compose.yaml",
        "scripts/start.sh", "scripts/with-docker.sh", "scripts/check-deployment.sh",
        "scripts/check-deployment.py")]
    paths.extend(path for path in (ROOT / "backend/src/main").rglob("*") if path.is_file())
    manifest = "\n".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(ROOT)}"
                         for path in sorted(paths)) + "\n"
    return hashlib.sha256(manifest.encode()).hexdigest()


def validate_config(config, project):
    services = config["services"]
    require(set(services) == {"app", "database"}, "Expected only app/database services")
    app, database = services["app"], services["database"]
    for service in (app, database):
        require(not service.get("privileged") and service.get("network_mode") != "host"
                and service.get("pid") != "host", "Host namespaces or privileged mode are forbidden")
    require(not database.get("ports"), "Database must not publish any host port")
    require(re.fullmatch(r"(?:.+@)?sha256:[0-9a-f]{64}", database["image"]) is not None,
            "Database image must be digest or full content-ID pinned")
    ports = app.get("ports", [])
    require(len(ports) == 1 and ports[0].get("host_ip") == "127.0.0.1"
            and ports[0].get("protocol", "tcp") == "tcp" and int(ports[0]["target"]) == 8080,
            "App must publish only container TCP 8080 on host 127.0.0.1")
    port = int(ports[0]["published"])
    require(1 <= port <= 65535, "App port must be between 1 and 65535")
    require(app.get("read_only") is True and set(app.get("cap_drop", [])) == {"ALL"}
            and not app.get("cap_add") and not app.get("volumes"),
            "App requires a read-only filesystem, all capabilities dropped and no data mounts")
    require("/tmp" in app.get("tmpfs", [])
            and "no-new-privileges:true" in app.get("security_opt", []),
            "App requires /tmp tmpfs and no-new-privileges")
    require(app["environment"].get("SERVER_ADDRESS") == "0.0.0.0"
            and str(app["environment"].get("PORT")) == "8080", "Unexpected container listen address")
    password = database["environment"].get("POSTGRES_PASSWORD", "")
    require(password and password != "replace-with-a-long-random-password"
            and app["environment"].get("DB_PASSWORD") == password,
            "Set matching nonempty private database credentials")
    volume = config["volumes"]["database-data"]
    require(volume["name"] == f"{project}_database-data" and not volume.get("external"),
            "Database volume must be scoped to the selected project")
    return port


def validate_runtime(item, service, project, config, port):
    labels = item["Config"].get("Labels", {})
    require(labels.get("com.docker.compose.project") == project
            and labels.get("com.docker.compose.service") == service,
            "Container does not belong to the selected project/service")
    require(item["State"]["Running"] is True
            and item["State"].get("Health", {}).get("Status") == "healthy", "Selected service is not healthy")
    host = item["HostConfig"]
    require(host.get("Privileged") is False and host.get("NetworkMode") != "host"
            and host.get("PidMode") != "host", "Runtime uses privileged mode or host namespaces")
    runtime_env = dict(value.split("=", 1) for value in item["Config"].get("Env", []) if "=" in value)
    require(all(runtime_env.get(key) == str(value) for key, value in config["environment"].items()),
            "Runtime environment does not match the selected resolved Compose configuration")
    require(item["Config"]["Image"] == config["image"], "Runtime image reference differs from selected config")
    published = {key: value for key, value in item["NetworkSettings"].get("Ports", {}).items() if value}
    if service == "app":
        require(published == {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": str(port)}]},
                "Running app has an unexpected host publication")
        require(item["Config"].get("User") == "10001:10001", "App must run as user/group 10001")
        require(host.get("ReadonlyRootfs") is True and set(host.get("CapDrop") or []) == {"ALL"}
                and not host.get("CapAdd") and "/tmp" in (host.get("Tmpfs") or {})
                and "no-new-privileges:true" in (host.get("SecurityOpt") or []),
                "Running app restrictions differ from the expected configuration")
        require(not any(mount["Type"] in ("bind", "volume") for mount in item.get("Mounts", [])),
                "Running app must not mount host files or persistent data")
    else:
        require(not published, "Running database publishes a host port")
        mounts = [mount for mount in item.get("Mounts", []) if mount["Type"] == "volume"]
        require(len(mounts) == 1 and mounts[0].get("Name") == f"{project}_database-data"
                and mounts[0]["Destination"] == "/var/lib/postgresql/data", "Unexpected database data volume")
    return {"containerId": item["Id"], "imageId": item["Image"],
            "imageReference": item["Config"]["Image"], "health": "healthy",
            "user": item["Config"].get("User", ""), "publishedPorts": published}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def health_check(port):
    opener = build_opener(ProxyHandler({}), NoRedirect())
    try:
        request = Request(f"http://127.0.0.1:{port}/actuator/health", headers={"Accept": "application/json"})
        with opener.open(request, timeout=5) as response:
            body = response.read(65537)
            require(response.status == 200 and len(body) <= 65536, "Health response was unsuccessful or oversized")
            health = json.loads(body)
            require(isinstance(health, dict) and set(health) <= {"status", "groups"}
                    and health.get("status") == "UP", "Health must not expose components or details")
            groups = health.get("groups", [])
            require(isinstance(groups, list) and all(isinstance(group, str)
                    and group in {"liveness", "readiness"} for group in groups)
                    and len(groups) == len(set(groups)), "Health exposes an unexpected group name")
    except (HTTPError, URLError) as error:
        raise AssertionError("Runner loopback health request failed; no proxy or redirect was used") from error
    return {"status": "UP", "groups": groups, "detailsExposed": False,
            "proxyUsed": False, "redirectFollowed": False}


def run(args):
    require(re.fullmatch(r"rk-[a-z0-9][a-z0-9_-]*", args.project) is not None and len(args.project) <= 63,
            "Use a lowercase rk-* project name, maximum 63 characters")
    env_file = args.env_file.resolve()
    require(env_file.is_file(), "Selected environment file is missing")
    require(stat.S_IMODE(env_file.stat().st_mode) & 0o077 == 0, "Environment file must have no group/other permissions")
    override = args.compose_override.resolve() if args.compose_override else None
    override_bytes = None
    if override is not None:
        require(override.is_file(), "Selected Compose override must be an existing regular file")
        override_bytes = override.read_bytes()
    docker = [str(ROOT / "scripts/with-docker.sh"), "docker"]
    compose = docker + ["compose", "--project-name", args.project, "--env-file", str(env_file),
                        "--file", str(ROOT / "infra/compose.yaml")]
    if override is not None:
        compose.extend(["--file", str(override)])
    config = json.loads(execute(compose + ["config", "--format", "json"]))
    port = validate_config(config, args.project)
    source_sha = source_identity()
    report = {"result": "CONFIG_PASS", "checkedAtUtc": datetime.now(timezone.utc).isoformat(),
              "selectedProject": args.project, "sourceManifestSha256": source_sha,
              "composeOverride": {"path": str(override), "sha256": hashlib.sha256(override_bytes).hexdigest()}
                                 if override is not None else None,
              "scope": "selected Compose configuration", "runtimeVerified": False,
              "vpsVerified": False, "externalReachabilityVerified": False,
              "config": {"appLoopbackPort": port, "databasePublishedPorts": [],
                         "databaseImage": config["services"]["database"]["image"],
                         "databaseVolume": f"{args.project}_database-data", "environmentFilePrivate": True}}
    if not args.config_only:
        version = json.loads(execute(docker + ["version", "--format", "{{json .Server}} "]))
        require(int(version["Version"].split(".", 1)[0]) >= 28,
                "Docker Engine 28+ is required for the documented loopback publication boundary")
        report["dockerEngineVersion"] = version["Version"]
        report["composeVersion"] = execute(docker + ["compose", "version", "--short"])
        report["runtime"] = {}
        for service in ("app", "database"):
            container_id = execute(compose + ["ps", "--quiet", service]).splitlines()
            require(len(container_id) == 1 and re.fullmatch(r"[0-9a-f]{64}", container_id[0]) is not None,
                    "Expected exactly one running container for each selected service")
            item = json.loads(execute(docker + ["inspect", container_id[0]]))[0]
            identity = validate_runtime(item, service, args.project, config["services"][service], port)
            image = json.loads(execute(docker + ["image", "inspect", item["Image"]]))[0]
            require(image["Id"] == item["Image"], "Runtime image identity could not be confirmed")
            identity["platform"] = image["Os"] + "/" + image["Architecture"]
            report["runtime"][service] = identity
        status = execute(compose + ["exec", "-T", "app", "cat", "/proc/1/status"])
        fields = dict(line.split(":", 1) for line in status.splitlines() if ":" in line)
        require(fields["Uid"].split() == ["10001"] * 4 and fields["Gid"].split() == ["10001"] * 4
                and int(fields["CapEff"], 16) == 0 and fields["NoNewPrivs"].strip() == "1",
                "Java PID 1 does not have the expected UID/GID/capability restrictions")
        report["javaPid1"] = {"uid": 10001, "gid": 10001, "effectiveCapabilities": 0, "noNewPrivileges": True}
        report["health"] = health_check(port)
        report.update(result="PASS", scope="selected running containers and runner loopback health",
                      runtimeVerified=True)
    if override is not None:
        require(override.is_file() and override.read_bytes() == override_bytes,
                "Compose override changed while checking; rerun with a stable override")
    require(source_identity() == source_sha, "Source changed while checking; rerun on a stable checkout")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--env-file", required=True, type=Path)
    parser.add_argument("--compose-override", type=Path,
                        help="Explicit additional Compose file used to create the selected containers")
    parser.add_argument("--config-only", action="store_true")
    try:
        run(parser.parse_args())
    except (AssertionError, OSError, ValueError, KeyError, TypeError, IndexError, subprocess.SubprocessError) as error:
        # Fixed assertion messages are safe; other exceptions may contain untrusted data.
        message = str(error) if isinstance(error, AssertionError) else "Query or response processing failed"
        print(f"Deployment check failed: {message}", file=sys.stderr)
        sys.exit(1)
