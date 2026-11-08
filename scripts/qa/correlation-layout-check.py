#!/usr/bin/env python3
"""Detect reintroduced local copies of the shared correlation infrastructure."""
import argparse
from io import BytesIO
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def check(root=ROOT):
    root=Path(root)
    sources=list(root.glob('*/src/main/java/**/*.java'))
    shared=root/'platform-security/src/main/java/org/demo/platformobservability'
    for name in ('CorrelationIdContext','CorrelationIdClientInterceptor'):
        copies={path for path in sources if path.name==name+'.java'}
        if copies!={shared/(name+'.java')}:
            raise ValueError('Only one shared '+name+' implementation is allowed')
    adapters=[]
    for path in sources:
        text=path.read_text(encoding='utf-8')
        if re.search(r'org\.demo\.[a-z]+\.observability\.CorrelationId(?:Context|ClientInterceptor)',text):
            raise ValueError('Legacy correlation reference: '+str(path))
        if path.name=='CorrelationIdFilter.java' and path.parent!=shared:
            if 'extends org.demo.platformobservability.CorrelationIdFilter' not in text or '@Component' not in text:
                raise ValueError('Service filter must delegate to the shared filter: '+str(path))
            if 'SAFE_ID' in text or 'HEADER_NAME' in text or 'MDC_KEY' in text:
                raise ValueError('Service filter duplicates correlation logic: '+str(path))
            adapters.append(path)
    if not adapters:
        raise ValueError('No service correlation adapters found')
    components=json.loads((root/'deploy/components.json').read_text(encoding='utf-8'))['components']
    expected={c['name'] for c in components if c['runtime']=='jvm'}
    actual=[path.relative_to(root).parts[0] for path in adapters]
    if set(actual)!=expected or len(actual)!=len(expected):
        raise ValueError('Every JVM component must register exactly one shared correlation adapter')
    return {'sharedContexts':1,'sharedInterceptors':1,'serviceAdapters':len(adapters)}


def check_jars(root=ROOT):
    root=Path(root)
    components=json.loads((root/'deploy/components.json').read_text(encoding='utf-8'))['components']
    services=[item['name'] for item in components if item['runtime']=='jvm']
    shared={f'org/demo/platformobservability/{name}.class' for name in
            ['CorrelationIdContext','CorrelationIdClientInterceptor','CorrelationIdFilter']}
    for service in services:
        jars=list((root/service/'target').glob('*-SNAPSHOT.jar'))
        if len(jars)!=1:raise ValueError(service+': build one current executable JAR first')
        with zipfile.ZipFile(jars[0]) as archive:
            names=set(archive.namelist())
            legacy=[name for name in names if name.startswith('BOOT-INF/classes/') and
                    name.endswith(('/CorrelationIdContext.class','/CorrelationIdClientInterceptor.class'))]
            if legacy:raise ValueError(service+': stale local correlation classes remain; run a clean build')
            adapter='BOOT-INF/classes/org/demo/'+service.replace('-','')+'/observability/CorrelationIdFilter.class'
            if adapter not in names:raise ValueError(service+': rebuild the application with its correlation adapter')
            libraries=[name for name in names if name.startswith('BOOT-INF/lib/platform-security-') and name.endswith('.jar')]
            if len(libraries)!=1:raise ValueError(service+': missing shared infrastructure library')
            with zipfile.ZipFile(BytesIO(archive.read(libraries[0]))) as library:
                if not shared<=set(library.namelist()):raise ValueError(service+': rebuild the shared library and application')
    return {'executableJars':len(services),'staleLocalClasses':0}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jars',action='store_true',help='Also verify all freshly packaged executable JARs')
    args=parser.parse_args()
    try:
        result=check()
        if args.jars:result.update(check_jars())
    except (ValueError,OSError,zipfile.BadZipFile) as error:raise SystemExit('Correlation layout failed: '+str(error))
    print('PASS correlation layout: one shared context/interceptor and '+str(result['serviceAdapters'])+' service adapters')
    if args.jars:print('PASS packaged correlation: '+str(result['executableJars'])+' executable JARs; no stale local classes')
