#!/usr/bin/env python3
"""A43.38: local-driver rotation only in a disposable container owned by this test."""
import json,secrets,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def run(*args):
    result=subprocess.run(args,capture_output=True,text=True,timeout=30)
    if result.returncode:raise RuntimeError('Rotation fixture command failed: '+args[0])
    return result.stdout.strip()
def main():
    directory=ROOT/'tmp'/('internal-log-rotation-'+secrets.token_hex(4));directory.mkdir(parents=True)
    container=None;report={'stage':'A43.38','status':'FAILED','realUserDataAccessed':False,'retentionActivated':False}
    try:
        script='i=0\npayload=$(printf "%01000d" 0)\nwhile [ "$i" -lt 3000 ]; do printf "FIXTURE_%s %s\\n" "$i" "$payload"; i=$((i+1)); done\nprintf "ROTATION_END\\n"\nsleep 86400'
        container=run('docker','run','-d','--network','none','--label','minitech.internal-copies='+directory.name,'--log-driver','local','--log-opt','max-size=16k','--log-opt','max-file=2','--log-opt','compress=false','--entrypoint','sh','redis:7-alpine','-c',script)
        for _ in range(60):
            output=run('docker','logs','--tail','2000',container)
            if 'ROTATION_END' in output:break
            time.sleep(.2)
        else:raise AssertionError('Fixture output incomplete')
        configuration=json.loads(run('docker','inspect','--format','{{json .HostConfig.LogConfig}}',container))
        assert configuration['Type']=='local' and configuration['Config']['max-file']=='2'
        assert 'FIXTURE_0 ' not in output and 'FIXTURE_2999 ' in output and len(output)<200000
        report.update(status='PASS',driver='local',maxSize='16k',maxFiles=2,oldOutputRotated=True,lastOutputRetained=True,hostConfigurationChanged=False)
    finally:
        if container:run('docker','rm','-f','-v',container)
        report['cleanupComplete']=True;(directory/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'status':report['status'],'report':str(directory/'report.json')}))
if __name__=='__main__':main()
