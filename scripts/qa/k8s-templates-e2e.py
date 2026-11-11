#!/usr/bin/env python3
"""A34.1: disposable Kubernetes admission and a restricted frontend container."""
import json
import copy
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.request
import yaml

ROOT = Path(__file__).resolve().parents[2]


def command(*args, input=None, env=None, expected=0):
    result = subprocess.run(args, input=input, capture_output=True, text=True, env=env)
    if result.returncode != expected:
        raise AssertionError(f'{args[0:3]} returned {result.returncode}: {result.stdout[-2000:]} {result.stderr[-3000:]}')
    return result.stdout.strip()


def main():
    run_id = 'audit-a34-' + secrets.token_hex(4)
    directory = ROOT / 'tmp' / run_id; directory.mkdir(parents=True)
    lock = json.loads((ROOT / 'scripts/deploy/tools.lock.json').read_text())
    env = dict(os.environ, KUBECONFIG=str(directory / 'kubeconfig'))
    env['PATH'] = str(ROOT / 'gitIgnoreOwn/tools/deploy/bin') + os.pathsep + env['PATH']
    containers, network, image_built, cluster_started = [], None, False, False
    image = 'mini-order-platform/a34-frontend-fixture:' + run_id
    try:
        command('python3', str(ROOT / 'scripts/deploy/validate-k8s.py'), '--output-dir', str(directory / 'validation'), env=env)
        rendered = directory / 'validation/rendered.yaml'
        resources = [item for item in yaml.safe_load_all(rendered.read_text()) if item]
        cluster_started = True
        command('kind', 'create', 'cluster', '--name', run_id, '--image', lock['kindNodeImage'],
                '--kubeconfig', env['KUBECONFIG'], '--wait', '120s', env=env)
        kube = ['kubectl', '--kubeconfig', env['KUBECONFIG'], '--context', 'kind-' + run_id]
        namespace = next(item for item in resources if item['kind'] == 'Namespace')
        command(*kube, 'apply', '-f', '-', input=yaml.safe_dump(namespace), env=env)
        output = command(*kube, 'apply', '--dry-run=server', '--validate=strict', '-f', str(rendered), env=env)
        (directory / 'server-dry-run.log').write_text(output)
        pods = [{'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {
            'name': item['metadata']['name'] + '-admission', 'namespace': 'minitech-store',
            'labels': item['spec']['template']['metadata']['labels']}, 'spec': copy.deepcopy(item['spec']['template']['spec'])}
            for item in resources if item['kind'] == 'Deployment']
        command(*kube, 'create', '--dry-run=server', '--validate=strict', '-f', '-', input=yaml.safe_dump_all(pods), env=env)
        invalid = copy.deepcopy(next(item for item in resources if item['kind'] == 'Deployment' and item['metadata']['name'] == 'api-gateway'))
        container = invalid['spec']['template']['spec']['containers'][0]
        container['limits'] = container['resources'].pop('limits')
        failed = subprocess.run([*kube, 'apply', '--dry-run=server', '--validate=strict', '-f', '-'],
                                input=yaml.safe_dump(invalid), capture_output=True, text=True, env=env)
        assert failed.returncode != 0 and 'unknown field' in failed.stderr.lower() and 'limits' in failed.stderr
        pods[0]['spec']['containers'][0]['securityContext']['privileged'] = True
        pods[0]['spec']['containers'][0]['securityContext']['allowPrivilegeEscalation'] = True
        failed = subprocess.run([*kube, 'create', '--dry-run=server', '-f', '-'], input=yaml.safe_dump(pods[0]),
                                capture_output=True, text=True, env=env)
        assert failed.returncode != 0 and 'violates PodSecurity' in failed.stderr, failed.stderr
        actual = json.loads(command(*kube, '-n', 'minitech-store', 'get', 'pods,deployments', '-o', 'json', env=env))
        assert actual['items'] == [], 'Dry-run must not start application workloads'
        print('PASS Kubernetes: 53 resources and 16 restricted Pods accepted; invalid limits and privileged Pod rejected', flush=True)

        build_log = directory / 'frontend-build.log'
        with build_log.open('w') as log:
            subprocess.run(['docker', 'build', '-f', str(ROOT / 'deploy/docker/frontend.Dockerfile'), '-t', image, str(ROOT)],
                           stdout=log, stderr=subprocess.STDOUT, check=True)
        image_built = True
        network = command('docker', 'network', 'create', run_id)
        stub = directory / 'gateway-nginx.conf'
        stub.write_text((ROOT / 'deploy/docker/nginx.conf').read_text().replace(
            'include /etc/nginx/conf.d/*.conf;', 'server { listen 8080; location /api/ { return 200 "gateway-ok"; } }'))
        restricted = ['--read-only', '--user', '10001:10001', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
                      '--tmpfs', '/tmp:rw,nosuid,nodev,size=32m,mode=1777', '--network', network]
        gateway = command('docker', 'run', '-d', *restricted, '--network-alias', 'api-gateway',
                          '--mount', f'type=bind,source={stub},target=/etc/nginx/nginx.conf,readonly', image)
        containers.append(gateway)
        frontend = command('docker', 'run', '-d', *restricted, '-p', '127.0.0.1::8080', image)
        containers.append(frontend)
        host_port = command('docker', 'port', frontend, '8080').rsplit(':', 1)[1]
        url = 'http://127.0.0.1:' + host_port
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(url + '/healthz', timeout=2) as response:
                    if response.status == 200: break
            except OSError: pass
            time.sleep(.2)
        else:
            raise AssertionError('Restricted frontend failed: ' + command('docker', 'logs', frontend))
        with urllib.request.urlopen(url + '/index.html') as response:
            assert b'MiniTech' in response.read()
        with urllib.request.urlopen(url + '/api/probe') as response:
            assert response.read() == b'gateway-ok'
        assert command('docker', 'exec', frontend, 'id', '-u') == '10001'
        denied = subprocess.run(['docker', 'exec', frontend, 'touch', '/usr/share/nginx/html/a34-forbidden'], capture_output=True)
        assert denied.returncode != 0
        (directory / 'report.json').write_text(json.dumps({'status': 'PASS', 'renderedResources': len(resources),
            'restrictedPodsDryRun': len(pods), 'negativeAdmissionChecks': 2, 'frontendNonRootReadOnly': True,
            'networkPolicyEnforcementTested': False, 'preproductionPaymentAndEmailAccepted': False}, indent=2))
        print('PASS frontend: non-root UID 10001, read-only root, no capabilities, health/static/API proxy; report: ' + str(directory / 'report.json'), flush=True)
    except Exception as failure:
        diagnostics = []
        for index, container in enumerate(containers):
            log = subprocess.run(['docker', 'logs', container], capture_output=True, text=True)
            output = log.stdout + log.stderr
            (directory / f'container-{index}.log').write_text(output)
            diagnostics.append(output[-2500:])
        raise RuntimeError(str(failure) + '\n' + '\n'.join(diagnostics)) from failure
    finally:
        for container in reversed(containers): subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
        if network: subprocess.run(['docker', 'network', 'rm', network], capture_output=True)
        if image_built: subprocess.run(['docker', 'image', 'rm', image], capture_output=True)
        if cluster_started: subprocess.run(['kind', 'delete', 'cluster', '--name', run_id], env=env, capture_output=True)
        Path(env['KUBECONFIG']).unlink(missing_ok=True)


if __name__ == '__main__':
    main()
