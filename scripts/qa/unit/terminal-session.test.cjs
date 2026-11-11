const {test,before} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
let createTerminalSession;
before(async () => ({createTerminalSession} = await import('data:text/javascript;base64,'+Buffer.from(fs.readFileSync('scripts/control-center/web/terminal-session.js','utf8')).toString('base64'))));
const defer = () => {let resolve;const promise = new Promise(r => {resolve=r;});return {promise,resolve};};
function fixture(request) {
  const calls=[], output=[], states=[], accepted=[], errors=[], timers=new Map();let next=0;
  const client=createTerminalSession({request:(...args)=>{calls.push(args);return request(...args);},
    onOutput:v=>output.push(v),onState:v=>states.push(v),onAccepted:v=>accepted.push(v),onError:e=>errors.push(e.message),
    setTimer:fn=>{timers.set(++next,fn);return next;},clearTimer:key=>timers.delete(key)});
  return {client,calls,output,states,accepted,errors,timers};
}
test('a double start creates one terminal and one polling timer',async()=>{
  const held=defer();const f=fixture((path,opts)=>opts?.method==='POST'?held.promise:Promise.resolve({id:'a',alive:true,output:'safe'}));
  const a=f.client.start();assert.equal(await f.client.start(),false);held.resolve({id:'a'});await a;
  assert.equal(f.calls.filter(c=>c[1]?.method==='POST').length,1);assert.equal(f.timers.size,1);
});
test('concurrent reads share transport and stop polling for a dead terminal',async()=>{
  const held=defer();const f=fixture((path,opts)=>opts?.method==='POST'?Promise.resolve({id:'a'}):held.promise);
  const started=f.client.start();await Promise.resolve();await Promise.resolve();
  const a=f.client.refresh(),b=f.client.refresh();assert.equal(a,b);
  held.resolve({id:'a',alive:false,output:'done'});await started;await a;
  assert.equal(f.timers.size,0);assert.equal(await f.client.send('command\n'),false);
});
test('switching terminals cancels old reads and ignores a late non-cooperative response',async()=>{
  const old=defer();let created=0;
  const f=fixture((path,opts)=>opts?.method==='POST'?Promise.resolve({id:String(++created)}):path.endsWith('/1')?old.promise:Promise.resolve({id:'2',alive:true,output:'new'}));
  const first=f.client.start();await Promise.resolve();await Promise.resolve();const oldCall=f.calls.find(c=>c[0].endsWith('/1'));
  await f.client.start();assert.equal(oldCall[1].signal.aborted,true);old.resolve({id:'1',alive:true,output:'old'});await first;
  assert.deepEqual(f.output,['new']);assert.equal(f.timers.size,1);
});
test('input is posted once, a successful acknowledgment is reported and reads never repeat it',async()=>{
  const held=defer();const f=fixture((path,opts)=>path.endsWith('/input')?held.promise:opts?.method==='POST'?Promise.resolve({id:'a'}):Promise.resolve({id:'a',alive:true,output:'safe'}));
  await f.client.start();const sent=f.client.send('first\n');assert.equal(await f.client.send('second\n'),false);
  held.resolve({ok:true});await sent;await f.client.refresh();
  assert.equal(f.calls.filter(c=>c[0].endsWith('/input')).length,1);assert.deepEqual(f.accepted,['first\n']);
});
test('a failed input preserves the draft by withholding acknowledgment and never retries',async()=>{
  const f=fixture(async(path,opts)=>{if(path.endsWith('/input'))throw Error('lost acknowledgment');return opts?.method==='POST'?{id:'a'}:{id:'a',alive:true};});
  await f.client.start();assert.equal(await f.client.send('command\n'),false);
  assert.deepEqual(f.accepted,[]);assert.equal(f.calls.filter(c=>c[0].endsWith('/input')).length,1);
});
test('session disposal cancels reads and permanently blocks start and input',async()=>{
  const held=defer();const f=fixture((path,opts)=>opts?.method==='POST'?Promise.resolve({id:'a'}):held.promise);
  const started=f.client.start();await Promise.resolve();await Promise.resolve();f.client.dispose();
  held.resolve({id:'a',alive:true,output:'late'});await started;
  assert.equal(await f.client.start(),false);assert.equal(await f.client.send('bad\n'),false);assert.deepEqual(f.output,[]);
  assert.equal(f.timers.size,0);assert.equal(f.states.at(-1).disposed,true);
});
test('a late input acknowledgment after terminal replacement cannot clear the new draft',async()=>{
  const held=defer();let created=0;
  const f=fixture((path,opts)=>path.endsWith('/input')?held.promise:opts?.method==='POST'?Promise.resolve({id:String(++created)}):Promise.resolve({id:String(created),alive:true}));
  await f.client.start();const write=f.client.send('old\n');await f.client.start();held.resolve({ok:true});await write;
  assert.deepEqual(f.accepted,[]);assert.equal(f.states.at(-1).alive,true);
});
test('failed replacement keeps the existing terminal available',async()=>{
  let creates=0;const f=fixture(async(path,opts)=>{if(opts?.method==='POST'){if(++creates>1)throw Error('limit');return {id:'a'};}return {id:'a',alive:true,output:'kept'};});
  await f.client.start();assert.equal(await f.client.start(),false);await Promise.resolve();await Promise.resolve();
  assert.equal(f.states.at(-1).alive,true);assert.deepEqual(f.errors,['limit']);
});
