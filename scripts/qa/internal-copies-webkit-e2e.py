#!/usr/bin/env python3
"""Use cached browser libraries in an isolated container, copying public sources only."""
import argparse,json,os,secrets,shutil,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
IMAGE='mcr.microsoft.com/playwright:v1.64.0-noble'
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--ai-delivery',action='store_true');args=parser.parse_args()
    directory=ROOT/'tmp'/('internal-copies-webkit-'+secrets.token_hex(4));workspace=directory/'workspace';workspace.mkdir(parents=True)
    # No .git, runtime, SOPS, keys, existing logs or user configuration is copied.
    allowed={'.js','.mjs','.cjs','.ts','.py','.sh','.html','.css','.svg'}
    for base in ('frontend','e2e','scripts/control-center','scripts/security','scripts/deploy'):
        for source in (ROOT/base).rglob('*'):
            if source.is_symlink() or not source.is_file() or source.suffix not in allowed or '__pycache__' in source.parts:continue
            target=workspace/source.relative_to(ROOT);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
    for name in ('package.json','playwright.config.ts','playwright.control-center.config.ts','scripts/qa/frontend-server.mjs','scripts/qa/fixtures/control_center_browser.py','scripts/control-center/data/error_database.json','deploy/components.json'):
        target=workspace/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/name,target)
    name='a43-38-webkit-'+secrets.token_hex(5)
    script='PLAYWRIGHT_FRONTEND_PORT=18773 PLAYWRIGHT_OUTPUT_ROOT=out/store node node_modules/@playwright/test/cli.js test e2e/tests/cart/cart-session.spec.ts --project=webkit --workers=1 && CC_TEST_BROWSER=webkit CC_TEST_OUTPUT=out/cc node node_modules/@playwright/test/cli.js test --config playwright.control-center.config.ts --grep "ending one panel|session expiry cancels a pending list"'
    if args.ai_delivery:script='CC_TEST_BROWSER=webkit CC_TEST_OUTPUT=out/cc node node_modules/@playwright/test/cli.js test --config playwright.control-center.config.ts --grep @ai-delivery-review'
    resolver="const {createRequire}=require('module'),p=require('path'),fs=require('fs');function pkg(r,name){let d=p.dirname(r.resolve(name));for(let i=0;i<10;i++,d=p.dirname(d)){const f=p.join(d,'package.json');if(fs.existsSync(f)&&JSON.parse(fs.readFileSync(f)).name===name)return d;}throw Error('Package root unavailable');}const r=createRequire(process.cwd()+'/package.json');const t=createRequire(pkg(r,'@playwright/test')+'/package.json');const w=createRequire(pkg(t,'playwright')+'/package.json');const a=createRequire(pkg(r,'@axe-core/playwright')+'/package.json');const out={};for(const [name,loader] of [['@playwright/test',r],['playwright',t],['playwright-core',w],['@axe-core/playwright',r],['axe-core',a]])out[name]=pkg(loader,name);process.stdout.write(JSON.stringify(out))"
    packages=json.loads(subprocess.check_output(['node','-e',resolver],cwd=ROOT,text=True))
    command=['docker','run','--rm','--pull','never','--name',name,'--label','minitech.internal-copies='+name,'--network','none','--shm-size','1g','-v',str(workspace)+':/workspace']
    for package,source in packages.items():command.extend(['-v',source+':/workspace/node_modules/'+package+':ro'])
    command.extend(['-v',str(Path.home()/'.cache/ms-playwright')+':/qa-browsers:ro','-e','PLAYWRIGHT_BROWSERS_PATH=/qa-browsers','-w','/workspace',IMAGE,'bash','-c',script])
    report={'stage':'A43.38','status':'FAILED','image':IMAGE,'hostDependenciesChanged':False,'network':'none','sourceCopy':'public code/configuration allowlist only'}
    if args.ai_delivery:report['stage']='A43.39'
    try:
        with (directory/'container.log').open('w') as output:result=subprocess.run(command,stdout=output,stderr=subprocess.STDOUT,timeout=900)
        if result.returncode:raise RuntimeError('Isolated WebKit fixture failed; inspect its own container.log')
        report.update(status='PASS',storeTests=7,controlCenterTests=2)
        if args.ai_delivery:report['storeTests']=0
    finally:
        subprocess.run(['docker','rm','-f','-v',name],capture_output=True,timeout=30)
        (directory/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'status':report['status'],'report':str(directory/'report.json')}))
if __name__=='__main__':main()
