const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../../../scripts/control-center/web/panel-client.js'), 'utf8');
const modulePromise = import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const A = 'A'.repeat(43), B = 'B'.repeat(43);
const reply = (body, status = 200) => new Response(JSON.stringify(body), {status, headers:{'Content-Type':'application/json'}});
const expired = () => reply({code:'CC_SESSION_EXPIRED', error:'Expired fixture'},401);
function deferred() {
    let resolve;
    const promise = new Promise(done => {resolve=done;});
    return {promise, resolve};
}
async function fixture(handler, options = {}) {
    const { createControlClient } = await modulePromise;
    const calls = [], expirations = [];
    const client = createControlClient({
        baseUrl:'http://127.0.0.1:12345/#data',
        fetch:async (url, init) => {calls.push({url,init});return handler(url,init,calls.length-1);},
        onSessionExpired:error => expirations.push(error), ...options
    });
    return {client, calls, expirations};
}

test('bootstrap retains the credential privately and sends it only in the header', async () => {
    const f=await fixture((url,init,index)=>index===0?reply({token:A,profile:'dev'}):reply({ok:true}));
    const configuration=await f.client.json('/api/bootstrap');
    assert.deepEqual(configuration,{profile:'dev'});assert.equal(configuration.token,undefined);
    assert.deepEqual(Object.keys(f.client).sort(),['blob','json','logout']);
    await f.client.json('/api/jobs?cursor=5');
    assert.equal(f.calls[1].init.headers.get('X-Control-Center-Token'),A);
    assert.equal(f.calls[1].url,'http://127.0.0.1:12345/api/jobs?cursor=5');
    assert.equal(JSON.stringify(f.client).includes(A),false);
});
test('explicit logout clears local capability first and reports server revocation',async()=>{
    const pending=deferred(), logout=deferred();
    const f=await fixture((url)=>url.endsWith('/bootstrap')?reply({token:A}):url.endsWith('/logout')?logout.promise:pending.promise);
    await f.client.json('/api/bootstrap');const old=f.client.json('/api/data/customers');const rejected=assert.rejects(old);
    const end=f.client.logout();assert.equal(f.expirations.length,1);
    await assert.rejects(f.client.json('/api/jobs'),{code:'CC_SESSION_EXPIRED'});
    pending.resolve(reply({customers:[{email:'private@example.test'}]}));logout.resolve(reply({sessionRevoked:true}));
    await rejected;assert.deepEqual(await end,{serverRevoked:true});
});

test('private JSON and binary requests cannot start before bootstrap', async () => {
    const f=await fixture(()=>reply({ok:true}));
    await assert.rejects(f.client.json('/api/jobs'),{code:'CC_SESSION_EXPIRED'});
    await assert.rejects(f.client.blob('/api/document.pdf'),{code:'CC_SESSION_EXPIRED'});
    assert.equal(f.calls.length,0);
    await f.client.json('/api/ping');assert.equal(f.calls[0].init.headers.has('X-Control-Center-Token'),false);
    await assert.rejects(f.client.json('/api/jobs'),{code:'CC_SESSION_EXPIRED'});
});

test('other origins, ports, credentials and paths outside the API are rejected before fetch', async () => {
    const f=await fixture(()=>reply({token:A}));
    for(const url of ['https://attacker.test/api/jobs','http://127.0.0.1:9999/api/jobs','//attacker.test/api/jobs',
        'http://user:pass@127.0.0.1:12345/api/jobs','/jobs','/api/../terminal','/api/%2e%2e/terminal','javascript:alert(1)']) {
        await assert.rejects(f.client.json(url),/Niedozwolony adres/);
    }
    assert.equal(f.calls.length,0);
});

test('request options cannot enable cookies, redirects or replace the session header', async () => {
    const f=await fixture((url,init,index)=>index===0?reply({token:A}):reply({ok:true}));
    await f.client.json('/api/bootstrap');
    await f.client.json('/api/jobs',{method:'POST',body:'{"action":"fixture"}',credentials:'include',mode:'cors',redirect:'follow',
        headers:new Headers({'x-control-center-token':B,Accept:'application/problem+json'})});
    const {init}=f.calls[1];
    assert.equal(init.credentials,'omit');assert.equal(init.mode,'cors');assert.equal(init.redirect,'error');
    assert.equal(init.headers.get('X-Control-Center-Token'),A);
    assert.equal(init.headers.get('Content-Type'),'application/json');
    assert.equal(init.headers.get('Accept'),'application/problem+json');assert.equal(init.body,'{"action":"fixture"}');
});

test('an explicit session-expiry response invalidates once and blocks subsequent operations', async () => {
    const f=await fixture((url,init,index)=>index===0?reply({token:A}):expired());
    await f.client.json('/api/bootstrap');
    await assert.rejects(f.client.json('/api/health'),{status:401,code:'CC_SESSION_EXPIRED'});
    await assert.rejects(f.client.json('/api/jobs'),{code:'CC_SESSION_EXPIRED'});
    await assert.rejects(f.client.blob('/api/document.pdf'),{code:'CC_SESSION_EXPIRED'});
    assert.equal(f.calls.length,2);assert.equal(f.expirations.length,1);
});

test('ordinary authorization failures do not erase an otherwise valid panel session', async () => {
    for(const status of [401,403]) {
        const f=await fixture((url,init,index)=>index===0?reply({token:A}):index===1?reply({code:'OPERATOR_DENIED'},status):reply({ok:true}));
        await f.client.json('/api/bootstrap');await assert.rejects(f.client.json('/api/data/orders'),{status});
        await f.client.json('/api/jobs');
        assert.equal(f.calls[2].init.headers.get('X-Control-Center-Token'),A);assert.equal(f.expirations.length,0);
    }
});

