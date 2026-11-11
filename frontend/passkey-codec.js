/** WebAuthn wire conversion. No HTTP, storage, DOM or authenticator invocation. */
export function decodeBase64Url(value) {
    const normalized = value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - value.length % 4) % 4);
    return Uint8Array.from(atob(normalized), character => character.charCodeAt(0)).buffer;
}

export function encodeBase64Url(value) {
    let bytes;
    if (value instanceof ArrayBuffer) bytes = new Uint8Array(value);
    else if (ArrayBuffer.isView(value)) bytes = new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    else throw new TypeError('Oczekiwano bufora danych passkey.');
    const chunks = [];
    for (let offset = 0; offset < bytes.length; offset += 16384) {
        chunks.push(String.fromCharCode(...bytes.subarray(offset, offset + 16384)));
    }
    return btoa(chunks.join('')).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function toAuthenticationOptions(options) {
    return {
        challenge: decodeBase64Url(options.challenge), timeout: options.timeout, rpId: options.rpId,
        allowCredentials: (options.allowCredentials || []).map(id => ({type: 'public-key', id: decodeBase64Url(id)})),
        userVerification: options.userVerification || 'required'
    };
}

export function toRegistrationOptions(options) {
    return {
        challenge: decodeBase64Url(options.challenge),
        rp: {id: options.rpId, name: options.rpName},
        user: {id: decodeBase64Url(options.user.id), name: options.user.name, displayName: options.user.displayName},
        pubKeyCredParams: options.pubKeyCredParams.map(alg => ({type: 'public-key', alg: ({ES256: -7, RS256: -257}[alg] ?? Number(alg))})),
        timeout: options.timeout,
        excludeCredentials: (options.excludeCredentials || []).map(id => ({type: 'public-key', id: decodeBase64Url(id)})),
        authenticatorSelection: {userVerification: options.userVerification || 'preferred'}, attestation: 'none'
    };
}

function requireCredential(credential) {
    if (!credential?.response) throw new Error('Nie otrzymano poświadczenia passkey.');
}

export function serializeAssertion(challengeId, credential) {
    requireCredential(credential);
    return {challengeId, id: credential.id, type: credential.type, rawId: encodeBase64Url(credential.rawId), response: {
        clientDataJSON: encodeBase64Url(credential.response.clientDataJSON),
        authenticatorData: encodeBase64Url(credential.response.authenticatorData),
        signature: encodeBase64Url(credential.response.signature),
        userHandle: credential.response.userHandle ? encodeBase64Url(credential.response.userHandle) : null
    }};
}

export function serializeAttestation(credential) {
    requireCredential(credential);
    return {id: credential.id, type: credential.type, rawId: encodeBase64Url(credential.rawId), response: {
        clientDataJSON: encodeBase64Url(credential.response.clientDataJSON),
        attestationObject: encodeBase64Url(credential.response.attestationObject)
    }};
}

export function assertWebAuthnHost(location, rpId) {
    if (location.port !== '18070' || !rpId) return;
    if ((location.hostname || '').toLowerCase() !== rpId.toLowerCase()) {
        throw new Error(`Otwórz sklep przez http://${rpId.toLowerCase()}:18070; obecny adres używa innej domeny.`);
    }
}
