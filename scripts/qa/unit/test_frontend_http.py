"""Real loopback HTTP against synthetic static files; no project photos or account data."""
from functools import partial
from contextlib import closing
import gzip
import http.client
import importlib.util
import os
from pathlib import Path
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('frontend_server', ROOT / 'scripts/frontend-server.py')
frontend = importlib.util.module_from_spec(spec);spec.loader.exec_module(frontend)


class FrontendHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.root = Path(cls.directory.name)
        cls.raw = b'/* static fixture */\n' * 200
        for name in ('index.html', 'order-detail.html', 'app.js', 'styles.css'):
            (cls.root / name).write_bytes(cls.raw)
        (cls.root / 'photo.png').write_bytes(b'fixture image, not a real photo' * 100)
        class Quiet(frontend.NoCacheHandler):
            def log_message(self, *args):pass
        cls.server = frontend.ThreadingHTTPServer(('127.0.0.1', 0), partial(Quiet, directory=cls.directory.name))
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True);cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown();cls.server.server_close();cls.thread.join();cls.directory.cleanup()

    def get(self, path, headers=None, method='GET'):
        with closing(http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=5)) as connection:
            connection.request(method, path, headers=headers or {})
            response = connection.getresponse()
            return response.status, dict((k.lower(), v) for k, v in response.getheaders()), response.read()

    def test_text_compression_preserves_bytes_and_varies_on_encoding(self):
        for path in ('/', '/index.html', '/app.js', '/styles.css'):
            with self.subTest(path=path):
                status, headers, body = self.get(path, {'Accept-Encoding': 'gzip'})
                self.assertEqual(200, status);self.assertEqual('gzip', headers['content-encoding'])
                self.assertEqual('Accept-Encoding', headers['vary']);self.assertEqual(self.raw, gzip.decompress(body))
                self.assertLess(len(body), len(self.raw))

    def test_quality_zero_or_bad_quality_never_selects_gzip(self):
        for encoding in ('gzip;q=0', '*;q=1, gzip;q=0', 'br', 'notgzip', 'gzip;q=invalid', 'gzip;q=NaN', 'gzip;q=2'):
            with self.subTest(encoding=encoding):
                _, headers, body = self.get('/app.js', {'Accept-Encoding': encoding})
                self.assertNotIn('content-encoding', headers);self.assertEqual(self.raw, body)

    def test_wildcard_and_positive_quality_allow_gzip(self):
        for encoding in ('*;q=0.5', 'br, gzip;q=0.2', 'GZIP'):
            with self.subTest(encoding=encoding):
                _, headers, _ = self.get('/app.js', {'Accept-Encoding': encoding})
                self.assertEqual('gzip', headers['content-encoding'])

    def test_script_and_stylesheet_validate_instead_of_using_immutable_query_versions(self):
        for path in ('/app.js?v=old-manual-version', '/styles.css?v=123'):
            with self.subTest(path=path):
                _, headers, _ = self.get(path)
                self.assertEqual('no-cache, must-revalidate', headers['cache-control']);self.assertIn('etag', headers)
                status, repeated, body = self.get(path, {'If-None-Match': headers['etag']})
                self.assertEqual(304, status);self.assertEqual(b'', body);self.assertEqual(headers['etag'], repeated['etag'])

    def test_weak_and_list_validators_use_selected_representation(self):
        _, headers, _ = self.get('/app.js')
        for value in ('W/' + headers['etag'], '"other", ' + headers['etag'], '*'):
            with self.subTest(value=value):self.assertEqual(304, self.get('/app.js', {'If-None-Match': value})[0])

    def test_compressed_and_identity_representations_have_distinct_etags(self):
        _, plain, _ = self.get('/app.js')
        _, encoded, _ = self.get('/app.js', {'Accept-Encoding': 'gzip'})
        self.assertNotEqual(plain['etag'], encoded['etag'])
        self.assertEqual(200, self.get('/app.js', {'Accept-Encoding': 'gzip', 'If-None-Match': plain['etag']})[0])
        self.assertEqual(304, self.get('/app.js', {'Accept-Encoding': 'gzip', 'If-None-Match': encoded['etag']})[0])

    def test_same_timestamp_edit_invalidates_cached_script(self):
        path = self.root / 'changing.js';path.write_bytes(self.raw);before = path.stat()
        _, headers, _ = self.get('/changing.js');path.write_bytes(b'new ' + self.raw)
        os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
        status, after, body = self.get('/changing.js', {'If-None-Match': headers['etag']})
        self.assertEqual(200, status);self.assertNotEqual(headers['etag'], after['etag']);self.assertEqual(b'new ' + self.raw, body)

    def test_html_shell_is_not_stored_or_reused_from_script_validator(self):
        for path in ('/', '/index.html', '/order-detail.html'):
            with self.subTest(path=path):
                status, headers, body = self.get(path, {'If-None-Match': '*'})
                self.assertEqual(200, status);self.assertIn('no-store', headers['cache-control'])
                self.assertNotIn('etag', headers);self.assertEqual(self.raw, body)

    def test_missing_script_is_404_without_caching_or_html_shell_fallback(self):
        status, headers, body = self.get('/missing.js')
        self.assertEqual(404, status);self.assertIn('no-store', headers['cache-control']);self.assertNotEqual(self.raw, body)

    def test_photo_headers_and_bytes_keep_existing_policy(self):
        _, headers, body = self.get('/photo.png', {'Accept-Encoding': 'gzip'})
        self.assertNotIn('content-encoding', headers);self.assertNotIn('etag', headers)
        self.assertEqual('no-store, no-cache, must-revalidate, max-age=0', headers['cache-control'])
        self.assertEqual((self.root / 'photo.png').read_bytes(), body)

    def test_order_details_have_noindex_without_affecting_public_catalog_policy(self):
        for path in ('/order-detail.html?order=fixture', '/%6Frder-detail.html', '/ignored/../order-detail.html'):
            with self.subTest(path=path):self.assertEqual('noindex, nofollow', self.get(path)[1]['x-robots-tag'])
        self.assertNotIn('x-robots-tag', self.get('/index.html')[1])

    def test_head_uses_same_selected_representation_without_body(self):
        _, headers, body = self.get('/app.js', {'Accept-Encoding': 'gzip'}, method='HEAD')
        _, get_headers, encoded = self.get('/app.js', {'Accept-Encoding': 'gzip'})
        self.assertEqual(b'', body);self.assertEqual(str(len(encoded)), headers['content-length'])
        self.assertEqual(get_headers['etag'], headers['etag'])


if __name__ == '__main__':unittest.main()
