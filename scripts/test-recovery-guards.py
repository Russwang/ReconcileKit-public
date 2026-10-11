#!/usr/bin/env python3
"""Focused destructive-target and credential-output guards; no Docker/data writes."""
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

SCRIPTS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('rk_recovery_check', SCRIPTS / 'recovery-check.py')
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)
backup = recovery.backup_module


class RecoveryGuards(unittest.TestCase):
    def stacks(self):
        source = SimpleNamespace(project='rk-source', env_file=Path('/tmp/source.env'),
                                 volume='rk-source_database-data', port=18090)
        restore = SimpleNamespace(project='rk-restore', env_file=Path('/tmp/restore.env'),
                                  volume='rk-restore_database-data', port=18091)
        return source, restore

    def test_each_source_target_alias_is_rejected(self):
        for field in ('project', 'env_file', 'volume', 'port'):
            with self.subTest(field=field):
                source, restore = self.stacks()
                setattr(restore, field, getattr(source, field))
                with self.assertRaises(AssertionError):
                    recovery.require_distinct(source, restore)

    def test_distinct_target_is_accepted(self):
        recovery.require_distinct(*self.stacks())

    def test_existing_project_resource_is_rejected(self):
        for existing in ('container', 'volume', 'network'):
            with self.subTest(existing=existing):
                stack = object.__new__(backup.Stack)
                stack.project, stack.volume, stack.docker = 'rk-restore', 'rk-restore_database-data', ['docker']
                def execute(command, **_):
                    kind = 'container' if command[1] == 'ps' else command[1]
                    return 'existing' if kind == existing else ''
                stack.execute = execute
                with self.assertRaises(AssertionError):
                    stack.require_unused()

    def test_unlabelled_named_volume_or_network_is_rejected(self):
        for kind, name in (('volume', 'rk-restore_database-data'), ('network', 'rk-restore_default')):
            with self.subTest(kind=kind):
                stack = object.__new__(backup.Stack)
                stack.project, stack.volume, stack.docker = 'rk-restore', 'rk-restore_database-data', ['docker']
                stack.execute = lambda command, **_: name if '--format' in command and command[1] == kind else ''
                with self.assertRaises(AssertionError):
                    stack.require_unused()

    def test_private_artifact_modes_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'manifest.json'
            backup.private_json(path, {'scope': 'LOCAL', 'archiveSha256': 'abc'})
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(json.loads(path.read_text()), {'scope': 'LOCAL', 'archiveSha256': 'abc'})
            with self.assertRaises(FileExistsError):
                backup.private_json(path, {})


if __name__ == '__main__':
    unittest.main()
