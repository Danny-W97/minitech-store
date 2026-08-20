import { createReadStream, existsSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, normalize, resolve } from 'node:path';

const root = resolve(process.env.PLAYWRIGHT_FRONTEND_ROOT || 'frontend');
const host = process.env.PLAYWRIGHT_FRONTEND_HOST || '127.0.0.1';
const port = Number(process.env.PLAYWRIGHT_FRONTEND_PORT || 18070);
const mimeTypes = {
  '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml'
};

createServer((request, response) => {
  const pathname = decodeURIComponent(new URL(request.url || '/', `http://${host}`).pathname);
  const requested = pathname === '/' ? '/index.html' : pathname;
  const file = normalize(join(root, requested));
  if (!file.startsWith(root) || !existsSync(file) || !statSync(file).isFile()) {
    response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    response.end('Not found');
    return;
  }
  response.writeHead(200, {
    'Cache-Control': 'no-store',
    'Content-Type': mimeTypes[extname(file).toLowerCase()] || 'application/octet-stream'
  });
  createReadStream(file).pipe(response);
}).listen(port, host, () => {
  process.stdout.write(`MiniTech Store Playwright server: http://${host}:${port}\n`);
});
