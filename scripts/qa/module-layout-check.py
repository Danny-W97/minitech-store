#!/usr/bin/env python3
"""Keep CC features independently importable and the HTTP adapter in one place."""
import ast
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FEATURES = ('cc_actions', 'cc_diagnostics', 'cc_transport', 'cc_secrets', 'cc_health',
            'cc_data', 'cc_events', 'cc_chaos', 'cc_runtime', 'cc_ai', 'cc_http', 'cc_job_logs', 'cc_job_output',
            'cc_log_writer', 'cc_log_sink')


def check(root=ROOT):
    folder = Path(root) / 'scripts/control-center/web'
    server = ast.parse((folder / 'server.py').read_text(encoding='utf-8'))
    functions = {node.name: node for node in server.body if isinstance(node, ast.FunctionDef)}
    count = 0
    for feature in FEATURES:
        tree = ast.parse((folder / (feature + '.py')).read_text(encoding='utf-8'))
        for node in tree.body:
            if not isinstance(node, (ast.Import, ast.ImportFrom, ast.FunctionDef, ast.ClassDef)):
                if not (isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str)):
                    raise ValueError('Feature must not execute effects on import: ' + feature)
            if isinstance(node, ast.Import) and any(item.name == 'server' for item in node.names):
                raise ValueError('Feature imports the composition root: ' + feature)
            if isinstance(node, ast.ImportFrom) and node.module == 'server':
                raise ValueError('Feature imports the composition root: ' + feature)
            if isinstance(node, ast.FunctionDef) and node.args.args and node.args.args[0].arg == 'services':
                wrapper = functions.get(node.name)
                if wrapper is None or len(wrapper.body) != 1 or not isinstance(wrapper.body[0], ast.Return):
                    raise ValueError('Missing thin facade for ' + node.name)
                call = wrapper.body[0].value
                if not (isinstance(call, ast.Call) and isinstance(call.func, ast.Attribute)
                        and isinstance(call.func.value, ast.Name) and call.func.value.id == feature
                        and call.func.attr == node.name and call.args and isinstance(call.args[0], ast.Name)
                        and call.args[0].id == '_services'):
                    raise ValueError('Duplicate implementation in facade: ' + node.name)
                count += 1
    handlers = [node for node in server.body if isinstance(node, ast.ClassDef) and node.name == 'Handler']
    if len(handlers) != 1 or ast.unparse(handlers[0].bases[0]) != 'ControlHttpHandler':
        raise ValueError('HTTP policy and routes must use ControlHttpHandler')
    if any(isinstance(node, ast.FunctionDef) for node in handlers[0].body):
        raise ValueError('HTTP handlers must not be copied back into server.py')
    if count < 50:
        raise ValueError('Expected extracted CC feature facades')
    return {'status': 'PASS', 'featureModules': len(FEATURES), 'facades': count, 'httpImplementations': 1}


if __name__ == '__main__':
    print(json.dumps(check(), sort_keys=True))
