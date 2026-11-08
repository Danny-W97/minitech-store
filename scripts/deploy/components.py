#!/usr/bin/env python3
"""Canonical deployable component names for image builds, manifests and rollouts."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def load(root=ROOT):
    inventory = json.loads((Path(root) / 'deploy/components.json').read_text(encoding='utf-8'))
    components = inventory['components']
    names = [item['name'] for item in components]
    if inventory['schemaVersion'] != 1 or len(names) != len(set(names)):
        raise ValueError('Invalid component inventory version or duplicate name')
    for item in components:
        if not re.fullmatch(r'[a-z][a-z0-9-]*', item['name']) or item['runtime'] not in ('jvm', 'nginx'):
            raise ValueError('Invalid component name or runtime')
        if any(name not in names or name == item['name'] for name in item['http']):
            raise ValueError('Unknown or recursive HTTP dependency: ' + item['name'])
        if type(item['redis']) is not bool or any(type(port) is not int or not 1 <= port <= 65535 for port in item.get('publicTcpPorts', [])):
            raise ValueError('Invalid network configuration: ' + item['name'])
    return inventory


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', choices=('jvm', 'nginx'))
    parser.add_argument('--matrix', action='store_true')
    args = parser.parse_args()
    names = [item['name'] for item in load()['components'] if not args.runtime or item['runtime'] == args.runtime]
    print(json.dumps({'service': names}, separators=(',', ':')) if args.matrix else '\n'.join(names))


if __name__ == '__main__':
    main()
