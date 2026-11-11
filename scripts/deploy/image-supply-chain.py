#!/usr/bin/env python3
"""Scan a verified local image ID and bind standalone CycloneDX evidence to it."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT=Path(__file__).resolve().parents[2]


def read(path):
    if Path(path).stat().st_size>64*1024*1024:raise ValueError('Evidence exceeds budget')
    return json.loads(Path(path).read_text(encoding='utf-8'))


def file_sha(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as stream:
        while chunk:=stream.read(1024*1024):digest.update(chunk)
    return digest.hexdigest()


def command(args):return subprocess.run(args,check=True,capture_output=True,text=True,timeout=60).stdout.strip()


def inspect(image):return command(['docker','image','inspect',image,'--format','{{.Id}}'])


def validate_sbom(doc):
    if doc.get('bomFormat')!='CycloneDX' or not doc.get('specVersion') or not isinstance(doc.get('components'),list) or not doc['components']:
        raise ValueError('Missing/invalid CycloneDX components')


def scan(image,image_id,directory,commit,runner=subprocess.run):
    if not re.fullmatch('sha256:[0-9a-f]{64}',image_id) or not re.fullmatch('[0-9a-f]{40}',commit):raise ValueError('Invalid image/commit identity')
    if inspect(image)!=image_id:raise ValueError('Image tag no longer matches the build output')
    directory=Path(directory);directory.mkdir(parents=True,exist_ok=True)
    if any((directory/name).exists() for name in ('scan.json','sbom.cdx.json','supply-chain.json')):raise ValueError('Refuse stale image evidence')
    lock_path=ROOT/'scripts/deploy/supply-chain.lock.json';lock=read(lock_path)
    trivy=ROOT/'gitIgnoreOwn/tools/supply-chain/bin/trivy'
    version=json.loads(command([str(trivy),'version','--format','json']))
    if version.get('Version')!=lock['tools']['trivy']['version'].removeprefix('v'):raise ValueError('Unexpected scanner version')
    common=[str(trivy),'image','--cache-dir',str(ROOT/'gitIgnoreOwn/tools/supply-chain/trivy-cache'),'--skip-version-check','--timeout','10m']
    if (ROOT/'.trivyignore').is_file():common+=['--ignorefile',str(ROOT/'.trivyignore')]
    scan_command=common+['--scanners','vuln','--pkg-types','os,library','--severity','HIGH,CRITICAL','--ignore-unfixed','--exit-code','1','--format','json','--output',str(directory/'scan.json'),image_id]
    result=runner(scan_command,timeout=660,check=False)
    sbom_result=runner(common+['--format','cyclonedx','--scanners','vuln','--exit-code','0','--output',str(directory/'sbom.cdx.json'),image_id],timeout=660,check=False)
    if result.returncode not in {0,1} or sbom_result.returncode!=0 or not (directory/'scan.json').is_file():raise ValueError('Scanner/SBOM execution unavailable')
    raw=read(directory/'scan.json');validate_sbom(read(directory/'sbom.cdx.json'))
    os_info=raw.get('Metadata',{}).get('OS',{})
    if os_info.get('Family')=='alpine':
        branches={re.search(r'/alpine/v(\d+\.\d+)/',p['indexSource'])[1] for p in lock['frontendPackages'].values()}
        if len(branches)!=1 or not str(os_info.get('Name','')).startswith(next(iter(branches))+'.'):raise ValueError('Alpine base and locked package branch differ')
    if raw.get('Metadata',{}).get('ImageID')!=image_id or inspect(image)!=image_id:raise ValueError('Scanner image identity mismatch')
    vulnerabilities=[v for row in raw.get('Results',[]) for v in row.get('Vulnerabilities') or []]
    blocking=[v for v in vulnerabilities if v.get('Severity') in {'HIGH','CRITICAL'} and v.get('FixedVersion')]
    if (result.returncode==0 and blocking) or (result.returncode==1 and not blocking):raise ValueError('Scan result and exit code disagree')
    report={'schemaVersion':1,'status':'PASS' if result.returncode==0 else 'BLOCKED_BY_SCAN','sourceCommit':commit,
            'localImageId':image_id,'lockSha256':file_sha(lock_path),'trivyVersion':version['Version'],
            'trivyBinarySha256':file_sha(trivy),'scanSha256':file_sha(directory/'scan.json'),
            'sbomSha256':file_sha(directory/'sbom.cdx.json'),'blockingPackageCveRecords':len(blocking),
            'scanExitCode':result.returncode,'sbomFormat':'CycloneDX','publicationPerformed':False}
    db_metadata=ROOT/'gitIgnoreOwn/tools/supply-chain/trivy-cache/db/metadata.json'
    if db_metadata.is_file():report['vulnerabilityDatabaseMetadata']=read(db_metadata)
    (directory/'supply-chain.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    return result.returncode


def preflight(image,directory,commit):
    directory=Path(directory);report=read(directory/'supply-chain.json')
    if (report.get('status')!='PASS' or report.get('sourceCommit')!=commit
            or report.get('scanExitCode')!=0 or report.get('blockingPackageCveRecords')!=0
            or report.get('localImageId')!=inspect(image)
            or report.get('lockSha256')!=file_sha(ROOT/'scripts/deploy/supply-chain.lock.json')
            or report.get('sbomSha256')!=file_sha(directory/'sbom.cdx.json')
            or report.get('scanSha256')!=file_sha(directory/'scan.json')):raise ValueError('Publication evidence mismatch')
    raw=read(directory/'scan.json')
    if raw.get('Metadata',{}).get('ImageID')!=report['localImageId']:raise ValueError('Publication scan identity mismatch')
    if any(v.get('Severity') in {'HIGH','CRITICAL'} and v.get('FixedVersion') for row in raw.get('Results',[]) for v in row.get('Vulnerabilities') or []):raise ValueError('Publication scan still has blocking records')
    validate_sbom(read(directory/'sbom.cdx.json'))
    return report


def published(image,directory,commit):
    directory=Path(directory);report=preflight(image,directory,commit)
    repository=image.rsplit(':',1)[0]
    digests=json.loads(command(['docker','image','inspect',image,'--format','{{json .RepoDigests}}']))
    refs=[ref for ref in digests or [] if ref.startswith(repository+'@sha256:')]
    if len(refs)!=1 or not re.fullmatch(re.escape(repository)+r'@sha256:[0-9a-f]{64}',refs[0]):raise ValueError('Missing/ambiguous published repository digest')
    result={k:v for k,v in report.items() if k!='publicationPerformed'}
    result.update(registryDigest=refs[0],publicationObserved=True,publicationPerformedByThisTool=False)
    (directory/'publication.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    return refs[0]


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('operation',choices=['scan','preflight','published'])
    parser.add_argument('--image',required=True);parser.add_argument('--image-id');parser.add_argument('--directory',type=Path,required=True);parser.add_argument('--commit',required=True)
    args=parser.parse_args()
    if args.operation=='scan':return scan(args.image,args.image_id or '',args.directory,args.commit)
    if args.operation=='preflight':preflight(args.image,args.directory,args.commit);return 0
    print(published(args.image,args.directory,args.commit));return 0


if __name__=='__main__':
    try:raise SystemExit(main())
    except (ValueError,OSError,KeyError,TypeError,subprocess.SubprocessError):raise SystemExit('Image supply-chain evidence rejected; no publication approval.')
