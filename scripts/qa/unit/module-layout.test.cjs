const {test} = require('node:test');
const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
test('all page and panel ES module graphs link with matching exports, one version and one refresh policy', () => {
    const run=spawnSync(process.execPath,['scripts/qa/check-a42-modules.cjs'],{encoding:'utf8',windowsHide:true});
    assert.equal(run.status,0,run.stdout+'\n'+run.stderr);
    const result=JSON.parse(run.stdout.trim());
    assert.equal(result.entries,5);assert.ok(result.linkedModules>30);assert.equal(result.evaluatedModules,0);
});
function mutant(t, files) {
    fs.mkdirSync(path.resolve('tmp'),{recursive:true});
    const root = fs.mkdtempSync(path.resolve('tmp/a42-link-mutant-'));
    assert.equal(path.dirname(root),path.resolve('tmp'));
    t.after(() => fs.rmSync(root,{recursive:true,force:true}));
    for (const name of ['frontend/app.js','frontend/product-detail.js','frontend/order-detail.js','frontend/chat-widget.js','scripts/control-center/web/app.js']) {
        const file=path.join(root,name);fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,'');
    }
    for (const [name,content] of Object.entries(files)) fs.writeFileSync(path.join(root,name),content);
    return spawnSync(process.execPath,['scripts/qa/check-a42-modules.cjs',root],{encoding:'utf8',windowsHide:true});
}
test('link guard rejects a missing deployed module', t => {
    const run=mutant(t,{'frontend/app.js':"import './missing.js';"});
    assert.notEqual(run.status,0);assert.match(run.stderr,/Missing module/);
});
test('link guard rejects mismatched named exports before browser execution', t => {
    const run=mutant(t,{'frontend/app.js':"import {missing} from './feature.js';",'frontend/feature.js':'export const present=1;'});
    assert.notEqual(run.status,0);assert.match(run.stderr,/export/);
});
test('link guard rejects two cached versions of the same state module', t => {
    const run=mutant(t,{'frontend/app.js':"import './shared.js?v=1';import './feature.js';",'frontend/feature.js':"import './shared.js?v=2';",'frontend/shared.js':'export const state={};'});
    assert.notEqual(run.status,0);assert.match(run.stderr,/Conflicting module versions/);
});
test('layout guard rejects a second customer refresh lock policy', t => {
    const run=mutant(t,{'frontend/duplicate.js':"const lock='minitech-refresh-session';"});
    assert.notEqual(run.status,0);assert.match(run.stderr,/Duplicated customer refresh policy/);
});
