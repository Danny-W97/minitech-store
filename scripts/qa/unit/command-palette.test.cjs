const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../control-center/web/command-palette.js'), 'utf8');
const modulePromise = import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

test('navigation uses supplied translations and unavailable operations are not invented', async () => {
    const {buildPaletteItems} = await modulePromise;
    const items = buildPaletteItems({viewTitles: {dashboard: 'Pulpit fixture', data: 'Dane fixture'}});
    assert.equal(items.filter(item => item.type === 'view').length, 12);
    assert.equal(items.find(item => item.id === 'view:dashboard').title, 'Widok: Pulpit fixture');
    assert.equal(items.some(item => item.type === 'action'), false);
    assert.equal(items.filter(item => item.type === 'report').length, 1);
});

test('distinct IDs remain selectable even when titles are identical or contain each other', async () => {
    const {buildPaletteItems} = await modulePromise;
    const items = buildPaletteItems({actions: [{id: 'first', title: 'Wyczyść dane testowe'}, {id: 'second', title: 'Wyczyść dane'}, {id: 'third', title: 'Wyczyść dane'}]});
    assert.deepEqual(items.filter(item => item.type === 'action').map(item => item.target), ['first', 'second', 'third']);
});

test('duplicate operation IDs and known shortcuts appear only once with the server title', async () => {
    const {buildPaletteItems} = await modulePromise;
    const items = buildPaletteItems({actions: [{id: 'fresh-start', title: 'Authoritative title'}, {id: 'fresh-start', title: 'Duplicate'}]});
    const matches = items.filter(item => item.id === 'action:fresh-start');
    assert.equal(matches.length, 1); assert.equal(matches[0].title, 'Authoritative title'); assert.equal(matches[0].icon, '🚀');
});

test('search combines accent-insensitive words from titles, groups, IDs and descriptions', async () => {
    const {buildPaletteItems, filterPaletteItems} = await modulePromise;
    const items = buildPaletteItems({actions: [{id: 'sample-backup', title: 'Załaduj bazę', group: 'Kopie', description: 'Środowisko testowe'}]});
    assert.deepEqual(filterPaletteItems(items, '  BAZE zaladuj TESTOWE kopie  ').map(item => item.target), ['sample-backup']);
    assert.deepEqual(filterPaletteItems(items, 'sample-backup').map(item => item.target), ['sample-backup']);
    assert.equal(filterPaletteItems(items, 'magazyn')[0].target, 'data');
});

test('language changes take new server labels instead of retaining a hardcoded operation translation', async () => {
    const {buildPaletteItems} = await modulePromise;
    const pl = buildPaletteItems({language: 'pl', actions: [{id: 'fixture', title: 'Polska nazwa'}]});
    const en = buildPaletteItems({language: 'en', actions: [{id: 'fixture', title: 'English name'}], viewTitles: {data: 'Business data'}});
    assert.equal(pl.find(item => item.target === 'fixture').title, 'Polska nazwa');
    assert.equal(en.find(item => item.target === 'fixture').title, 'English name');
    assert.equal(en.find(item => item.target === 'fixture').category, 'Operations');
    assert.equal(en.find(item => item.id === 'view:data').title, 'View: Business data');
});

test('malformed catalogue records are skipped and the source catalogue is never mutated', async () => {
    const {buildPaletteItems} = await modulePromise;
    const valid = Object.freeze({id: 'valid', title: 'Valid', group: 'Fixture'});
    const input = Object.freeze([null, {}, {id: 7, title: 'wrong'}, {id: 'empty', title: ''}, valid]);
    assert.deepEqual(buildPaletteItems({actions: input}).filter(item => item.type === 'action').map(item => item.target), ['valid']);
    assert.equal(input.at(-1), valid); assert.doesNotThrow(() => buildPaletteItems({actions: null}));
});

test('external commands require HTTP(S) without embedded credentials and Swagger is not duplicated', async () => {
    const {buildPaletteItems} = await modulePromise;
    const items = buildPaletteItems({actions: [
        {id: 'bad', title: 'Bad', externalUrl: 'javascript:alert(1)'}, {id: 'data', title: 'Data', externalUrl: 'data:text/html,x'},
        {id: 'credentials', title: 'Credentials', externalUrl: 'https://user:pass@example.test/'},
        {id: 'relative', title: 'Relative', externalUrl: '/api/docs'},
        {id: 'swagger-ui', title: 'Server docs', externalUrl: 'http://127.0.0.1:18080/swagger-ui.html'},
        {id: 'valid', title: 'Valid docs', externalUrl: 'https://example.test/docs#section'}]});
    assert.deepEqual(items.filter(item => item.type === 'external').map(item => item.title), ['Server docs', 'Valid docs']);
});

test('catalogue text stays plain data and prototype-like IDs cannot become executable presentation', async () => {
    const {buildPaletteItems} = await modulePromise;
    const title = '<img src=x onerror=alert(1)>';
    const item = buildPaletteItems({actions: [{id: '__proto__', title}]}).find(item => item.type === 'action');
    assert.equal(item.title, title); assert.equal(item.icon, '⌘'); assert.equal(item.target, '__proto__');
});

test('keyboard selection wraps safely and empty results never produce a valid index', async () => {
    const {nextPaletteIndex} = await modulePromise;
    assert.equal(nextPaletteIndex(0, -1, 3), 2); assert.equal(nextPaletteIndex(2, 1, 3), 0);
    assert.equal(nextPaletteIndex(-1, 1, 3), 0); assert.equal(nextPaletteIndex(-1, -1, 3), 2);
    assert.equal(nextPaletteIndex(0, 1, 0), -1); assert.equal(nextPaletteIndex(0, -1, 1), 0);
});

test('filtering preserves catalogue order and object identity and can recover from an empty result', async () => {
    const {buildPaletteItems, filterPaletteItems} = await modulePromise;
    const items = buildPaletteItems({actions: [{id: 'a', title: 'Fixture A'}, {id: 'b', title: 'Fixture B'}]});
    assert.equal(filterPaletteItems(items, '   '), items); assert.deepEqual(filterPaletteItems(items, 'missing fixture'), []);
    const results = filterPaletteItems(items, 'fixture');
    assert.deepEqual(results.map(item => item.target), ['a', 'b']); assert.equal(results[0], items.find(item => item.target === 'a'));
});
