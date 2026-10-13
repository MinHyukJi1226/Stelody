import os
from pathlib import Path
import subprocess
import textwrap
import unittest

from select_checks import select_checks


class CheckSelectionTests(unittest.TestCase):
    def test_paths(self):
        cases = [
            (['frontend/src/main.tsx'], (True, False)),
            (['backend/build.gradle', 'infra/compose.yml'], (False, True)),
            (['frontend/package.json', 'backend/Dockerfile'], (True, True)),
            (['.github/workflows/frontend-ci.yml'], (True, True)),
            (['.env.example'], (True, True)),
            ([], (False, False)),
        ]
        for paths, expected in cases:
            with self.subTest(paths=paths):
                self.assertEqual(select_checks(paths), expected)

    def test_required_gate(self):
        workflow = Path(__file__).parents[1] / 'workflows/backend-ci.yml'
        script = textwrap.dedent(workflow.read_text().rsplit('        run: |\n', 1)[1])
        defaults = {
            'CHANGES_RESULT': 'success', 'BACKEND_REQUIRED': 'false',
            'FRONTEND_REQUIRED': 'true', 'BACKEND_RESULT': 'skipped',
            'FRONTEND_RESULT': 'success', 'IMAGE_RESULT': 'skipped',
            'CI_EVENT': 'pull_request',
        }
        cases = [
            ({}, True),
            ({'FRONTEND_RESULT': 'failure'}, False),
            ({'FRONTEND_RESULT': 'cancelled'}, False),
            ({'FRONTEND_RESULT': 'skipped'}, False),
            ({'CHANGES_RESULT': 'failure'}, False),
            ({'BACKEND_REQUIRED': ''}, False),
            ({'BACKEND_REQUIRED': 'true', 'BACKEND_RESULT': 'success',
              'FRONTEND_REQUIRED': 'false', 'FRONTEND_RESULT': 'skipped'}, True),
            ({'BACKEND_REQUIRED': 'true', 'BACKEND_RESULT': 'success'}, True),
            ({'CI_EVENT': 'push', 'BACKEND_REQUIRED': 'true',
              'BACKEND_RESULT': 'success', 'IMAGE_RESULT': 'success'}, True),
            ({'CI_EVENT': 'push', 'BACKEND_REQUIRED': 'true',
              'BACKEND_RESULT': 'success'}, False),
        ]
        for overrides, expected in cases:
            with self.subTest(overrides=overrides):
                result = subprocess.run(
                    ['bash', '-e', '-c', script],
                    env={**os.environ, **defaults, **overrides}, check=False,
                )
                self.assertEqual(result.returncode == 0, expected)


if __name__ == '__main__':
    unittest.main()
