// Real storefront module -> real Customer/Gateway -> disposable PostgreSQL.
// Receives fixture credentials only on stdin; never writes them or exported data to disk.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const {createHash} = require('node:crypto');
const {chromium} = require('@playwright/test');
async function main(config) {
    const root = path.resolve(__dirname, '../..'), frontend = path.join(root, 'frontend');
    const server = http.createServer((request, response) => {
        const relative = decodeURIComponent(new URL(request.url, 'http://fixture').pathname);
        const file = path.resolve(frontend, '.' + (relative === '/' ? '/index.html' : relative));
        if (!file.startsWith(frontend + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {response.writeHead(404); response.end(); return;}
        const type = {'.js': 'application/javascript', '.html': 'text/html', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml'}[path.extname(file)] || 'application/octet-stream';
        response.writeHead(200, {'Content-Type': type, 'Cache-Control': 'no-store'}); fs.createReadStream(file).pipe(response);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    let browser;
    const checks = [], errors = [];
    try {
        browser = await chromium.launch({headless: true});
        const context = await browser.newContext({locale: 'pl-PL', acceptDownloads: true});
        const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
        await page.addInitScript(() => localStorage.setItem('minitech-auth:last-activity', String(Date.now())));
        let forwarded = 0;
        await page.route('**/api/**', async route => {
            const request = route.request(), url = new URL(request.url());
            const endpoint = url.pathname.replace(/^\/api/, '');
            if (endpoint.startsWith('/customers/me/data-export/jobs')) {
                forwarded++;
                // This is a same-origin fixture proxy. Its ephemeral listener origin is not a public Gateway origin.
                const headers = {...request.headers()}; delete headers.origin; delete headers.referer; delete headers.host;
                let response;
                try {response = await route.fetch({url: config.gateway + url.pathname + url.search, headers, maxRedirects: 0, timeout: 15000});}
                catch {throw Error('Fixture Gateway request failed');}
                await route.fulfill({response}); return;
            }
            if (endpoint === '/customers/refresh') return route.fulfill({json: {accessToken: config.accessToken, customer: config.customer}});
            return route.fulfill({json: []});
        });
        await page.goto('http://127.0.0.1:' + server.address().port + '/index.html');
        const {expect} = require('@playwright/test');
        await expect(page.locator('#auth-toggle')).toContainText(config.customer.email);
        await page.locator('#auth-toggle').click(); await page.locator('#account-menu-profile').click();
        const rootNode = page.locator('#auth-account-export');
        const action = name => rootNode.locator('[data-export-action="' + name + '"]');
        const status = rootNode.locator('[data-export-status]');
        await expect(action('start')).toBeEnabled(); await action('start').click();
        await expect(status).toContainText('Wynik częściowy', {timeout: 60000});
        await action('resume').click(); await expect(status).toContainText('Zakres automatyczny ukończony', {timeout: 60000});
        checks.push('real browser prepares job through Gateway, observes source 503 as partial, and resumes to automatic completion');
        const downloaded = page.waitForEvent('download'); await action('download').click();
        const download = await downloaded, stream = await download.createReadStream(), pieces = [];
        for await (const part of stream) pieces.push(Buffer.from(part));
        const bytes = Buffer.concat(pieces), text = bytes.toString('utf8'), doc = JSON.parse(text);
        if (download.suggestedFilename() !== 'account-data-export.json' || doc.complete || !doc.automaticScopeComplete || doc.parts.length !== 26 || Object.keys(doc.manifest.serviceClassification).length !== 14) throw Error('Real browser export contract mismatch');
        if (text.includes(config.accessToken) || text.includes('private-password')) throw Error('Credential material in downloaded fixture result');
        const profile = doc.parts.find(part => part.scope === 'customer-profile-and-security-summary');
        if (profile?.data.profile.id !== config.customer.id) throw Error('Wrong account profile');
        const sourceBytes = doc.parts.reduce((sum, part) => sum + part.bytes, 0);
        if (sourceBytes !== doc.manifest.recordedBytes) throw Error('Incomplete real download');
        const storage = await page.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]));
        if (storage.includes('account-export-manifest') || storage.includes('account-data-export')) throw Error('Export persisted in browser storage');
        checks.push('26 verified real stored parts, 14-owner manifest, correct account, no credentials or export storage, lossless assembled JSON');
        fs.mkdirSync(config.directory, {recursive: true}); await rootNode.screenshot({path: path.join(config.directory, 'real-account-export.png')});
        await action('cancel').click(); await expect(status).toContainText('Eksport anulowany'); await expect(action('download')).toBeHidden();
        checks.push('browser cancellation revokes the actual server result');
        if (errors.length) throw Error('Storefront runtime error: ' + errors.join('; '));
        await context.close();
        return {status: 'PASS', checks, forwardedRequests: forwarded, automaticParts: doc.parts.length, downloadBytes: bytes.length,
            downloadSha256: createHash('sha256').update(bytes).digest('hex'), fullAccountExportComplete: false, userDataAccessed: false};
    } finally {if (browser) await browser.close(); await new Promise(resolve => server.close(resolve));}
}
let input = ''; process.stdin.setEncoding('utf8'); process.stdin.on('data', chunk => {input += chunk;});
process.stdin.on('end', () => {main(JSON.parse(input)).then(result => process.stdout.write(JSON.stringify(result) + '\n')).catch(error => {console.error(error.message); process.exitCode = 1;});});
