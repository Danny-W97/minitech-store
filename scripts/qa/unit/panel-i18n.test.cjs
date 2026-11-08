const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../../control-center/web/panel-i18n.js'), 'utf8');
const modulePromise = import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

test('panel locales expose the same nonempty message paths without browser globals', async () => {
    const { getPanelMessages, normalizePanelLanguage } = await modulePromise;
    const leaves = (value, prefix = '') => Object.entries(value).flatMap(([key, item]) => {
        const name = prefix ? `${prefix}.${key}` : key;
        if (typeof item === 'object' && item !== null) return leaves(item, name);
        assert.equal(typeof item, 'string', name);
        assert.ok(item.trim(), name);
        return [name];
    }).sort();
    assert.deepEqual(leaves(getPanelMessages('en')), leaves(getPanelMessages('pl')));
    for (const language of [undefined, null, '', 'de', 'EN', '<script>']) {
        assert.equal(normalizePanelLanguage(language), 'pl');
        assert.equal(getPanelMessages(language), getPanelMessages('pl'));
    }
    assert.equal(normalizePanelLanguage('en'), 'en');
});

test('current action metadata takes precedence over a historical job title', async () => {
    const { getPanelJobTitle } = await modulePromise;
    const job = {actionId: 'safe-fixture', title: 'Environment Doctor'};
    assert.equal(getPanelJobTitle(job, 'pl', [{id: 'safe-fixture', title: 'Aktualna nazwa'}]), 'Aktualna nazwa');
    assert.equal(getPanelJobTitle(job, 'en', [{id: 'safe-fixture', title: 'Current name'}]), 'Current name');
    assert.equal(getPanelJobTitle(job, 'pl', [{id: 'unrelated', title: 'Other'}]), 'Doctor środowiska');
});

test('historical jobs translate known labels and retain unknown titles', async () => {
    const { getPanelJobTitle } = await modulePromise;
    assert.equal(getPanelJobTitle({title: 'Uruchom infrastrukturę'}, 'en'), 'Start infrastructure');
    assert.equal(getPanelJobTitle({title: 'Start infrastructure'}, 'pl'), 'Uruchom infrastrukturę');
    for (const language of ['pl', 'en']) {
        assert.equal(getPanelJobTitle({title: 'Custom operator task'}, language), 'Custom operator task');
        assert.equal(getPanelJobTitle(null, language), '');
        assert.equal(getPanelJobTitle({}, language), '');
    }
});
test('renderPanelLanguage executes cleanly for all views and locales', async () => {
    const { renderPanelLanguage } = await modulePromise;
    const mockDoc = {
        documentElement: { lang: 'pl' },
        querySelector() { return null; },
        querySelectorAll() { return []; },
    };
    for (const language of ['pl', 'en']) {
        for (const currentView of ['dashboard', 'data', 'chaos', 'chat', 'assistant', 'tests', 'jobs', 'terminal', 'about']) {
            assert.doesNotThrow(() => {
                renderPanelLanguage({ language, profile: 'dev', document: mockDoc, currentView });
            });
        }
    }
});
