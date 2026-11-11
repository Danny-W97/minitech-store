#!/usr/bin/env python3
"""Bounded build/scan timing metadata. No logs, environment dumps, credentials or registry calls."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import time


def mark(directory, phase, now=None):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / 'clock.json'
    value = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}
    if phase not in {'build', 'scan'} or phase in value:
        raise ValueError('Invalid or repeated phase')
    value[phase] = time.monotonic_ns() if now is None else now
    path.write_text(json.dumps(value) + '\n', encoding='utf-8')


def finish(directory, service, commit, event, build, scan, image_id='', now=None):
    if (not re.fullmatch('[a-z][a-z0-9-]*', service) or not re.fullmatch('[0-9a-f]{40}', commit)
            or event not in {'pull_request', 'push', 'workflow_dispatch', 'qa'}
            or build not in {'success', 'failure', 'cancelled', 'skipped'}
            or scan not in {'success', 'failure', 'cancelled', 'skipped'}):
        raise ValueError('Invalid evidence metadata')
    if image_id and not re.fullmatch('sha256:[0-9a-f]{64}', image_id):
        raise ValueError('Invalid local image ID')
    now = time.monotonic_ns() if now is None else now
    clock = json.loads((Path(directory) / 'clock.json').read_text(encoding='utf-8'))
    start, scanning = clock.get('build'), clock.get('scan')
    if (type(start) is not int or start < 0 or now < start
            or (scanning is not None and (type(scanning) is not int or not start <= scanning <= now))):
        raise ValueError('Invalid clock evidence')
    passed = build == scan == 'success'
    if passed and (not image_id or scanning is None):
        raise ValueError('Successful build/scan needs image identity and timing')
    result = {'schemaVersion': 1, 'service': service, 'sourceCommit': commit, 'event': event,
              'recordedAt': datetime.now(timezone.utc).isoformat(),
              'status': 'PASS' if passed else 'FAILED', 'buildOutcome': build, 'scanOutcome': scan,
              'localImageId': image_id or None,
              'buildSeconds': round(((now if scanning is None else scanning) - start) / 1e9, 3),
              'scanSeconds': round((now - scanning) / 1e9, 3) if scanning is not None else None,
              'totalSeconds': round((now - start) / 1e9, 3),
              'publicationPerformedByThisTool': False, 'deploymentPerformedByThisTool': False,
              'note': 'Wall time includes action/tool setup; no baseline speedup or complete CVE coverage claim.'}
    (Path(directory) / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('build', 'scan', 'finish'))
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--service')
    parser.add_argument('--commit')
    parser.add_argument('--event')
    parser.add_argument('--build', default='skipped')
    parser.add_argument('--scan', default='skipped')
    parser.add_argument('--image-id', default='')
    args = parser.parse_args()
    try:
        if args.operation == 'finish':
            result = finish(args.directory, args.service or '', args.commit or '', args.event or '',
                            args.build, args.scan, args.image_id)
            print(json.dumps(result))
        else:
            mark(args.directory, args.operation)
        return 0
    except (ValueError, OSError, TypeError):
        print('Image timing evidence unavailable; no success claimed.')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
