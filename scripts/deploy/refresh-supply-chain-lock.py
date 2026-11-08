#!/usr/bin/env python3
"""Read-only upstream refresh: emit a reviewed lock candidate and patch, never apply or merge."""
import argparse
import concurrent.futures
import difflib
import hashlib
import io
import json
from datetime import datetime,timezone
from pathlib import Path
import re
import tarfile
import urllib.request

ROOT=Path(__file__).resolve().parents[2]


def read(url,headers=None):
    request=urllib.request.Request(url,headers={'User-Agent':'MiniOrderPlatform-supply-chain-refresh',**(headers or {})})
    with urllib.request.urlopen(request,timeout=45) as response:
        raw=response.read(5*1024*1024+1)
        if len(raw)>5*1024*1024:raise ValueError('Upstream metadata exceeds budget')
        return raw,dict(response.headers)


def refresh(current):
    candidate=json.loads(json.dumps(current))
    candidate['verifiedAt']=datetime.now(timezone.utc).date().isoformat()
    def action(pair):
        name,entry=pair
        if not re.fullmatch(r'[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+',name) or not re.fullmatch(r'v[0-9.]+',entry['tag']):raise ValueError('Invalid action reference')
        obj=json.loads(read('https://api.github.com/repos/'+name+'/git/ref/tags/'+entry['tag'])[0])['object']
        for _ in range(3):
            if obj['type']=='commit':break
            if obj['type']!='tag':raise ValueError('Unexpected Git object')
            obj=json.loads(read('https://api.github.com/repos/'+name+'/git/tags/'+obj['sha'])[0])['object']
        if obj['type']!='commit' or not re.fullmatch('[0-9a-f]{40}',obj['sha']):raise ValueError('Unresolved action SHA')
        return name,{**entry,'sha':obj['sha'],'source':'https://github.com/'+name+'/tree/'+obj['sha']}
    def image(pair):
        name,entry=pair;reference=entry['reference'].split('@')[0];repo,tag=reference.rsplit(':',1)
        if '/' not in repo:repo='library/'+repo
        if not re.fullmatch('[a-z0-9./_-]+',repo) or not re.fullmatch('[a-zA-Z0-9._-]+',tag):raise ValueError('Invalid public image reference')
        token=json.loads(read('https://auth.docker.io/token?service=registry.docker.io&scope=repository:'+repo+':pull')[0])['token']
        url='https://registry-1.docker.io/v2/'+repo+'/manifests/'+tag
        raw,headers=read(url,{'Authorization':'Bearer '+token,'Accept':'application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json'})
        digest='sha256:'+hashlib.sha256(raw).hexdigest()
        if headers.get('Docker-Content-Digest',digest)!=digest:raise ValueError('Image manifest digest mismatch')
        return name,{**entry,'reference':reference+'@'+digest,'digest':digest,'source':url}
    with concurrent.futures.ThreadPoolExecutor(4) as pool:
        candidate['actions']=dict(pool.map(action,current['actions'].items()))
        candidate['images']=dict(pool.map(image,current['images'].items()))
    for name,repo in [('trivy','aquasecurity/trivy'),('cosign','sigstore/cosign')]:
        release=json.loads(read('https://api.github.com/repos/'+repo+'/releases/latest')[0]);version=release['tag_name']
        if release.get('prerelease') or release.get('draft') or not re.fullmatch(r'v\d+\.\d+\.\d+',version):raise ValueError('Non-stable tool release')
        assets={a['name']:a['browser_download_url'] for a in release['assets']}
        archive='trivy_'+version[1:]+'_Linux-64bit.tar.gz' if name=='trivy' else 'cosign-linux-amd64'
        checks='trivy_'+version[1:]+'_checksums.txt' if name=='trivy' else 'cosign_checksums.txt'
        checksum=next(line.split()[0] for line in read(assets[checks])[0].decode().splitlines() if line.split()[-1].lstrip('*')==archive)
        if not re.fullmatch('[0-9a-f]{64}',checksum):raise ValueError('Invalid published SHA256')
        candidate['tools'][name]={**current['tools'][name],'version':version,'url':assets[archive],'sha256':checksum,'checksumSource':assets[checks]}
    index_sources={entry['indexSource'] for entry in current.get('frontendPackages',{}).values()}
    if len(index_sources)!=1:raise ValueError('Expected one reviewed Alpine package branch')
    index_url=next(iter(index_sources))
    if not re.fullmatch(r'https://dl-cdn.alpinelinux.org/alpine/v\d+\.\d+/main/x86_64/APKINDEX.tar.gz',index_url):raise ValueError('Unreviewed Alpine index origin')
    with tarfile.open(fileobj=io.BytesIO(read(index_url)[0]),mode='r:gz') as bundle:
        member=bundle.getmember('APKINDEX')
        if not member.isfile() or member.size>20*1024*1024:raise ValueError('Invalid Alpine index')
        index=bundle.extractfile(member).read().decode()
    found={}
    for block in index.split('\n\n'):
        fields=dict(line.split(':',1) for line in block.splitlines() if ':' in line)
        name=fields.get('P')
        if name in current['frontendPackages']:
            version=fields['V']
            if not re.fullmatch('[0-9A-Za-z._+-]+',version):raise ValueError('Invalid APK version')
            url=index_url.rsplit('/',1)[0]+'/'+name+'-'+version+'.apk'
            with urllib.request.urlopen(url,timeout=60) as source:
                digest=hashlib.sha256();size=0
                while chunk:=source.read(1024*1024):
                    size+=len(chunk)
                    if size>32*1024*1024:raise ValueError('APK exceeds download budget')
                    digest.update(chunk)
            found[name]={'version':version,'url':url,'sha256':digest.hexdigest(),'indexSource':index_url}
    if set(found)!=set(current['frontendPackages']):raise ValueError('Missing reviewed Alpine package')
    candidate['frontendPackages']=found
    return candidate


