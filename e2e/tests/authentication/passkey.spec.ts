import { test, expect, CUSTOMER } from '../../fixtures/store.fixture';
import type { Page } from '@playwright/test';

async function authenticator(page: Page, settings = {rejectCreate: false, holdGet: false}) {
  await page.addInitScript(settings => {
    const state = {getCalls: 0, getOptions: [] as unknown[], createOptions: null as unknown, releases: [] as Array<() => void>};
    Object.assign(window, {passkeyFixture: state});
    if (!window.PublicKeyCredential) Object.defineProperty(window, 'PublicKeyCredential', {configurable: true, value: class {}});
    const credential = (index: number) => ({id: `fixture-credential-${index}`, type: 'public-key', rawId: Uint8Array.of(1, 2, 3).buffer,
      response: {clientDataJSON: Uint8Array.of(4, 5).buffer, authenticatorData: Uint8Array.of(6, 7).buffer,
        signature: Uint8Array.of(8, 9).buffer, userHandle: Uint8Array.of(10, 11).buffer}});
    Object.defineProperty(navigator, 'credentials', {configurable: true, value: {
      async get({publicKey}: any) {
        const index = ++state.getCalls;
        state.getOptions.push({challenge: [...new Uint8Array(publicKey.challenge)], rpId: publicKey.rpId, timeout: publicKey.timeout,
          allow: publicKey.allowCredentials.map((item: any) => [...new Uint8Array(item.id)]), verification: publicKey.userVerification});
        if (settings.holdGet) await new Promise<void>(resolve => {state.releases[index - 1] = resolve;});
        return credential(index);
      },
      async create({publicKey}: any) {
        state.createOptions = {challenge: [...new Uint8Array(publicKey.challenge)], userId: [...new Uint8Array(publicKey.user.id)],
          rp: publicKey.rp, algorithms: publicKey.pubKeyCredParams.map((item: any) => item.alg),
          excluded: publicKey.excludeCredentials.map((item: any) => [...new Uint8Array(item.id)]),
          verification: publicKey.authenticatorSelection.userVerification, attestation: publicKey.attestation};
        if (settings.rejectCreate) throw new DOMException('Fixture device cancelled', 'NotAllowedError');
        return {id: 'fixture-registration', type: 'public-key', rawId: Uint8Array.of(1, 2, 3).buffer, response: {
          clientDataJSON: Uint8Array.of(4, 5).buffer,
          attestationObject: Uint8Array.from({length: 1024 * 1024}, (_, index) => index % 256).buffer}};
      }
    }});
  }, settings);
}

async function api(page: Page, options = {login: false, denyProof: false}) {
  const state = {proofs: [] as any[], registrations: [] as any[], challenges: 0, refreshes: 0};
  page.on('request', request => {if (request.url().endsWith('/customers/refresh')) state.refreshes++;});
  if (options.login) await page.route('**/customers/login', route => route.fulfill({json: {
    mfaRequired: true, challengeId: `fixture-challenge-${++state.challenges}`, method: 'webauthn', expiresIn: 300}}));
  await page.route('**/customers/mfa/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/status')) return route.fulfill({json: {totpEnabled: false, webAuthnAvailable: state.registrations.length > 0, recoveryCodesRemaining: 0}});
    if (path.endsWith('/register/options')) return route.fulfill({json: {challenge: 'AAEC_w', rpId: '127.0.0.1', rpName: 'Fixture Store',
      user: {id: 'Bwg', name: CUSTOMER.email, displayName: 'Fixture User'}, timeout: 60000, pubKeyCredParams: ['ES256', 'RS256'], excludeCredentials: ['AQID']}});
    if (path.endsWith('/login/options')) return route.fulfill({json: {challenge: 'AAEC_w', rpId: '127.0.0.1', timeout: 60000, allowCredentials: ['AQID']}});
    if (path.endsWith('/login/verify')) {
      state.proofs.push(route.request().postDataJSON());
      return options.denyProof ? route.fulfill({status: 401, json: {message: 'Fixture invalid proof', details: {code: 'MFA_INVALID_PROOF'}}})
        : route.fulfill({json: {accessToken: 'fixture-passkey-access', customer: CUSTOMER}});
    }
    if (path.endsWith('/register') && route.request().method() === 'POST') {
      state.registrations.push(route.request().postDataJSON());
      return route.fulfill({json: {credentialId: 'fixture-registration', label: 'Browser passkey'}});
    }
    return route.fulfill({status: 500, json: {message: 'Unexpected fixture path'}});
  });
  return state;
}

