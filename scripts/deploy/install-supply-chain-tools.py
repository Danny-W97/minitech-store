#!/usr/bin/env python3
"""Install exact official release bytes after SHA256 verification; never uses latest."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import re
import shutil
import tarfile
import urllib.request

ROOT=Path(__file__).resolve().parents[2]


def sha(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as stream:
        while chunk:=stream.read(1024*1024):digest.update(chunk)
    return digest.hexdigest()


def install(name,entry,destination):
    require=lambda value: value or (_ for _ in ()).throw(ValueError('Invalid locked tool'))
    repo={'trivy':'aquasecurity/trivy','cosign':'sigstore/cosign'}[name]
    require(re.fullmatch(r'v\d+\.\d+\.\d+',entry['version']))
    require(re.fullmatch('[0-9a-f]{64}',entry['sha256']))
    filename='trivy_'+entry['version'][1:]+'_Linux-64bit.tar.gz' if name=='trivy' else 'cosign-linux-amd64'
    require(entry['url']=='https://github.com/'+repo+'/releases/download/'+entry['version']+'/'+filename)
    destination=Path(destination);destination.mkdir(parents=True,exist_ok=True)
    archive=destination.parent/(name+'-'+entry['version']+'.archive')
    temporary=archive.with_suffix('.download')
    if not archive.is_file() or sha(archive)!=entry['sha256']:
        try:
            request=urllib.request.Request(entry['url'],headers={'User-Agent':'MiniOrderPlatform-locked-tools'})
            with urllib.request.urlopen(request,timeout=60) as source,temporary.open('wb') as output:
                count=0
                while chunk:=source.read(1024*1024):
                    count+=len(chunk)
                    if count>256*1024*1024:raise ValueError('Tool archive exceeds budget')
                    output.write(chunk)
            if sha(temporary)!=entry['sha256']:raise ValueError('Tool SHA256 mismatch')
            temporary.replace(archive)
        finally:temporary.unlink(missing_ok=True)
    target=destination/name;staging=destination/(name+'.verified')
    try:
        if entry.get('member'):
            if entry['member']!=name:raise ValueError('Invalid executable member')
            with tarfile.open(archive,'r:gz') as bundle:
                member=bundle.getmember(name)
                if not member.isfile() or member.size>512*1024*1024:raise ValueError('Invalid executable member')
                with bundle.extractfile(member) as source,staging.open('wb') as output:shutil.copyfileobj(source,output,1024*1024)
        else:
            with archive.open('rb') as source,staging.open('wb') as output:shutil.copyfileobj(source,output,1024*1024)
        staging.chmod(0o755);staging.replace(target)
    finally:staging.unlink(missing_ok=True)
    return {'tool':name,'version':entry['version'],'archiveSha256':entry['sha256'],'binarySha256':sha(target)}


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('tool',choices=['trivy','cosign']);args=parser.parse_args()
    if platform.system()!='Linux' or platform.machine() not in {'x86_64','amd64'}:parser.exit(1,'Locked tools require Linux amd64.\n')
    lock=json.loads((ROOT/'scripts/deploy/supply-chain.lock.json').read_text())
    result=install(args.tool,lock['tools'][args.tool],ROOT/'gitIgnoreOwn/tools/supply-chain/bin')
    print(json.dumps(result))


if __name__=='__main__':main()