test('binary responses retain their bytes without creating a URL or parsing JSON', async () => {
    const bytes=Uint8Array.from([80,75,5,6,0,255]);
    const f=await fixture((url,init,index)=>index===0?reply({token:A}):new Response(bytes,{headers:{'Content-Type':'application/zip'}}));
    await f.client.json('/api/bootstrap');const blob=await f.client.blob('/api/export.zip');
    assert.equal(blob.type,'application/zip');assert.deepEqual(new Uint8Array(await blob.arrayBuffer()),bytes);
    assert.equal(f.calls[1].init.headers.get('X-Control-Center-Token'),A);
});

test('network failures do not retry a writing operation or discard the session', async () => {
    const f=await fixture((url,init,index)=>{
        if(index===0)return reply({token:A});if(index===1)throw Error('offline');return reply({ok:true});
    });
    await f.client.json('/api/bootstrap');await assert.rejects(f.client.json('/api/jobs',{method:'POST',body:'{}'}),/offline/);
    assert.equal(f.calls.length,2);await f.client.json('/api/health');
    assert.equal(f.calls[2].init.headers.get('X-Control-Center-Token'),A);assert.equal(f.expirations.length,0);
});

test('definitive error metadata is preserved only for a boolean true value', async () => {
    const f=await fixture((url,init,index)=>index===0?reply({token:A}):reply({error:'fixture',definitive:index===1?true:'true',code:'CONFLICT'},409));
    await f.client.json('/api/bootstrap');
    await assert.rejects(f.client.json('/api/command'),{message:'fixture',status:409,definitive:true,code:'CONFLICT'});
    await assert.rejects(f.client.json('/api/command'),{definitive:false});
});

test('a malformed bootstrap cannot authorize a later request', async () => {
    for(const token of [undefined,null,'',42,'x'.repeat(42),'bad\nheader']) {
        const f=await fixture(()=>reply({token}));
        await assert.rejects(f.client.json('/api/bootstrap'),/Nieprawidłowa odpowiedź/);
        await assert.rejects(f.client.json('/api/jobs'),{code:'CC_SESSION_EXPIRED'});assert.equal(f.calls.length,1);
    }
});

test('two concurrent expiry responses notify the interface once', async () => {
    const one=deferred(),two=deferred();
    const f=await fixture((url,init,index)=>index===0?reply({token:A}):index===1?one.promise:two.promise);
    await f.client.json('/api/bootstrap');
    const results=Promise.allSettled([f.client.json('/api/health'),f.client.json('/api/jobs')]);
    one.resolve(expired());two.resolve(expired());
    assert.deepEqual((await results).map(result=>result.status),['rejected','rejected']);
    assert.equal(f.expirations.length,1);
});

test('an old expiry response cannot invalidate the token from a newer bootstrap', async () => {
    const pending=deferred();
    const f=await fixture((url,init,index)=>index===0?reply({token:A}):index===1?pending.promise:index===2?reply({token:B}):reply({ok:true}));
    await f.client.json('/api/bootstrap');
    const old=f.client.json('/api/health');const result=assert.rejects(old,{status:401});
    await f.client.json('/api/bootstrap');pending.resolve(expired());await result;
    await f.client.json('/api/jobs');
    assert.equal(f.expirations.length,0);assert.equal(f.calls[3].init.headers.get('X-Control-Center-Token'),B);
});

test('a late bootstrap cannot resurrect a session invalidated while it was pending', async () => {
    const pending=deferred();
    const f=await fixture((url,init,index)=>index===0?reply({token:A}):index===1?pending.promise:expired());
    await f.client.json('/api/bootstrap');
    const old=assert.rejects(f.client.json('/api/bootstrap'),{code:'CC_SESSION_EXPIRED'});
    await assert.rejects(f.client.json('/api/health'),{status:401});pending.resolve(reply({token:B}));await old;
    await assert.rejects(f.client.json('/api/jobs'),{code:'CC_SESSION_EXPIRED'});
    assert.equal(f.calls.length,3);assert.equal(f.expirations.length,1);
});

for(const kind of ['json','blob']) test(`a late ${kind} body is discarded after session expiry`,async()=>{
    const body=deferred(),reading=deferred();
    const f=await fixture((url,init,index)=>{
        if(index===0)return reply({token:A});
        if(index===1)return {ok:true,[kind]:()=>{reading.resolve();return body.promise;}};
        return expired();
    });
    await f.client.json('/api/bootstrap');
    const old=assert.rejects(f.client[kind]('/api/slow'),{code:'CC_SESSION_EXPIRED'});
    await reading.promise;await assert.rejects(f.client.json('/api/health'),{status:401});
    body.resolve(kind==='json'?{ok:true}:new Blob(['private']));await old;
    assert.equal(f.expirations.length,1);
});

test('session-expiry callbacks and later blocked calls honor the UI language',async()=>{
    const f=await fixture((url,init,index)=>index===0?reply({token:A}):expired(),{getLanguage:()=> 'en'});
    await f.client.json('/api/bootstrap');await assert.rejects(f.client.json('/api/health'));
    assert.match(f.expirations[0].message,/Panel session expired/);
    await assert.rejects(f.client.json('/api/jobs'),/Panel session expired/);
});

test('separate client instances never share or clear each other session credentials',async()=>{
    const first=await fixture((url,init,index)=>index===0?reply({token:A}):expired());
    const second=await fixture((url,init,index)=>index===0?reply({token:B}):reply({ok:true}));
    await Promise.all([first.client.json('/api/bootstrap'),second.client.json('/api/bootstrap')]);
    await assert.rejects(first.client.json('/api/health'));await second.client.json('/api/health');
    assert.equal(second.calls[1].init.headers.get('X-Control-Center-Token'),B);assert.equal(second.expirations.length,0);
});
