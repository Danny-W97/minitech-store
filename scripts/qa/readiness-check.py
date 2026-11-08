#!/usr/bin/env python3
"""Review public probe configuration against the component inventory; never load runtime secrets."""
import argparse
import json
from pathlib import Path
import re
import yaml

ROOT = Path(__file__).resolve().parents[2]


def require(value, message):
    if not value:raise ValueError(message)


def flatten(value, prefix='', result=None):
    result = {} if result is None else result
    for key, child in value.items():
        name = prefix + str(key)
        if isinstance(child, dict):flatten(child, name + '.', result)
        else:
            require(name not in result, 'Duplicate flattened configuration key')
            result[name] = child
    return result


def default(value):
    if isinstance(value, str):
        match = re.fullmatch(r'\$\{[A-Z0-9_]+:([^{}]+)\}', value)
        if match:value = match[1]
    return str(value).lower()


def members(value):
    if isinstance(value, str):return [v.strip() for v in value.split(',')]
    return value


def check(root=ROOT):
    root = Path(root)
    components = [c for c in json.loads((root/'deploy/components.json').read_text())['components'] if c['runtime']=='jvm']
    require(len(components)==15 and sum(bool(c.get('database')) for c in components)==14, 'Review the changed component inventory')
    reviewed = []
    group = 'management.endpoint.health.group.'
    for component in components:
        name = component['name'];directory = root/name/'src/main/resources'
        base = directory/'application.yaml';require(base.is_file(), 'Missing base application configuration')
        baseline = flatten(yaml.safe_load(base.read_text(encoding='utf-8')))
        paths = [base, *sorted(p for p in directory.glob('application*.y*ml') if p!=base)]
        for path in paths:
            settings = dict(baseline)
            if path!=base:settings.update(flatten(yaml.safe_load(path.read_text(encoding='utf-8'))))
            require(default(settings.get('management.endpoint.health.probes.enabled'))=='true', name+': probes disabled or missing')
            for key in ('livenessstate','readinessstate'):
                require(default(settings.get('management.health.'+key+'.enabled', True))=='true', name+': availability indicator disabled')
            require(members(settings.get(group+'liveness.include'))==['livenessState'], name+': liveness must not depend on external systems')
            required = ['readinessState','db'] if component.get('database') else ['readinessState']
            require(members(settings.get(group+'readiness.include'))==required, name+': readiness membership differs from reviewed role')
            if component.get('database'):require(default(settings.get('management.health.db.enabled', True))=='true', name+': SQL health disabled')
            for prefix in ('management.endpoint.health.', group+'liveness.', group+'readiness.'):
                require(default(settings.get(prefix+'show-details', 'never'))=='never', name+': health details could be exposed')
                require(default(settings.get(prefix+'show-components', 'never'))=='never', name+': health components could be exposed')
                for status in ('down','out-of-service'):
                    require(default(settings.get(prefix+'status.http-mapping.'+status,503))=='503', name+': unhealthy probe must not return success')
            for health_group in ('liveness','readiness'):
                require(not settings.get(group+health_group+'.exclude'), name+': health exclusions require review')
            reviewed.append(path.relative_to(root).as_posix())
    deployments = {d['metadata']['name']: d for d in yaml.safe_load_all((root/'deploy/k8s/base/deployments.yaml').read_text()) if d and d.get('kind')=='Deployment'}
    for c in components:
        d = deployments.get(c['name']);require(d is not None, 'Missing JVM Deployment')
        containers = d['spec']['template']['spec']['containers'];require(len(containers)==1, 'Review extra application containers')
        for probe,path in (('startupProbe','readiness'),('readinessProbe','readiness'),('livenessProbe','liveness')):
            require(containers[0].get(probe,{}).get('httpGet',{}).get('path')=='/actuator/health/'+path, 'Kubernetes probe path changed')
    return {'status':'PASS','jvmComponents':len(components),'sqlReadinessComponents':14,'stateOnlyGateway':True,
            'configurationsReviewed':len(reviewed),'files':reviewed,'kubernetesProbePaths':45,
            'runtimeConfigurationInspected':False,'runtimeExecutionVerified':False,'fullA35Complete':False}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--root',type=Path,default=ROOT);args=parser.parse_args()
    try:print(json.dumps(check(args.root),indent=2))
    except (ValueError,OSError,KeyError,TypeError,yaml.YAMLError) as error:raise SystemExit('Readiness configuration rejected: '+str(error))