async function login(page: Page) {
  await page.locator('#auth-toggle').click();
  await page.locator('#auth-email').fill(CUSTOMER.email); await page.locator('#auth-password').fill('Playwright1!');
  await page.locator('#auth-submit').click();
}

async function security(page: Page) {
  await login(page); await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
  await expect(page.locator('#auth-modal')).toBeHidden();
  await page.locator('#auth-toggle').click(); await page.locator('#account-menu-security').click();
  await expect(page.locator('#auth-passkey-register')).toBeEnabled();
}

test('passkey registration maps server options and preserves a one MiB attestation buffer', async ({page, mockStore}) => {
  void mockStore; await authenticator(page); const state = await api(page);
  await page.goto('/index.html'); await security(page); await page.locator('#auth-passkey-register').click();
  await expect(page.locator('#auth-security-status')).toContainText('Passkey został dodany');
  expect(state.registrations).toHaveLength(1);
  const registered = state.registrations[0];
  expect(registered.rawId).toBe('AQID'); expect(registered.response.clientDataJSON).toBe('BAU');
  const expected = Buffer.from(Uint8Array.from({length: 1024 * 1024}, (_, i) => i % 256));
  expect(Buffer.from(registered.response.attestationObject, 'base64url').equals(expected)).toBe(true);
  expect(await page.evaluate(() => (window as any).passkeyFixture.createOptions)).toEqual({challenge: [0, 1, 2, 255], userId: [7, 8],
    rp: {id: '127.0.0.1', name: 'Fixture Store'}, algorithms: [-7, -257], excluded: [[1, 2, 3]], verification: 'preferred', attestation: 'none'});
});

test('passkey login submits the matching challenge and exact assertion bytes once', async ({page, mockStore}) => {
  void mockStore; await authenticator(page); const state = await api(page, {login: true, denyProof: false});
  await page.goto('/index.html'); await login(page); await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
  expect(state.proofs).toEqual([{challengeId: 'fixture-challenge-1', id: 'fixture-credential-1', type: 'public-key', rawId: 'AQID',
    response: {clientDataJSON: 'BAU', authenticatorData: 'Bgc', signature: 'CAk', userHandle: 'Cgs'}}]);
  expect(state.refreshes).toBe(0);
  expect(await page.evaluate(() => (window as any).passkeyFixture.getOptions)).toEqual([{challenge: [0, 1, 2, 255], rpId: '127.0.0.1',
    timeout: 60000, allow: [[1, 2, 3]], verification: 'required'}]);
});

test('cancelling authenticator registration restores the button without posting a credential', async ({page, mockStore}) => {
  void mockStore; await authenticator(page, {rejectCreate: true, holdGet: false}); const state = await api(page);
  await page.goto('/index.html'); await security(page); await page.locator('#auth-passkey-register').click();
  await expect(page.locator('#auth-security-status')).toContainText('Fixture device cancelled');
  await expect(page.locator('#auth-passkey-register')).toBeEnabled(); expect(state.registrations).toEqual([]);
});

test('a rejected passkey proof cannot trigger refresh or automatic proof resubmission', async ({page, mockStore}) => {
  void mockStore; await authenticator(page); const state = await api(page, {login: true, denyProof: true});
  await page.goto('/index.html'); await login(page);
  await expect(page.locator('#auth-mfa-status')).toContainText('Fixture invalid proof');
  await expect(page.locator('#auth-mfa-passkey')).toBeEnabled();
  expect(state.proofs).toHaveLength(1); expect(state.refreshes).toBe(0);
});

test('a late credential from a closed challenge cannot verify or unlock a newer login attempt', async ({page, mockStore}) => {
  void mockStore; await authenticator(page, {rejectCreate: false, holdGet: true}); const state = await api(page, {login: true, denyProof: false});
  await page.goto('/index.html'); await login(page);
  await expect.poll(() => page.evaluate(() => (window as any).passkeyFixture.getCalls)).toBe(1);
  await page.locator('#auth-close').click(); await login(page);
  await expect.poll(() => page.evaluate(() => (window as any).passkeyFixture.getCalls)).toBe(2);
  await page.evaluate(async () => {(window as any).passkeyFixture.releases[0](); await Promise.resolve(); await Promise.resolve();});
  await expect(page.locator('#auth-mfa-passkey')).toBeDisabled(); expect(state.proofs).toEqual([]);
  await page.evaluate(() => (window as any).passkeyFixture.releases[1]());
  await expect(page.locator('#auth-toggle')).toContainText(CUSTOMER.email);
  expect(state.proofs).toHaveLength(1); expect(state.proofs[0].challengeId).toBe('fixture-challenge-2');
  expect(state.proofs[0].id).toBe('fixture-credential-2');
});
