const {test, before} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('scripts/control-center/web/panel-invoices.js', 'utf8').replace(/^export /gm, '');
const functions = source.slice(source.indexOf('async function openCorrectionModal('), source.indexOf('function openDispatchModal('));
let intentsModule;
before(async () => { intentsModule = await import('data:text/javascript;base64,' + Buffer.from(fs.readFileSync('scripts/control-center/web/operator-intents.js', 'utf8')).toString('base64')); });
function fixture(shared = new Map(), locks = new Map()) {
  let next = 0;
  const elements = new Map();
  for (const id of ['correction-dialog','correction-invoice-id','correction-orig-gross','correction-submit-btn','correction-dialog-title','correction-dialog-subtitle','correction-reason','correction-delta','correction-error','calc-orig-text','calc-delta-text','calc-final-text'])
    elements.set('#'+id, {value:'', textContent:'', dataset:{}, style:{}, dispatchEvent(){}});
  const dialog=elements.get('#correction-dialog');dialog.showModal=()=>{dialog.open=true;};
  const context=vm.createContext({state:{profile:'dev',language:'pl'},
    crypto:{randomUUID:()=>`00000000-0000-4000-8000-${String(++next).padStart(12,'0')}`},
    navigator:{locks:{request: async (key, work)=>{
      const before=locks.get(key)||Promise.resolve();const current=before.then(work);locks.set(key,current.catch(()=>{}));return current;
    }}},
    localStorage:{getItem:k=>shared.get(k)||null,setItem:(k,v)=>shared.set(k,v),removeItem:k=>shared.delete(k)},
    $:selector=>elements.get(selector),Event:class {},api:async()=>({version:2,grossAmount:'70.00'})});
  Object.assign(context, intentsModule.createOperatorIntents({getProfile:()=>context.state.profile,
    storage:()=>context.localStorage,locks:()=>context.navigator.locks,randomUUID:()=>context.crypto.randomUUID()}),
    {correctionAmount:intentsModule.correctionAmount});
  vm.runInContext(functions,context);
  return {context,shared,elements};
}
test('correction modal uses the current server balance instead of the original invoice amount',async()=>{
  const {context,elements}=fixture();await context.openCorrectionModal('1','FV/1',100);
  assert.equal(elements.get('#correction-orig-gross').value,'70.00');
  assert.equal(elements.get('#correction-dialog').dataset.balanceVersion,'2');
  assert.equal(elements.get('#correction-submit-btn').disabled,false);
});
test('an uncertain correction survives reload and restores its immutable command',async()=>{
  const first=fixture();const intent=await first.context.correctionIntent('1','Rabat','-10.00',0,'100.00');
  const reload=fixture(first.shared);reload.context.api=()=>{throw Error('must not replace an uncertain command');};
  await reload.context.openCorrectionModal('1','FV/1',999);
  assert.equal(reload.elements.get('#correction-orig-gross').value,'100.00');
  assert.equal(reload.elements.get('#correction-reason').readOnly,true);
  assert.equal(reload.elements.get('#correction-delta').value,'-10.00');
  const retried=await reload.context.correctionIntent('1','Rabat','-10.00',0,'100.00');
  assert.equal(retried.idempotencyKey,intent.idempotencyKey);
});
test('parallel clicks share one key and a changed payload cannot replace an unresolved operation',async()=>{
  const {context}=fixture();const [a,b]=await Promise.all([context.correctionIntent('1','Rabat','-10.00',0,'100'),context.correctionIntent('1','Rabat','-10.00',0,'100')]);
  assert.equal(a.idempotencyKey,b.idempotencyKey);
  await assert.rejects(context.correctionIntent('1','Rabat','-20.00',0,'100'),/Najpierw ponów/);
  await assert.rejects(context.correctionIntent('1','Rabat','-10.00',1,'90'),/Najpierw ponów/);
  await context.finishCorrectionIntent(a);assert.equal(context.readPendingCorrection('1'),null);
});
test('an unavailable balance or local operation store blocks issuing a new correction',async()=>{
  const {context,elements}=fixture();context.api=async()=>{throw Error('offline');};
  await context.openCorrectionModal('1','FV/1',100);
  assert.equal(elements.get('#correction-submit-btn').disabled,true);
  context.localStorage.setItem=()=>{throw Error('storage blocked');};
  await assert.rejects(context.correctionIntent('1','Rabat','-10.00',0,'100'),/storage blocked/);
});
test('money is normalized without a floating point conversion and invalid precision is refused',()=>{
  const {context}=fixture();assert.equal(context.correctionAmount('-99999999999999999.99'),'-99999999999999999.99');
  assert.equal(context.correctionAmount('01.2'),'1.20');
  for(const value of ['0','-0.00','1.001','NaN','1e3']) assert.throws(()=>context.correctionAmount(value));
});
