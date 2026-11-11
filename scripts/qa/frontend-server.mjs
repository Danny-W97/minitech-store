import { createReadStream, realpathSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, isAbsolute, join, relative, resolve, sep } from 'node:path';

const root = realpathSync(resolve(process.env.PLAYWRIGHT_FRONTEND_ROOT || 'frontend'));
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

const insideRoot = file => {
  const path = relative(root, file);
  return path !== '..' && !path.startsWith(`..${sep}`) && !isAbsolute(path);
};

const server = createServer((request, response) => {
  let pathname;
  try {
    pathname = decodeURIComponent(new URL(request.url || '/', `http://${host}`).pathname);
  } catch {
    response.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
    response.end('Invalid path');
    return;
  }
  const requested = pathname === '/' ? '/index.html' : pathname;
  let file;
  try {
    file = resolve(join(root, requested));
    if (!insideRoot(file)) throw new Error('Outside root');
    file = realpathSync(file);
    if (!insideRoot(file) || !statSync(file).isFile()) throw new Error('Unavailable file');
  } catch {
    response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    response.end('Not found');
    return;
  }
  response.writeHead(200, {
    'Cache-Control': 'no-store',
    'Content-Type': mimeTypes[extname(file).toLowerCase()] || 'application/octet-stream'
  });
  if (request.method === 'HEAD') return response.end();
  createReadStream(file).on('error', () => response.destroy()).pipe(response);
}).listen(port, host, () => {
  process.stdout.write(`MiniTech Store Playwright server: http://${host}:${server.address().port}\n`);
});
