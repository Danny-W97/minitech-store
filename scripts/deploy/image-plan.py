#!/usr/bin/env python3
"""Plan image builds from local Git objects. No API calls, credentials, builds or publication."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

from components import load

ROOT = Path(__file__).resolve().parents[2]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def text(node, name):
    return node.findtext('m:' + name, default='', namespaces=NS).strip()


def select(root, changed, force_all=False, fallback=None):
    root = Path(root).resolve()
    inventory = load(root)['components']
    names = [row['name'] for row in inventory]
    jvm = {row['name'] for row in inventory if row['runtime'] == 'jvm'}
    frontend = {row['name'] for row in inventory if row['runtime'] == 'nginx'}
    reasons = {}

    def add(code, images):
        reasons.setdefault(code, set()).update(images)

    if force_all or fallback:
        add(fallback or 'FULL_EVENT', names)
    else:
        parent = ET.parse(root / 'pom.xml').getroot()
        modules = {node.text.strip() for node in parent.findall('m:modules/m:module', NS)}
        if modules != jvm | {'platform-security', 'qa-tests'}:
            raise ValueError('Unreviewed reactor modules')
        reverse = {name: set() for name in modules}
        for module in modules:
            model = ET.parse(root / module / 'pom.xml').getroot()
            for dep in model.findall('m:dependencies/m:dependency', NS):
                artifact, group = text(dep, 'artifactId'), text(dep, 'groupId')
                if artifact in modules and (group == text(parent, 'groupId') or '${' in group):
                    reverse[artifact].add(module)

        def dependents(module):
            seen, todo = set(), [module]
            while todo:
                item = todo.pop()
                if item in seen:
                    continue
                seen.add(item)
                todo.extend(reverse.get(item, ()))
            return seen & jvm

        for path in changed:
            parts = path.split('/')
            if (not path or path.startswith('/') or '\\' in path or '..' in parts
                    or any(ord(char) < 32 for char in path)):
                add('UNCLASSIFIED_PATH', names)
            elif path in {'pom.xml', 'mvnw', 'mvnw.cmd'} or path.startswith('.mvn/'):
                add('MAVEN_PARENT_OR_WRAPPER', jvm)
            elif path == '.dockerignore' or path == 'deploy/components.json':
                add('SHARED_BUILD_INPUT', names)
            elif path.startswith(('.github/workflows/', '.github/actions/', 'scripts/deploy/')):
                add('PIPELINE_INPUT', names)
            elif path == 'deploy/docker/service.Dockerfile':
                add('JVM_DOCKERFILE', jvm)
            elif path in {'deploy/docker/frontend.Dockerfile', 'deploy/docker/nginx.conf', 'deploy/docker/nginx.frontend.conf'}:
                add('FRONTEND_DOCKER_INPUT', frontend)
            elif path.startswith('deploy/docker/'):
                add('UNCLASSIFIED_DOCKER_INPUT', names)
            elif len(parts) == 2 and parts[0] in modules and parts[1] == 'pom.xml':
                # The service Dockerfile copies every reactor POM before invoking Maven.
                add('REACTOR_POM', jvm)
            elif parts[0] in modules:
                add('MODULE_AND_MAVEN_DEPENDENTS', dependents(parts[0]))
            elif parts[0] == 'frontend':
                add('FRONTEND_SOURCE', frontend)
            elif path == 'scripts/qa/contracts/technical-data-classification.json':
                add('PACKAGED_CUSTOMER_POLICY', {'customer-service'})
            elif path.startswith(('docs/', 'e2e/', 'scripts/qa/')) or path in {
                    'README.md', '.gitignore', 'package.json', 'pnpm-lock.yaml',
                    'playwright.config.ts', 'playwright.control-center.config.ts'}:
                # These inputs are not copied into either production image; verify still runs.
                continue
            else:
                add('UNCLASSIFIED_INPUT', names)

    selected = set().union(*reasons.values()) if reasons else set()
    services = [name for name in names if name in selected]
    return {'schemaVersion': 1, 'matrix': {'service': services}, 'hasImages': bool(services),
            'changedFiles': len(changed), 'selectedImages': len(services), 'allImages': len(names),
            'selectionMode': 'fallback-full' if fallback else ('full' if force_all else 'affected'),
            'reasons': [{'code': code, 'images': sorted(images)} for code, images in sorted(reasons.items())],
            'publicationRequested': False, 'deploymentRequested': False}


def changed_paths(root, base, head):
    if not all(isinstance(sha, str) and re.fullmatch(r'[0-9a-f]{40}', sha) for sha in (base, head)):
        raise ValueError('Invalid revision')
    def git(*args):
        return subprocess.run(['git', '-c', 'core.fsmonitor=false', *args], cwd=root,
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30, check=True).stdout
    git('cat-file', '-e', base + '^{commit}')
    git('cat-file', '-e', head + '^{commit}')
    git('merge-base', '--is-ancestor', base, head)
    raw = git('diff', '--name-only', '-z', '--no-renames', base, head, '--')
    if len(raw) > 2 * 1024 * 1024:
        raise ValueError('Diff budget exceeded')
    paths = [value.decode('utf-8', errors='strict') for value in raw.split(b'\0') if value]
    if len(paths) > 10000:
        raise ValueError('Path budget exceeded')
    return paths


def plan(root, event_name, event, head):
    if event_name != 'pull_request':
        return select(root, [], force_all=True)
    try:
        paths = changed_paths(root, event['pull_request']['base']['sha'], head)
    except (KeyError, TypeError, ValueError, OSError, subprocess.SubprocessError, UnicodeError):
        return select(root, [], fallback='DIFF_UNAVAILABLE')
    try:
        return select(root, paths)
    except (ET.ParseError, OSError, KeyError, TypeError, ValueError):
        return select(root, paths, fallback='BUILD_GRAPH_UNAVAILABLE')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        event_name = os.environ.get('GITHUB_EVENT_NAME', '')
        head = os.environ.get('GITHUB_SHA', '')
        event = {}
        if event_name == 'pull_request':
            path = Path(os.environ.get('GITHUB_EVENT_PATH', ''))
            if path.is_file() and path.stat().st_size <= 2 * 1024 * 1024:
                try:
                    event = json.loads(path.read_text(encoding='utf-8'))
                except (ValueError, OSError):
                    pass
        result = plan(args.root, event_name, event, head)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        if os.environ.get('GITHUB_OUTPUT'):
            with Path(os.environ['GITHUB_OUTPUT']).open('a', encoding='utf-8') as stream:
                stream.write('matrix=' + json.dumps(result['matrix'], separators=(',', ':')) + '\n')
                stream.write('has_images=' + str(result['hasImages']).lower() + '\n')
        print(json.dumps(result))
        return 0
    except (ValueError, OSError, KeyError, TypeError, ET.ParseError):
        print('Image plan rejected: component inventory or output is invalid.')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
