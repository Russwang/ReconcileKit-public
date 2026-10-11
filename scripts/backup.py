#!/usr/bin/env python3
"""Back up one explicitly selected local Compose database; never restore over it."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def private_json(path, value):
    with path.open('x', encoding='utf-8') as stream:
        os.chmod(path, 0o600)
        json.dump(value, stream, indent=2)
        stream.write('\n')


def project_name(value):
    require(re.fullmatch(r'rk-[a-z0-9][a-z0-9_-]*', value) is not None and len(value) <= 63,
            'Use a lowercase rk-* project name of at most 63 characters')
    return value


class Stack:
    """Keep sensitive Compose configuration in memory; only report selected identities."""
    def __init__(self, project, env_file, source_root=ROOT, image_tag=None):
        self.project = project_name(project)
        self.env_file = Path(env_file).resolve()
        self.root = Path(source_root).resolve()
        require(self.env_file.is_file(), 'Selected environment file is missing')
        require((self.root / 'infra/compose.yaml').is_file(), 'Selected Compose file is missing')
        require((ROOT / 'scripts/with-docker.sh').is_file(), 'Workspace Docker wrapper is missing')
        self.env = os.environ.copy()
        for key in ('DB_NAME', 'DB_USER', 'DB_PASSWORD', 'APP_PORT', 'IMAGE_TAG'):
            self.env.pop(key, None)
        if image_tag:
            require(re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}', image_tag) is not None,
                    'Invalid selected image tag')
            self.env['IMAGE_TAG'] = image_tag
        require(not self.env.get('DOCKER_HOST') or self.env['DOCKER_HOST'].startswith('unix://'),
                'This workflow accepts only a local Unix Docker endpoint')
        self.docker = [str(ROOT / 'scripts/with-docker.sh'), 'docker']
        endpoint = self.execute(self.docker + ['context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'])
        require(endpoint.startswith('unix://'), 'This workflow requires a local Unix Docker context')
        self.compose = self.docker + ['compose', '--project-name', self.project, '--env-file',
            str(self.env_file), '--file', str(self.root / 'infra/compose.yaml')]
        self.config = json.loads(self.execute(self.compose + ['config', '--format', 'json']))
        database, app = (self.config['services'][name] for name in ('database', 'app'))
        require(not database.get('ports'), 'Database must not publish a host port')
        ports = app.get('ports', [])
        require(len(ports) == 1 and ports[0]['host_ip'] == '127.0.0.1'
                and int(ports[0]['target']) == 8080, 'App must publish only a loopback port')
        self.port = int(ports[0]['published'])
        require(1 <= self.port <= 65535, 'Invalid app port')
        volume = self.config['volumes']['database-data']
        self.volume = f'{project}_database-data'
        require(volume['name'] == self.volume and not volume.get('external'),
                'Database volume must belong only to the selected project')
        require(len(database['volumes']) == 1 and database['volumes'][0]['type'] == 'volume'
                and database['volumes'][0]['source'] == 'database-data'
                and database['volumes'][0]['target'] == '/var/lib/postgresql/data',
                'Unexpected database data mount')
        require(not app.get('volumes'), 'App must not mount an existing data volume')
        self.user = database['environment']['POSTGRES_USER']
        self.database = database['environment']['POSTGRES_DB']
        password = database['environment']['POSTGRES_PASSWORD']
        require(password and password != 'replace-with-a-long-random-password',
                'Set a nonempty nonexample database password')
        self.base = f'http://127.0.0.1:{self.port}'

    def execute(self, command, input_bytes=None, log=None, timeout=300):
        result = subprocess.run(command, cwd=self.root, env=self.env, input=input_bytes,
                                capture_output=True, timeout=timeout)
        if log:
            # Compose progress/psql validation logs are allowed; configuration is never logged.
            with Path(log).open('xb') as stream:
                os.chmod(log, 0o600)
                stream.write(result.stdout + result.stderr)
        require(result.returncode == 0,
                f'Command failed with exit {result.returncode}' +
                (f'; see {Path(log).name}' if log else '; private configuration was not printed'))
        return result.stdout.decode('utf-8').strip()

    def sql(self, sql, log=None):
        return self.execute(self.compose + ['exec', '-T', 'database', 'psql', '-U', self.user,
            '-d', self.database, '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'],
            input_bytes=sql.encode('utf-8'), log=log)

    def identity(self, service):
        identity = self.execute(self.compose + ['ps', '--quiet', service])
        require(identity and '\n' not in identity, f'Expected one running {service} container')
        # Deliberately omit Config.Env: it contains credentials.
        template = '{"id":{{json .Id}},"imageId":{{json .Image}},"imageReference":{{json .Config.Image}},' \
            '"labels":{{json .Config.Labels}},"mounts":{{json .Mounts}},"ports":{{json .NetworkSettings.Ports}},' \
            '"health":{{json .State.Health.Status}}}'
        item = json.loads(self.execute(self.docker + ['inspect', '--format', template, identity]))
        labels = item.pop('labels')
        require(labels['com.docker.compose.project'] == self.project
                and labels['com.docker.compose.service'] == service, 'Container identity mismatch')
        item['volumes'] = sorted(mount['Name'] for mount in item.pop('mounts') if mount['Type'] == 'volume')
        require(item['health'] == 'healthy', f'{service} must be healthy before verification')
        if service == 'database':
            # Check only the two selected database identifiers inside the container.
            # No environment listing or password is read, printed, or persisted.
            self.execute(self.docker + ['exec', item['id'], 'sh', '-c',
                'test "$POSTGRES_DB" = "$1" && test "$POSTGRES_USER" = "$2"',
                'reconcilekit-database-identity', self.database, self.user])
            require(item['volumes'] == [self.volume], 'Selected database has unexpected volumes')
            require(item['ports'] == {'5432/tcp': None}, 'Database port is published')
        else:
            require(item['ports'] == {'8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': str(self.port)}]},
                    'App runtime loopback binding differs from the selected configuration')
        return item

    def require_unused(self):
        for kind in ('container', 'volume', 'network'):
            listing = (['ps', '--all', '--quiet'] if kind == 'container' else [kind, 'ls', '--quiet'])
            require(not self.execute(self.docker + listing + ['--filter',
                f'label=com.docker.compose.project={self.project}']),
                f'Restore project already has a {kind}; choose an unused project')
        for kind, expected in (('volume', self.volume), ('network', f'{self.project}_default')):
            names = self.execute(self.docker + [kind, 'ls', '--format', '{{.Name}}']).splitlines()
            require(expected not in names, f'Restore {kind} name already exists')


def backup(stack, output):
    output = Path(output).resolve()
    output.mkdir(parents=True, mode=0o700, exist_ok=False)
    os.chmod(output, 0o700)
    database = stack.identity('database')
    archive = output / 'database.dump'
    try:
        with archive.open('xb') as stream:
            os.chmod(archive, 0o600)
            result = subprocess.run(stack.compose + ['exec', '-T', 'database', 'pg_dump',
                '-U', stack.user, '-d', stack.database, '--format=custom', '--no-owner', '--no-acl'],
                cwd=stack.root, env=stack.env, stdout=stream, stderr=subprocess.PIPE, timeout=300)
        require(result.returncode == 0 and archive.stat().st_size > 0,
                'pg_dump failed; partial private archive retained for diagnosis')
        digest = sha256(archive)
        manifest = {'formatVersion': 1, 'scope': 'LOCAL selected Compose database only; VPS not verified',
                    'createdAtUtc': datetime.now(timezone.utc).isoformat(),
                    'sourceProject': stack.project, 'sourceDatabase': stack.database,
                    'sourceVolume': stack.volume, 'sourceDatabaseContainerId': database['id'],
                    'sourceDatabaseImageId': database['imageId'],
                    'sourceDatabaseImageReference': database['imageReference'],
                    'postgresVersion': stack.sql('SHOW server_version;'),
                    'archive': archive.name, 'archiveFormat': 'custom', 'noOwner': True,
                    'noAcl': True, 'archiveSha256': digest, 'archiveBytes': archive.stat().st_size}
        private_json(output / 'manifest.json', manifest)
        with (output / 'database.dump.sha256').open('x', encoding='ascii') as stream:
            os.chmod(stream.name, 0o600)
            stream.write(f'{digest}  database.dump\n')
        return manifest
    except Exception:
        private_json(output / 'failure.json', {'result': 'FAIL', 'phase': 'backup',
                     'scope': 'LOCAL', 'sourceProject': stack.project})
        raise


def add_source_arguments(parser):
    parser.add_argument('--project', required=True)
    parser.add_argument('--env-file', required=True, type=Path)
    parser.add_argument('--source-root', type=Path, default=ROOT,
                        help='Directory containing the selected infra/compose.yaml')
    parser.add_argument('--image-tag', help='Explicit tag if the selected running image overrides the env file')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    add_source_arguments(parser)
    parser.add_argument('--output-dir', required=True, type=Path, help='New private backup directory')
    args = parser.parse_args()
    manifest = backup(Stack(args.project, args.env_file, args.source_root, args.image_tag), args.output_dir)
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (AssertionError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f'Backup failed: {error}', file=sys.stderr)
        sys.exit(1)
