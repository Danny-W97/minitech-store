#!/usr/bin/env python3
"""Keep resilience implementations shared and runtime dependencies explicitly opt-in."""
import argparse
from io import BytesIO
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[2]
ACTIVE={'api-gateway','order-service','payment-service','customer-service'}
ARTIFACTS={'resilience4j-circuitbreaker','resilience4j-retry','resilience4j-bulkhead','resilience4j-micrometer'}
NS={'m':'http://maven.apache.org/POM/4.0.0'}


def check(root=ROOT):
    root=Path(root);shared=root/'platform-security/src/main/java/org/demo/platformhttp'
    for name in ('ResilientHttpExecutor','ResilienceConfiguration'):
        path=shared/(name+'.java')
        if not path.is_file():raise ValueError('Missing shared '+name)
        if '@Configuration' in path.read_text() or '@Component' in path.read_text():
            raise ValueError('Shared resilience must not activate itself in every service')
    for filename,base,annotation in [('ResilientHttpExecutor.java','ResilientHttpExecutor','@Component'),
                                     ('ResilienceConfig.java','ResilienceConfiguration','@Configuration')]:
        adapters=[]
        for path in root.glob('*/src/main/java/**/'+filename):
            if path.parent==shared:continue
            text=path.read_text();module=path.relative_to(root).parts[0]
            if 'extends org.demo.platformhttp.'+base not in text or annotation not in text:
                raise ValueError('Service resilience must delegate: '+str(path))
            if '@Bean' in text or ' execute(' in text or 'decorateSupplier' in text:
                raise ValueError('Service adapter duplicates resilience policy: '+str(path))
            adapters.append(module)
        if len(adapters)!=len(ACTIVE) or set(adapters)!=ACTIVE:
            raise ValueError('Resilience must remain opt-in for the four existing services')
    pom=ET.parse(root/'platform-security/pom.xml').getroot()
    dependencies=[node for node in pom.findall('m:dependencies/m:dependency',NS)
                  if node.findtext('m:groupId',namespaces=NS)=='io.github.resilience4j']
    if {node.findtext('m:artifactId',namespaces=NS) for node in dependencies}!=ARTIFACTS:
        raise ValueError('Shared resilience dependency inventory changed')
    if any(node.findtext('m:optional',namespaces=NS)!='true' for node in dependencies):
        raise ValueError('Shared resilience dependencies must be optional')
    return {'serviceAdapters':len(ACTIVE),'sharedImplementations':2,'optionalDependencies':len(dependencies)}


def check_jars(root=ROOT):
    root=Path(root);components=json.loads((root/'deploy/components.json').read_text())['components']
    services=[item['name'] for item in components if item['runtime']=='jvm']
    shared={f'org/demo/platformhttp/{name}.class' for name in ('ResilientHttpExecutor','ResilienceConfiguration')}
    for service in services:
        jars=list((root/service/'target').glob('*-SNAPSHOT.jar'))
        if len(jars)!=1:raise ValueError(service+': build one current executable JAR first')
        with zipfile.ZipFile(jars[0]) as archive:
            names=set(archive.namelist())
            present=any(name.startswith('BOOT-INF/lib/resilience4j-') for name in names)
            if present!=(service in ACTIVE):raise ValueError(service+': unexpected runtime resilience dependencies')
            if any(name.startswith('BOOT-INF/classes/') and name.endswith('/ResilientHttpExecutor$ResilienceRejectedException.class') for name in names):
                raise ValueError(service+': stale local exception class; run a clean build')
            libraries=[name for name in names if name.startswith('BOOT-INF/lib/platform-security-') and name.endswith('.jar')]
            if len(libraries)!=1:raise ValueError(service+': missing shared library')
            with zipfile.ZipFile(BytesIO(archive.read(libraries[0]))) as library:
                if not shared<=set(library.namelist()):raise ValueError(service+': rebuild the shared library')
    return {'executableJars':len(services),'resilienceEnabled':len(ACTIVE),'withoutExtraRuntimeDependencies':len(services)-len(ACTIVE)}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--jars',action='store_true');args=parser.parse_args()
    try:
        result=check()
        if args.jars:result.update(check_jars())
    except (ValueError,OSError,ET.ParseError,zipfile.BadZipFile) as error:raise SystemExit('Resilience layout failed: '+str(error))
    print('PASS resilience layout: four adapters, two shared implementations, optional dependencies')
    if args.jars:print('PASS packaged resilience: '+str(result['executableJars'])+' JARs; '+str(result['withoutExtraRuntimeDependencies'])+' apps without extra runtime dependencies')