def patch(root,current,candidate):
    replacements={entry['sha']:candidate['actions'][name]['sha'] for name,entry in current['actions'].items()}
    replacements.update({entry['reference']:candidate['images'][name]['reference'] for name,entry in current['images'].items()})
    for name,entry in current.get('frontendPackages',{}).items():
        replacements[entry['url']]=candidate['frontendPackages'][name]['url']
        replacements['sha256:'+entry['sha256']]='sha256:'+candidate['frontendPackages'][name]['sha256']
    changed={}
    for base in ('.github/workflows','.github/actions','deploy/docker'):
        for path in sorted((Path(root)/base).rglob('*')):
            if path.is_symlink():raise ValueError('Unexpected input symlink')
            if not path.is_file() or (path.suffix not in {'.yml','.yaml','.Dockerfile'} and path.name!='Dockerfile'):continue
            before=path.read_text(encoding='utf-8');after=before
            for old,new in replacements.items():after=after.replace(old,new)
            if before!=after:changed[path.relative_to(root).as_posix()]=(before,after)
    lock='scripts/deploy/supply-chain.lock.json'
    before=(Path(root)/lock).read_text(encoding='utf-8');after=json.dumps(candidate,indent=2)+'\n'
    if before!=after:changed[lock]=(before,after)
    return ''.join(''.join(difflib.unified_diff(a.splitlines(True),b.splitlines(True),fromfile='a/'+name,tofile='b/'+name)) for name,(a,b) in sorted(changed.items()))


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    current=json.loads((ROOT/'scripts/deploy/supply-chain.lock.json').read_text());candidate=refresh(current)
    args.output.mkdir(parents=True,exist_ok=False)
    (args.output/'supply-chain.lock.json').write_text(json.dumps(candidate,indent=2)+'\n')
    (args.output/'proposed.patch').write_text(patch(ROOT,current,candidate))
    print(json.dumps({'status':'CANDIDATE_ONLY','repositoryChanged':False,'published':False,'output':str(args.output)}))


if __name__=='__main__':main()
