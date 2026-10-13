"""Select PR checks; main pushes retain the complete release validation."""

import os
import subprocess


def select_checks(paths):
    frontend = backend = False
    for path in paths:
        if path.startswith('frontend/'):
            frontend = True
        elif path.startswith(('backend/', 'infra/')):
            backend = True
        else:
            # Shared configuration and unknown paths require both checks.
            frontend = backend = True
    return frontend, backend


if __name__ == '__main__':
    if os.environ['CI_EVENT'] == 'pull_request':
        changes = subprocess.check_output([
            'git', 'diff', '--name-only', '--no-renames', '-z',
            os.environ['BASE_SHA'] + '...' + os.environ['HEAD_SHA'],
        ])
        paths = changes.decode().split('\0')
        frontend, backend = select_checks(path for path in paths if path)
    else:
        frontend = backend = True
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f'frontend={str(frontend).lower()}\n')
        output.write(f'backend={str(backend).lower()}\n')
