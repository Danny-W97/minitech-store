#!/usr/bin/env python3
"""A44: enforce the reviewed PR/image publication boundary from public workflow sources."""
import json
from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parents[2]


def require(value, message):
    if not value:
        raise ValueError(message)


def check(root=ROOT):
    root = Path(root)
    workflow = yaml.safe_load((root / '.github/workflows/ci.yml').read_text(encoding='utf-8'))
    action = yaml.safe_load((root / '.github/actions/build-scan-image/action.yml').read_text(encoding='utf-8'))
    jobs = workflow['jobs']
    triggers = workflow.get('on', workflow.get(True, {}))  # PyYAML YAML 1.1 boolean key.
    require('pull_request' in triggers and not {'pull_request_target','workflow_run'} & triggers.keys(), 'Untrusted privileged trigger')
    require(not triggers['pull_request'].get('paths-ignore') and not triggers['pull_request'].get('paths'), 'Required PR image gate must also run for docs-only changes')
    require(workflow.get('permissions') == {'contents':'read'}, 'Workflow permissions must default to read-only')
    for name in ('pr-image-plan','pr-images','pr-image-gate'):
        require(jobs[name].get('permissions') == {'contents':'read'}, 'PR job permissions expanded')
        require('pull_request' in jobs[name].get('if',''), 'PR boundary missing')
        for step in jobs[name]['steps']:
            require('secrets.' not in json.dumps(step) and not step.get('continue-on-error'), 'PR step uses credentials or ignores failure')
            require(not step.get('uses','').startswith(('docker/login-action@','sigstore/')), 'PR cannot log in or sign')
    plan = jobs['pr-image-plan']
    checkout = next(s for s in plan['steps'] if s.get('uses','').startswith('actions/checkout@'))
    require(checkout['with'] == {'fetch-depth':0,'persist-credentials':False}, 'Planner needs merge history without persisted credentials')
    require(any('scripts/deploy/image-plan.py' in s.get('run','') for s in plan['steps']), 'Missing local Git image planner')
    pr = jobs['pr-images']
    require(next(s for s in pr['steps'] if s.get('uses','').startswith('actions/checkout@'))['with']=={'persist-credentials':False}, 'PR must build the default tested merge without persisted credentials')
    require(pr['needs']=='pr-image-plan' and "outputs.has_images == 'true'" in pr['if'], 'PR matrix/empty-plan guard changed')
    require(pr['strategy']['matrix']=='${{ fromJSON(needs.pr-image-plan.outputs.matrix) }}', 'PR builds the wrong matrix')
    require(1 <= pr['strategy']['max-parallel'] <= 4 and pr['strategy']['fail-fast'] is False, 'Unbounded PR build concurrency')
    invoke = next(s for s in pr['steps'] if s.get('uses')=='./.github/actions/build-scan-image')
    require(invoke['with']['export-cache']=='false', 'PR must not export the trusted image cache')
    require('always()' in jobs['pr-image-gate']['if'] and set(jobs['pr-image-gate']['needs'])=={'pr-image-plan','pr-images'}, 'Missing stable PR gate')
    gate = '\n'.join(s.get('run','') for s in jobs['pr-image-gate']['steps'])
    for fragment in ('test "$PLAN_RESULT" = success','test "$BUILD_RESULT" = success','test "$BUILD_RESULT" = skipped','test "$HAS_IMAGES" = false'):
        require(fragment in gate, 'Gate can silently accept failed/missing scans')

    require(action['runs']['using']=='composite', 'Build/scan implementation is not shared')
    steps = action['runs']['steps']
    builds = [s for s in steps if s.get('uses','').startswith('docker/build-push-action@')]
    require(len(builds)==2, 'Missing JVM/frontend build')
    for step in builds:
        options=step['with']
        require(options['push'] is False and options['load'] is True, 'Build must be local before scan')
        require(options['tags']=='${{ inputs.image }}', 'Unexpected build image identity')
        require(options.get('platforms')=='linux/amd64', 'Cache architecture missing')
        require('scope=a44-${{ inputs.service }}-linux-amd64' in options['cache-from'], 'Cross-image cache collision')
        require("inputs.export-cache == 'true'" in options['cache-to'] and 'inputs.service' in options['cache-to'], 'Cache write boundary missing')
        require(not {'secrets','secret-files','ssh'} & options.keys(), 'Credentials passed into image build')
        require(step.get('env',{}).get('DOCKER_BUILD_RECORD_UPLOAD')=='false', 'Raw build records must not be uploaded by default')
        require(not step.get('continue-on-error'), 'Image build failure ignored')
    scan=next(s for s in steps if s.get('id')=='scan')
    require('scripts/deploy/image-supply-chain.py scan' in scan.get('run','') and scan['env']['A44_IMAGE']=='${{ inputs.image }}' and 'outputs.imageid' in scan['env']['A44_IMAGE_ID'], 'Scan must cover the built image ID')
    require(any(s.get('run')=='python3 scripts/deploy/install-supply-chain-tools.py trivy' for s in steps), 'Missing checksum-verified scanner')
    require(not scan.get('continue-on-error'), 'Scan failure ignored')
    require(not any('docker push' in s.get('run','') or s.get('uses','').startswith(('docker/login-action@','sigstore/')) for s in steps), 'Shared PR action can publish/sign')
    upload=next(s for s in steps if s.get('uses','').startswith('actions/upload-artifact@'))
    paths=upload['with']['path'].splitlines()
    require({p.rsplit('/',1)[-1] for p in paths}=={'result.json','scan.json','sbom.cdx.json','supply-chain.json'} and len(paths)==4 and upload['with']['retention-days']==1, 'Unbounded/raw evidence upload')

    release=jobs['build-images']
    require(release['needs']=='verify' and "github.ref == 'refs/heads/main'" in release['if'] and 'workflow_dispatch' in release['if'], 'Release boundary changed')
    require(release['strategy']['matrix']=='${{ fromJSON(needs.verify.outputs.image_matrix) }}', 'Full release inventory was reduced')
    rsteps=release['steps']
    built=next(i for i,s in enumerate(rsteps) if s.get('uses')=='./.github/actions/build-scan-image')
    pushed=next(i for i,s in enumerate(rsteps) if 'docker push' in s.get('run',''))
    signed=next(i for i,s in enumerate(rsteps) if 'cosign sign' in s.get('run',''))
    require(built < pushed < signed, 'Publication must follow successful build/scan')
    require(rsteps[built]['with']['image']==rsteps[pushed]['env']['IMAGE_REF'] and 'image-supply-chain.py preflight' in rsteps[pushed]['run'] and 'image-supply-chain.py published' in rsteps[pushed]['run'], 'Publication must validate scan evidence and capture registry digest')
    require('cosign sign --yes "$PUBLISHED_IMAGE"' in rsteps[signed]['run'], 'Signature must target the published digest')
    require(any('cosign attest --yes --type cyclonedx' in s.get('run','') and '"$PUBLISHED_IMAGE"' in s['run'] for s in rsteps), 'Missing digest-bound SBOM attestation')
    require(rsteps[built]['with']['export-cache']=='true', 'Trusted per-image cache export missing')
    for step in rsteps[pushed:signed+1]:
        require(not step.get('continue-on-error') and 'always()' not in step.get('if',''), 'Publication can bypass a failure')
    deploy=jobs['deploy-kubernetes']
    require(deploy['needs']=='build-images' and 'inputs.deploy == true' in deploy['if'] and deploy['environment']=='production', 'Automatic deployment introduced')
    require(any('audit_readiness.py --release' in s.get('run','') for s in deploy['steps']), 'P1 production guard removed')
    return {'status':'PASS','stage':'A44.1-A44.3','prPublication':False,'prCredentialPersistence':False,
            'perImageCache':True,'scanBeforePublication':True,'fullReleaseMatrixPreserved':True,
            'githubRunObserved':False,'branchProtectionInspected':False}


if __name__=='__main__':
    try:
        print(json.dumps(check(),indent=2))
    except (ValueError,KeyError,TypeError,StopIteration):
        raise SystemExit('CI image boundary rejected; review workflow and shared action.')
