const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../../frontend/passkey-codec.js'), 'utf8');
const modulePromise = import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const bytes = value => [...new Uint8Array(value)];
const buffer = values => Uint8Array.from(values).buffer;

test('base64url round trips empty data, padding boundaries and every byte value', async () => {
    const {encodeBase64Url: encode, decodeBase64Url: decode} = await modulePromise;
    for (const value of [new Uint8Array(), Uint8Array.of(255), Uint8Array.of(0, 255), Uint8Array.from({length: 256}, (_, i) => i)]) {
        const expected = Buffer.from(value).toString('base64url');
        assert.equal(encode(value), expected); assert.deepEqual(bytes(decode(expected)), [...value]);
    }
    assert.deepEqual(bytes(decode('AAEC/w==')), [0, 1, 2, 255]);
});

test('a one MiB credential buffer encodes exactly without a spread argument RangeError', async () => {
    const {encodeBase64Url, decodeBase64Url} = await modulePromise;
    const value = Uint8Array.from({length: 1024 * 1024}, (_, i) => i % 256);
    const encoded = encodeBase64Url(value.buffer);
    assert.equal(encoded, Buffer.from(value).toString('base64url'));
    assert.deepEqual(Buffer.from(decodeBase64Url(encoded)), Buffer.from(value));
});

test('typed array and DataView offsets do not expose bytes outside the credential slice', async () => {
    const {encodeBase64Url} = await modulePromise;
    const value = Uint8Array.of(99, 1, 2, 255, 88);
    for (const view of [value.subarray(1, 4), new DataView(value.buffer, 1, 3)]) {
        assert.equal(encodeBase64Url(view), Buffer.from([1, 2, 255]).toString('base64url'));
    }
    assert.deepEqual([...value], [99, 1, 2, 255, 88]);
});

test('non-buffer values and malformed base64 fail rather than emitting empty credential data', async () => {
    const {encodeBase64Url, decodeBase64Url} = await modulePromise;
    for (const value of [null, undefined, 'credential', [], {}, 10]) assert.throws(() => encodeBase64Url(value), TypeError);
    assert.throws(() => decodeBase64Url('not!base64'));
});

test('authentication options preserve RP, timeout, verification and exact credential bytes', async () => {
    const {toAuthenticationOptions} = await modulePromise;
    const result = toAuthenticationOptions({challenge: 'AAEC_w', rpId: 'localhost', timeout: 60000, allowCredentials: ['AQID'], userVerification: 'preferred'});
    assert.deepEqual(bytes(result.challenge), [0, 1, 2, 255]); assert.equal(result.rpId, 'localhost'); assert.equal(result.timeout, 60000);
    assert.equal(result.userVerification, 'preferred'); assert.equal(result.allowCredentials[0].type, 'public-key');
    assert.deepEqual(bytes(result.allowCredentials[0].id), [1, 2, 3]);
});

test('authentication defaults keep required verification and an empty allow-list', async () => {
    const {toAuthenticationOptions} = await modulePromise;
    const result = toAuthenticationOptions({challenge: 'AQID'});
    assert.equal(result.userVerification, 'required'); assert.deepEqual(result.allowCredentials, []);
});

test('registration maps existing algorithms and binary user/exclusion fields without mutating the wire options', async () => {
    const {toRegistrationOptions} = await modulePromise;
    const input = {challenge: 'AAEC_w', rpId: 'localhost', rpName: 'Fixture', timeout: 60000,
        user: {id: 'Bwg', name: 'fixture@example.test', displayName: 'Fixture'}, pubKeyCredParams: ['ES256', 'RS256', '-8'], excludeCredentials: ['AQID']};
    const before = JSON.stringify(input), result = toRegistrationOptions(input);
    assert.deepEqual(result.rp, {id: 'localhost', name: 'Fixture'}); assert.deepEqual(bytes(result.user.id), [7, 8]);
    assert.deepEqual(result.pubKeyCredParams, [{type: 'public-key', alg: -7}, {type: 'public-key', alg: -257}, {type: 'public-key', alg: -8}]);
    assert.deepEqual(bytes(result.excludeCredentials[0].id), [1, 2, 3]); assert.equal(result.attestation, 'none');
    assert.equal(result.authenticatorSelection.userVerification, 'preferred'); assert.equal(JSON.stringify(input), before);
});

test('registration keeps the supplied verification and safely defaults an absent exclusion list', async () => {
    const {toRegistrationOptions} = await modulePromise;
    const result = toRegistrationOptions({challenge: 'AQID', user: {id: 'AQID'}, pubKeyCredParams: [-7], userVerification: 'required'});
    assert.deepEqual(result.excludeCredentials, []); assert.equal(result.authenticatorSelection.userVerification, 'required');
    assert.equal(result.pubKeyCredParams[0].alg, -7);
});

test('assertion serialization binds the explicit challenge and includes only the required binary response fields', async () => {
    const {serializeAssertion} = await modulePromise;
    const result = serializeAssertion('challenge-fixture', {id: 'AQID', type: 'public-key', rawId: buffer([1, 2, 3]), unrelated: 'not transmitted', response: {
        clientDataJSON: buffer([4, 5]), authenticatorData: buffer([6, 7]), signature: buffer([8, 9]), userHandle: buffer([10, 11])}});
    assert.deepEqual(result, {challengeId: 'challenge-fixture', id: 'AQID', type: 'public-key', rawId: 'AQID', response: {
        clientDataJSON: 'BAU', authenticatorData: 'Bgc', signature: 'CAk', userHandle: 'Cgs'}});
});

test('assertions preserve null or empty user handles and attestation keeps the original bytes', async () => {
    const {serializeAssertion, serializeAttestation} = await modulePromise;
    const credential = {id: 'AQID', type: 'public-key', rawId: buffer([1, 2, 3]), response: {
        clientDataJSON: buffer([4, 5]), authenticatorData: buffer([6, 7]), signature: buffer([8, 9]), userHandle: null, attestationObject: buffer([255, 0, 128])}};
    assert.equal(serializeAssertion('fixture', credential).response.userHandle, null);
    credential.response.userHandle = new ArrayBuffer(0);
    assert.equal(serializeAssertion('fixture', credential).response.userHandle, '');
    assert.deepEqual(serializeAttestation(credential), {id: 'AQID', type: 'public-key', rawId: 'AQID', response: {clientDataJSON: 'BAU', attestationObject: '_wCA'}});
});

test('missing authenticator results have an explicit error', async () => {
    const {serializeAssertion, serializeAttestation} = await modulePromise;
    for (const value of [null, undefined, {}]) {
        assert.throws(() => serializeAssertion('fixture', value), /Nie otrzymano poświadczenia/);
        assert.throws(() => serializeAttestation(value), /Nie otrzymano poświadczenia/);
    }
});

test('the existing localhost port guard is preserved while other origins stay subject to native WebAuthn checks', async () => {
    const {assertWebAuthnHost} = await modulePromise;
    assert.doesNotThrow(() => assertWebAuthnHost({hostname: 'LOCALHOST', port: '18070'}, 'localhost'));
    assert.throws(() => assertWebAuthnHost({hostname: '127.0.0.1', port: '18070'}, 'localhost'), /http:\/\/localhost:18070/);
    assert.doesNotThrow(() => assertWebAuthnHost({hostname: '127.0.0.1', port: '18756'}, 'localhost'));
    assert.doesNotThrow(() => assertWebAuthnHost({hostname: 'store.example', port: ''}, null));
});
