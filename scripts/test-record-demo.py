#!/usr/bin/env python3
"""Regression guards for recorded HTTP/SQL target identity; no Docker required."""
import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('record_demo', Path(__file__).with_name('record-demo.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RecordingTargetGuards(unittest.TestCase):
    def fixture(self):
        config = {'services': {'database': {'environment': {'POSTGRES_DB': 'selected_db', 'POSTGRES_USER': 'selected_user'}},
                  'app': {'ports': [{'host_ip': '127.0.0.1', 'published': '18115', 'target': 8080}]}}}
        app = {'Config': {'Labels': {'com.docker.compose.project': 'rk-recording-guard', 'com.docker.compose.service': 'app'},
                          'Env': ['DB_URL=jdbc:postgresql://database:5432/selected_db', 'DB_USER=selected_user']},
               'NetworkSettings': {'Ports': {'8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '18115'}]}}}
        database = {'Config': {'Labels': {'com.docker.compose.project': 'rk-recording-guard', 'com.docker.compose.service': 'database'},
                              'Env': ['POSTGRES_DB=selected_db', 'POSTGRES_USER=selected_user']}}
        return config, [app, database]

    def test_wrong_http_port_rejects_before_any_http(self):
        config, inspected = self.fixture()
        with patch.object(module.OPENER, 'open') as requests:
            with self.assertRaises(AssertionError):
                module.selected_stack(config, inspected, 'rk-recording-guard', 18116)
            requests.assert_not_called()

    def test_actual_port_project_and_database_drift_reject(self):
        config, original = self.fixture()
        for kind in ('port', 'project', 'database'):
            inspected = copy.deepcopy(original)
            if kind == 'port':
                inspected[0]['NetworkSettings']['Ports']['8080/tcp'][0]['HostPort'] = '18116'
            elif kind == 'project':
                inspected[1]['Config']['Labels']['com.docker.compose.project'] = 'rk-other'
            else:
                inspected[0]['Config']['Env'][0] = 'DB_URL=jdbc:postgresql://database:5432/other_db'
            with self.subTest(kind=kind), self.assertRaises(AssertionError):
                module.selected_stack(config, inspected, 'rk-recording-guard', 18115)

    def test_selected_stack_returns_actual_configured_sql_identity(self):
        config, inspected = self.fixture()
        self.assertEqual(('selected_db', 'selected_user'), module.selected_stack(config, inspected, 'rk-recording-guard', 18115))


if __name__ == '__main__':
    unittest.main()
