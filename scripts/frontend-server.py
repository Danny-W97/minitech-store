#!/usr/bin/env python3
"""Local static frontend: compressed text, revalidated scripts, private HTML shells."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


class NoCacheHandler(SimpleHTTPRequestHandler):
    def send_head(self):
        self.cacheable_asset = False
        target = Path(self.translate_path(self.path))
        if target.is_dir() and urlsplit(self.path).path.endswith('/'):
            target = target / 'index.html'
        # Bound buffering to static text; all other resources use the existing handler.
        if target.suffix.lower() not in {'.html', '.js', '.css'} or not target.is_file():
            return super().send_head()
        try:
            with target.open('rb') as stream:
                body = stream.read(5 * 1024 * 1024 + 1)
        except OSError:
            self.send_error(404, 'File not found')
            return None
        if len(body) > 5 * 1024 * 1024:
            return super().send_head()
        self.cacheable_asset = target.suffix.lower() in {'.js', '.css'}
        compressed = len(body) >= 1024 and self.accepts_gzip(self.headers.get('Accept-Encoding', ''))
        if compressed:
            body = gzip.compress(body, mtime=0)
        # Validator identifies the selected representation, including content encoding.
        etag = '"' + hashlib.sha256(body).hexdigest() + '"'
        candidates = [v.strip().removeprefix('W/') for v in self.headers.get('If-None-Match', '').split(',')]
        not_modified = self.cacheable_asset and ('*' in candidates or etag in candidates)
        self.send_response(304 if not_modified else 200)
        self.send_header('Content-Type', self.guess_type(str(target)))
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Vary', 'Accept-Encoding')
        if self.cacheable_asset:
            self.send_header('ETag', etag)
        if compressed:
            self.send_header('Content-Encoding', 'gzip')
        self.end_headers()
        return None if not_modified else io.BytesIO(body)

    @staticmethod
    def accepts_gzip(value: str) -> bool:
        # Explicit gzip;q=0 overrides a wildcard; malformed quality values fail closed.
        encodings = {}
        for item in value.split(','):
            parts = [p.strip() for p in item.lower().split(';')]
            if parts[0] not in {'gzip', '*'}:
                continue
            quality = 1.0
            for parameter in parts[1:]:
                if parameter.startswith('q='):
                    try:
                        quality = float(parameter[2:])
                    except ValueError:
                        quality = 0
            encodings[parts[0]] = quality if 0 <= quality <= 1 else 0
        return encodings.get('gzip', encodings.get('*', 0)) > 0

    def end_headers(self) -> None:
        if getattr(self, 'cacheable_asset', False):
            self.send_header('Cache-Control', 'no-cache, must-revalidate')
        else:
            self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            self.send_header("Pragma", "no-cache")
        if Path(self.translate_path(self.path)).name == 'order-detail.html':
            self.send_header('X-Robots-Tag', 'noindex, nofollow')
        super().end_headers()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--directory", required=True)
    args = parser.parse_args()

    handler = partial(NoCacheHandler, directory=args.directory)
    server = ThreadingHTTPServer((args.bind, args.port), handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
