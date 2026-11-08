const {test,before} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
let canonicalCheckoutIntent,checkoutAttemptFingerprintMatches;
before(async()=>({canonicalCheckoutIntent,checkoutAttemptFingerprintMatches}=await import('data:text/javascript;base64,'+Buffer.from(fs.readFileSync('frontend/checkout-intent.js','utf8')).toString('base64'))));
test('canonical purchase identity is independent of quote cost, property order and cart order',()=>{
    const a={cart:[{id:2,quantity:3},{id:1,quantity:1}],address:{city:'A',street:'B'},deliveryCost:10};
    const b={deliveryCost:20,address:{street:'B',city:'A'},cart:[{quantity:1,id:1},{quantity:3,id:2}]};
    assert.equal(canonicalCheckoutIntent(a),canonicalCheckoutIntent(b));assert.equal(a.cart[0].id,2);
});
test('another owner, address or quantity cannot reuse the same purchase fingerprint',()=>{
    const value={cart:[{id:1,quantity:1}],customerId:4,address:{city:'A'}};
    const expected=canonicalCheckoutIntent(value);
    for(const change of [{customerId:5},{cart:[{id:1,quantity:2}]},{address:{city:'B'}}])
        assert.equal(checkoutAttemptFingerprintMatches({fingerprint:JSON.stringify({...value,...change})},expected),false);
    assert.equal(checkoutAttemptFingerprintMatches({fingerprint:'{'},expected),false);
});
