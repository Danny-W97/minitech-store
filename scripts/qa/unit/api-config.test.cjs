const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../../frontend/api-config.js'), 'utf8');
function configure(url, overrides = {}) {
    const window = { location: new URL(url), ...overrides };
    vm.runInNewContext(source, { window, URL });
    return window.MiniTechApi.baseUrl;
}

test('query parameters cannot change the server receiving credentials', () => {
    for (const page of ['index.html', 'catalog.html', 'product.html?id=101&', 'order-detail.html?id=1&']) {
        const separator = page.includes('?') ? '' : '?';
        assert.equal(configure(`http://127.0.0.1:18070/${page}${separator}api=https://attacker.example/api`),
            'http://127.0.0.1:18080/api');
    }
    assert.equal(configure('https://store.example/?api=https://attacker.example'), 'https://store.example/api');
});

test('all hosted pages use their own origin, including a nondefault development port', () => {
    assert.equal(configure('https://store.example/product.html?id=1'), 'https://store.example/api');
    assert.equal(configure('http://127.0.0.1:18071/index.html'), 'http://127.0.0.1:18071/api');
    assert.equal(configure('http://localhost:18070/index.html'), 'http://localhost:18080/api');
    assert.equal(configure('http://[::1]:18070/index.html'), 'http://[::1]:18080/api');
});

test('a trusted deployment may select a versioned relative API or allow an explicit backend', () => {
    assert.equal(configure('https://store.example/', { MINITECH_API_BASE: '/api/v1/' }),
        'https://store.example/api/v1');
    assert.equal(configure('https://store.example/', {
        MINITECH_API_BASE: 'https://api.example/api/v1/',
        MINITECH_ALLOWED_API_ORIGINS: ['https://api.example']
    }), 'https://api.example/api/v1');
});

test('unsafe deployment overrides fail closed', () => {
    for (const override of ['https://attacker.example/api', 'javascript:alert(1)',
        'https://user:pass@store.example/api', '/api?redirect=other', '/api#other', 'http://store.example/api']) {
        assert.throws(() => configure('https://store.example/', { MINITECH_API_BASE: override }), /untrusted/);
    }
});
