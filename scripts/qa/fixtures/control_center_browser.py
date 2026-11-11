"""Real CC HTTP/session policy with isolated business effects for browser tests."""
import json
from pathlib import Path
import sys
import tempfile
import threading
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts/control-center/web'))
import server


class FixtureHandler(server.Handler):
    def do_GET(self):
        if self.path.split('?', 1)[0] == '/api/data/invoices/export-zip':
            self.send_response(200)
            self.send_header('Content-Type', 'application/zip')
            self.end_headers()
            self.wfile.write(b'PK\x05\x06' + b'\x00' * 18)
            return
        if self.path == '/api/test/document.pdf':
            self.send_response(200)
            self.send_header('Content-Type', 'application/pdf')
            self.end_headers()
            self.wfile.write(b'%PDF-1.4\nSAFE_DOWNLOAD_FIXTURE\n%%EOF')
        else:
            super().do_GET()

    def do_POST(self):
        if self.path == '/api/ai/chat/preview':
            self.read_json()
            self.send_json({'mode': 'local', 'externalDelivery': False})
        elif self.path == '/api/test/expire':
            self.read_json()
            with self.server.panel_sessions.lock: self.server.panel_sessions.sessions.clear()
            self.send_json({'ok': True})
        elif self.path == '/api/terminal/safe/input':
            data = self.read_json()
            session = server.TERMINALS['safe']
            with session.lock: session.output.extend(data.get('data', '').encode())
            self.send_json({'ok': True})
        else:
            super().do_POST()


def terminal():
    session = SimpleNamespace(id='safe', alive=True, output=bytearray(b'SAFE TERMINAL\n'), lock=threading.Lock())
    server.TERMINALS[session.id] = session
    return session


server.active_profile = lambda: 'dev'
server.active_language = lambda: 'pl'
server.build_actions = lambda lang: []
server.check_service = lambda item: {'alias': item[0], 'name': item[1], 'port': item[2], 'online': False, 'status': 'OFFLINE'}
server.public_secret_profiles = lambda: {}
server.start_terminal = terminal
server.get_orders_data = lambda params: {'orders': [], 'hasMore': False, 'nextCursor': '0'}
server.get_invoices_data = lambda params: {'invoices': [], 'hasMore': False, 'nextCursor': '0'}
server.api_json = lambda *args, **kwargs: {'role': 'OPERATOR'}
server.platform_request = lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError('External requests disabled in fixture'))
server.urllib.request.urlopen = lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError('External requests disabled in fixture'))
server.open_ai_request = lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError('External requests disabled in fixture'))

(ROOT / 'tmp').mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='cc-browser-', dir=ROOT / 'tmp') as directory:
    server.JOB_ROOT = Path(directory)
    server.AI_SETTINGS = server.AiSettings(Path(directory) / 'ai-config.json', lambda: Path(directory) / 'vault', lambda: 'dev',
                                         {'OPENAI_API_KEY': 'cc-fixture-env-secret-1234'})
    server.AI_SETTINGS.save({'provider': 'codex', 'model': 'fixture'})
    local = server.create_server('127.0.0.1', 0)
    local.RequestHandlerClass = FixtureHandler
    print(json.dumps({'url': f'http://127.0.0.1:{local.server_port}'}), flush=True)
    try: local.serve_forever()
    finally: local.server_close()
