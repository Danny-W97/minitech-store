#!/usr/bin/env python3
"""Install pinned, SHA256-checked validation tools in the project's ignored cache."""
import argparse
import hashlib
import io
import json
import platform
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def install(name, entry, destination):
    archives = destination.parent / 'archives'; archives.mkdir(parents=True, exist_ok=True)
    archive = archives / (name + '-' + entry['version'])
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != entry['sha256']:
        temporary = archive.with_suffix('.download')
        digest = hashlib.sha256()
        try:
            with urllib.request.urlopen(entry['url'], timeout=60) as response, temporary.open('wb') as output:
                while chunk := response.read(1024 * 1024):
                    digest.update(chunk); output.write(chunk)
            if digest.hexdigest() != entry['sha256']:
                raise ValueError('SHA256 mismatch for ' + name)
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    data = archive.read_bytes()
    if 'member' in entry:
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as bundle:
            member = bundle.extractfile(entry['member'])
            if member is None:
                raise ValueError('Missing executable in ' + name)
            data = member.read()
    destination.mkdir(parents=True, exist_ok=True)
    executable = destination / name
    if not executable.exists() or executable.read_bytes() != data:
        executable.write_bytes(data)
    executable.chmod(0o755)
    print('Verified ' + name + ' ' + entry['version'], flush=True)


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--with-kind', action='store_true'); args = parser.parse_args()
    if platform.system() != 'Linux' or platform.machine() not in ('x86_64', 'amd64'):
        parser.exit(1, 'This locked installer targets Linux amd64; run it in WSL or install matching tools manually.\n')
    lock = json.loads((ROOT / 'scripts/deploy/tools.lock.json').read_text())
    destination = ROOT / 'gitIgnoreOwn/tools/deploy/bin'
    for name in ['kubectl', 'kubeconform'] + (['kind'] if args.with_kind else []):
        install(name, lock[name], destination)
    print('Tool directory: ' + str(destination))


if __name__ == '__main__':
    main()
