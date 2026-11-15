#!/usr/bin/env python3
"""Offline checks for reviewed direct action/base/tool pins and SBOM publication bindings."""
import json,re
from pathlib import Path
import yaml
ROOT=Path(__file__).resolve().parents[2]


def check(root=ROOT):
    root=Path(root);lock=json.loads((root/'scripts/deploy/supply-chain.lock.json').read_text())
    def require(value,message):
        if not value:raise ValueError(message)
    require(lock.get('schemaVersion')==1,'Invalid supply-chain lock')
    require(set(lock['images'])=={'jvmBuild','jvmRuntime','frontend','buildkit'},'Missing critical base pin')
    for entry in lock['images'].values():
        require(re.fullmatch(r'.+@sha256:[0-9a-f]{64}',entry['reference']) and entry['reference'].endswith('@'+entry['digest']),'Unpinned image')
    sources=list((root/'.github/workflows').glob('*.yml'))+list((root/'.github/actions').rglob('action.yml'))
    seen=set()
    def visit(value):
        if isinstance(value,dict):
            if 'uses' in value:
                use=value['uses']
                if not use.startswith('./'):
                    match=re.fullmatch(r'([^@]+)@([0-9a-f]{40})',use)
                    require(match is not None and match[1] in lock['actions'] and match[2]==lock['actions'][match[1]]['sha'],'Floating/unreviewed direct action')
                    seen.add(match[1])
            for child in value.values():visit(child)
        elif isinstance(value,list):
            for child in value:visit(child)
    for path in sources:visit(yaml.safe_load(path.read_text(encoding='utf-8')))
    require(seen==set(lock['actions']),'Stale/unused direct action lock')
    frontend='\n'.join(l for l in (root/'deploy/docker/frontend.Dockerfile').read_text(encoding='utf-8').splitlines() if not l.lstrip().startswith('#'))
    service='\n'.join(l for l in (root/'deploy/docker/service.Dockerfile').read_text(encoding='utf-8').splitlines() if not l.lstrip().startswith('#'))
    require(re.findall(r'^FROM (\S+)',frontend,re.M)==[lock['images']['frontend']['reference']],'Frontend base drift')
    require(re.findall(r'^FROM (\S+)',service,re.M)==[lock['images']['jvmBuild']['reference'],lock['images']['jvmRuntime']['reference']],'JVM base drift')
    require('apk upgrade' not in frontend and 'apt-get upgrade' not in service and 'apt-get install' not in service,'Mutable package upgrade/install')
    require('RUN --network=none apk add --no-network' in frontend and '--allow-untrusted' not in frontend,'APK signature/network protection missing')
    require(set(lock['frontendPackages'])=={'brotli-libs','curl','libcurl','libxslt'},'Missing reviewed frontend security patches')
    for name,entry in lock['frontendPackages'].items():
        require(re.fullmatch('[0-9a-f]{64}',entry['sha256']),'Invalid APK SHA256')
        require(re.fullmatch(r'https://dl-cdn.alpinelinux.org/alpine/v\d+\.\d+/main/x86_64/APKINDEX.tar.gz',entry['indexSource']),'Unreviewed APK index')
        require(re.fullmatch(r'[0-9A-Za-z._+-]+',entry['version']) and entry['url']==entry['indexSource'].rsplit('/',1)[0]+'/'+name+'-'+entry['version']+'.apk','Unreviewed APK origin/version')
        require('ADD --checksum=sha256:'+entry['sha256']+' '+entry['url']+' /tmp/a44-'+name+'.apk' in frontend,'APK source/checksum drift')
    require('DejaVuSans.ttf' in service and 'DejaVuSans-Bold.ttf' in service,'PDF font assertion missing')
    require(set(lock['tools'])=={'trivy','cosign'},'Missing pinned supply-chain tool')
    for name,entry in lock['tools'].items():
        require(name in {'trivy','cosign'} and re.fullmatch(r'v\d+\.\d+\.\d+',entry['version']) and re.fullmatch('[0-9a-f]{64}',entry['sha256']),'Invalid locked tool')
    wrapper=dict(line.split('=',1) for line in (root/'.mvn/wrapper/maven-wrapper.properties').read_text().splitlines() if '=' in line)
    require(wrapper.get('distributionUrl')==lock['mavenWrapper']['url'] and wrapper.get('distributionSha256Sum')==lock['mavenWrapper']['sha256'],'Wrapper distribution is not checksum-locked')
    ci=yaml.safe_load((root/'.github/workflows/ci.yml').read_text())
    for job in ['pr-images','build-images']:
        setup=next(s for s in ci['jobs'][job]['steps'] if s.get('uses','').startswith('docker/setup-buildx-action@'))
        require(setup['with']['driver-opts']=='image='+lock['images']['buildkit']['reference'],'BuildKit driver is floating')
    updates=yaml.safe_load((root/'.github/workflows/image-input-updates.yml').read_text())
    require(updates.get('permissions')=={'contents':'read'},'Update proposal has write permissions')
    for job in updates['jobs'].values():
        require(job.get('permissions',updates['permissions'])=={'contents':'read'},'Update job overrides read-only permissions')
        for step in job['steps']:
            if step.get('uses','').startswith('actions/checkout@'):
                require(step.get('with',{}).get('persist-credentials') is False,'Update checkout persists credentials')
    require('secrets.' not in (root/'.github/workflows/image-input-updates.yml').read_text(),'Update proposal accesses secrets')
    runs='\n'.join(s.get('run','') for j in updates['jobs'].values() for s in j['steps'])
    require('refresh-supply-chain-lock.py --output' in runs and not any(v in runs for v in ('git push','gh pr','docker push','--apply')),'Updates must remain review-only candidates')
    return {'status':'PASS','directActions':len(seen),'baseImages':len(lock['images']),'lockedTools':len(lock['tools']),
            'frontendPackagePins':len(lock['frontendPackages']),'wrapperChecksum':True,'automaticMerge':False,
            'transitiveActionsFullyAudited':False,'published':False}


if __name__=='__main__':
    try:print(json.dumps(check(),indent=2))
    except (ValueError,KeyError,TypeError,StopIteration):raise SystemExit('Supply-chain pin review failed.')
