const {test, before, after} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const {spawn} = require('node:child_process');
const {once} = require('node:events');
let temporary, child, port;

before(async () => {
  temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'a33-static-'));
  const root = path.join(temporary, 'frontend'), sibling = path.join(temporary, 'frontend-private');
  fs.mkdirSync(root);fs.mkdirSync(sibling);
  fs.writeFileSync(path.join(root, 'index.html'), '<html>fixture</html>');
  fs.writeFileSync(path.join(root, 'app.js'), 'export const fixture = 1;');
  fs.writeFileSync(path.join(sibling, 'sentinel.txt'), 'outside-fixture-root');
  fs.symlinkSync(sibling, path.join(root, 'linked'), process.platform === 'win32' ? 'junction' : 'dir');
  child = spawn(process.execPath, ['scripts/qa/frontend-server.mjs'], {
    env: {PLAYWRIGHT_FRONTEND_ROOT: root, PLAYWRIGHT_FRONTEND_HOST: '127.0.0.1', PLAYWRIGHT_FRONTEND_PORT: '0'},
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let output = '';
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error('Fixture startup timed out')), 10000);
    child.once('error', reject);child.once('exit', () => reject(Error('Fixture exited before startup')));
    child.stdout.on('data', data => {
      output += data.toString();const match = output.match(/http:\/\/127\.0\.0\.1:(\d+)\r?\n/);
      if (match) {port = Number(match[1]);clearTimeout(timer);resolve();}
    });
  });
});
after(async () => {
  if (child && child.exitCode === null) {const exited = once(child, 'exit');child.kill();await exited;}
  if (temporary) {
    // Remove only the exact private fixture directory created by this test.
    assert.ok(path.resolve(temporary).startsWith(path.resolve(os.tmpdir()) + path.sep + 'a33-static-'));
    fs.rmSync(temporary, {recursive: true, force: true});
  }
});

function request(url, method = 'GET') {
  return new Promise((resolve, reject) => {
    const r = http.request({host: '127.0.0.1', port, path: url, method}, response => {
      const chunks = [];response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => resolve({status: response.statusCode, headers: response.headers, body: Buffer.concat(chunks).toString()}));
    });
    r.on('error', reject);r.setTimeout(5000, () => r.destroy(Error('Fixture request timed out')));r.end();
  });
}

test('normal files, query versions and HEAD remain available', async () => {
  assert.equal((await request('/')).body, '<html>fixture</html>');
  const script = await request('/app.js?v=manual');assert.equal(script.status, 200);
  assert.equal(script.headers['cache-control'], 'no-store');
  assert.equal(script.body, 'export const fixture = 1;');
  const head = await request('/app.js', 'HEAD');assert.equal(head.status, 200);assert.equal(head.body, '');
});
test('encoded traversal cannot escape into a sibling with the same directory prefix', async () => {
  for (const url of ['/..%2Ffrontend-private/sentinel.txt', '/%2e%2e%2ffrontend-private/sentinel.txt']) {
    const r = await request(url);assert.equal(r.status, 404);assert.ok(!r.body.includes('outside-fixture-root'));
  }
});
test('a symlink cannot expose files outside the frontend root', async () => {
  const r = await request('/linked/sentinel.txt');assert.equal(r.status, 404);assert.ok(!r.body.includes('outside-fixture-root'));
});
test('malformed encoding and missing files do not terminate the test server', async () => {
  assert.equal((await request('/%E0%A4%A')).status, 400);
  assert.equal((await request('/missing.js')).status, 404);
  assert.equal((await request('/app.js')).status, 200);
});
