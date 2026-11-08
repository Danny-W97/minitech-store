const {test,before} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
let messages;
before(async()=>{messages=await import('data:text/javascript;base64,'+Buffer.from(fs.readFileSync('frontend/store-messages.js','utf8')).toString('base64'));});
test('store dictionaries are independently importable and PL/EN cover the same 215 keys',()=>{
    const {pl,en}=messages.DICTIONARY;
    assert.equal(Object.keys(pl).length,215);assert.deepEqual(Object.keys(pl).sort(),Object.keys(en).sort());
    for(const key of Object.keys(pl)){assert.equal(typeof pl[key],'string');assert.equal(typeof en[key],'string');}
});
test('category, parameter and order status labels provide both supported languages',()=>{
    for(const name of ['CATEGORY_NAMES','PARAMETER_LABELS','ORDER_STATUSES'])
        for(const value of Object.values(messages[name])){assert.equal(typeof value.pl,'string');assert.equal(typeof value.en,'string');}
});
