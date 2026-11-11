#!/usr/bin/env python3
"""Keep all Maven modules on the shared build parent, without coupling domains."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
BOOT_MANAGED = {('org.postgresql', 'postgresql'), *[
    ('org.testcontainers', artifact) for artifact in
    ('testcontainers', 'testcontainers-junit-jupiter', 'testcontainers-postgresql')]}


def value(node, path):
    if node is None:
        return ''
    return node.findtext('/'.join('m:' + part for part in path.split('/')), default='', namespaces=NS).strip()


def coordinate(node):
    return value(node, 'groupId'), value(node, 'artifactId')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check(root=ROOT):
    root = Path(root).resolve()
    parent = ET.parse(root / 'pom.xml').getroot()
    require(value(parent, 'packaging') == 'pom', 'Root must be a POM parent')
    require(coordinate(parent.find('m:parent', NS)) == ('org.springframework.boot', 'spring-boot-starter-parent'),
            'Only the root inherits the Spring Boot parent')
    shared = {node.tag.split('}')[-1] for node in parent.findall('m:properties/*', NS)}
    require({'java.version', 'tomcat.version', 'netty.version', 'postgresql.version', 'springdoc.version',
             'resilience4j.version', 'testcontainers.version'} <= shared, 'Missing shared version properties')
    require(parent.find('m:dependencies', NS) is None, 'Root must not add inherited runtime dependencies')
    require(parent.find('m:build/m:plugins', NS) is None, 'Root must not activate plugins for every module')
    managed = BOOT_MANAGED | {coordinate(node) for node in parent.findall('m:dependencyManagement/m:dependencies/m:dependency', NS)}
    plugins = {coordinate(node) for node in parent.findall('m:build/m:pluginManagement/m:plugins/m:plugin', NS)}
    modules = [node.text.strip() for node in parent.findall('m:modules/m:module', NS)]
    require(len(modules) == len(set(modules)), 'Duplicate reactor modules')
    inventory = json.loads((root / 'deploy/components.json').read_text(encoding='utf-8'))
    expected = {item['name'] for item in inventory['components'] if item['runtime'] == 'jvm'} | {'platform-security', 'qa-tests'}
    require(set(modules) == expected, 'Reactor modules do not match the component inventory and build/test libraries')
    group, artifact = coordinate(parent)
    version = value(parent, 'version')
    for module in modules:
        path = (root / module / 'pom.xml').resolve()
        require(root in path.parents, 'Module path must stay inside the repository')
        model = ET.parse(path).getroot()
        inherited = model.find('m:parent', NS)
        require(inherited is not None and coordinate(inherited) == (group, artifact)
                and value(inherited, 'version') == version and value(inherited, 'relativePath') == '../pom.xml',
                module + ': must inherit the repository parent via ../pom.xml')
        require(not value(model, 'groupId') and not value(model, 'version'), module + ': group/version must be inherited')
        overrides = {node.tag.split('}')[-1] for node in model.findall('m:properties/*', NS)} & shared
        require(not overrides, module + ': duplicated shared properties: ' + ', '.join(sorted(overrides)))
        for dependency in model.findall('.//m:dependency', NS):
            require(coordinate(dependency) not in managed or not value(dependency, 'version'),
                    module + ': managed dependency version must be inherited: ' + ':'.join(coordinate(dependency)))
        for plugin in model.findall('.//m:plugin', NS):
            require(coordinate(plugin) not in plugins or not value(plugin, 'version'),
                    module + ': managed plugin version must be inherited: ' + ':'.join(coordinate(plugin)))
    return {'status': 'PASS', 'modules': len(modules), 'sharedProperties': len(shared),
            'bootParentVersion': value(parent, 'parent/version'), 'architectureChanged': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        report = check(args.root)
    except (ValueError, OSError, ET.ParseError) as error:
        parser.exit(1, 'Maven layout failed: ' + str(error) + '\n')
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('PASS Maven parent: ' + str(report['modules']) + ' modules inherit shared versions and settings')
