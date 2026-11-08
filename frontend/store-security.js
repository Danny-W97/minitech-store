import {
    assertWebAuthnHost, serializeAssertion, serializeAttestation, toAuthenticationOptions,
    toRegistrationOptions,
} from './passkey-codec.js?v=20261027-1';
import { finishLogin } from './store-account.js?v=20261028-a43-32';
import {
    authForgotPassword, authForm, authLogout, authMfaCode, authMfaCodeLabel, authMfaHelp, authMfaPanel,
    authMfaPasskey, authMfaStatus, authMfaSubmit, authMfaSummary, authModal, authModeSelector,
    authOrdersPanel, authPasskeyRegister, authProfilePanel, authResetForm, authSecurityPanel,
    authSecurityStatus, authSubmit, authTotpCode, authTotpCodeLabel, authTotpConfirm, authTotpDisable,
    authTotpDisableCode, authTotpDisableConfirm, authTotpDisablePanel, authTotpQr, authTotpSecret,
    authTotpSecretLabel, authTotpSetup, authTotpStart, dialogs,
} from './store-dom.js?v=20261028-a43-32';
import { api } from './store-services.js?v=20261028-a43-32';
import { runtime, state } from './store-state.js?v=20261028-a43-32';

export function showMfaChallenge(challenge) {
    state.mfaChallenge = challenge;
    authForm.hidden = true;
    authModeSelector.hidden = true;
    authForgotPassword.hidden = true;
    authResetForm.hidden = true;
    authSubmit.hidden = true;
    authLogout.hidden = true;
    authMfaPanel.hidden = false;
    authSecurityPanel.hidden = true;
    authProfilePanel.hidden = true;
    authOrdersPanel.hidden = true;
    authMfaCode.value = '';
    const passkey = challenge.method === 'webauthn';
    authMfaCodeLabel.hidden = passkey;
    authMfaSubmit.hidden = passkey;
    authMfaPasskey.hidden = !passkey;
    authMfaHelp.textContent = passkey
        ? 'Potwierdź logowanie za pomocą zarejestrowanego passkey.'
        : 'Wpisz kod z aplikacji uwierzytelniającej albo użyj kodu odzyskiwania.';
    authMfaStatus.textContent = `Weryfikacja ważna ${challenge.expiresIn || 300} sekund.`;
    dialogs.open(authModal, passkey ? '#auth-mfa-passkey' : '#auth-mfa-code');
    if (passkey) {
        startWebAuthnLogin();
    }
}

export async function submitMfaLogin() {
    if (!state.mfaChallenge || authMfaSubmit.disabled) return;
    const code = authMfaCode.value.trim();
    if (!code || !authMfaCode.checkValidity()) {
        authMfaCode.reportValidity();
        return;
    }
    authMfaSubmit.disabled = true;
    authMfaStatus.textContent = 'Sprawdzanie kodu...';
    try {
        const session = await api('/customers/mfa/login/verify', {
            method: 'POST',
            body: JSON.stringify({ challengeId: state.mfaChallenge.challengeId, code })
        }, false);
        finishLogin(session);
    } catch (error) {
        authMfaStatus.textContent = `Nie udało się potwierdzić kodu: ${error.message}`;
        authMfaStatus.style.color = '#bd331d';
    } finally {
        authMfaSubmit.disabled = false;
    }
}

export async function startWebAuthnLogin() {
    if (!state.mfaChallenge) return;
    const challengeId = state.mfaChallenge.challengeId;
    authMfaPasskey.disabled = true;
    authMfaStatus.textContent = 'Oczekiwanie na potwierdzenie passkey...';
    try {
        if (!window.PublicKeyCredential || !navigator.credentials) {
            throw new Error('Ta przeglądarka nie obsługuje WebAuthn.');
        }
        const options = await api(`/customers/mfa/webauthn/login/options?challengeId=${encodeURIComponent(challengeId)}`, {}, false);
        if (state.mfaChallenge?.challengeId !== challengeId) return;
        assertWebAuthnHost(window.location, options.rpId);
        const credential = await navigator.credentials.get({publicKey: toAuthenticationOptions(options)});
        if (state.mfaChallenge?.challengeId !== challengeId) return;
        const session = await api('/customers/mfa/webauthn/login/verify', {
            method: 'POST',
            body: JSON.stringify(serializeAssertion(challengeId, credential))
        }, false);
        if (state.mfaChallenge?.challengeId !== challengeId) return;
        finishLogin(session);
    } catch (error) {
        if (state.mfaChallenge?.challengeId !== challengeId) return;
        authMfaStatus.textContent = `Nie udało się użyć passkey: ${error.message}`;
        authMfaStatus.style.color = '#bd331d';
    } finally {
        if (!state.mfaChallenge || state.mfaChallenge.challengeId === challengeId) authMfaPasskey.disabled = false;
    }
}

export async function loadMfaStatus() {
    authMfaSummary.textContent = 'Sprawdzanie konfiguracji MFA...';
    try {
        const status = await api('/customers/mfa/status');
        authMfaSummary.textContent = `TOTP: ${status.totpEnabled ? 'włączone' : 'wyłączone'} · passkey: ${status.webAuthnAvailable ? 'zarejestrowany' : 'brak'}.`;
        authTotpStart.hidden = status.totpEnabled;
        authTotpDisable.hidden = !status.totpEnabled;
        if (!status.totpEnabled) {
            authTotpDisablePanel.hidden = true;
            authTotpDisableCode.value = '';
        }
        authPasskeyRegister.disabled = !window.PublicKeyCredential;
    } catch (error) {
        authMfaSummary.textContent = `Nie udało się pobrać statusu MFA: ${error.message}`;
    }
}

export async function beginTotpEnrollment() {
    authTotpStart.disabled = true;
    authSecurityStatus.textContent = 'Generowanie klucza TOTP...';
    try {
        runtime.mfaEnrollment = await api('/customers/mfa/totp/enroll', { method: 'POST' });
        authTotpQr.src = runtime.mfaEnrollment.qrCodeDataUri;
        authTotpQr.alt = 'Kod QR do skonfigurowania aplikacji TOTP';
        authTotpSetup.hidden = false;
        authTotpSecret.value = runtime.mfaEnrollment.secret;
        authTotpSecretLabel.hidden = false;
        authTotpCodeLabel.hidden = false;
        authTotpConfirm.hidden = false;
        authSecurityStatus.textContent = 'Dodaj klucz do aplikacji Authenticator i wpisz aktualny kod.';
    } catch (error) {
        authSecurityStatus.textContent = `Nie udało się rozpocząć TOTP: ${error.message}`;
        authSecurityStatus.style.color = '#bd331d';
        authTotpStart.disabled = false;
    }
}

export async function confirmTotpEnrollment() {
    const code = authTotpCode.value.trim();
    if (!/^\d{6}$/.test(code)) {
        authSecurityStatus.textContent = 'Kod TOTP musi zawierać dokładnie 6 cyfr.';
        authSecurityStatus.style.color = '#bd331d';
        authTotpCode.focus();
        return;
    }
    authTotpConfirm.disabled = true;
    try {
        const recovery = await api('/customers/mfa/totp/confirm', {
            method: 'POST',
            body: JSON.stringify({ code })
        });
        authSecurityStatus.textContent = `TOTP włączone. Kody odzyskiwania: ${recovery.codes.join(', ')}. Zapisz je poza aplikacją.`;
        authTotpSetup.hidden = true;
        authTotpSecretLabel.hidden = true;
        authTotpCodeLabel.hidden = true;
        authTotpConfirm.hidden = true;
        authTotpStart.hidden = true;
        await loadMfaStatus();
    } catch (error) {
        if (error.status === 410) {
            runtime.mfaEnrollment = null;
            authTotpQr.removeAttribute('src');
            authTotpSecret.value = '';
            authTotpCode.value = '';
            authTotpSetup.hidden = true;
            authTotpSecretLabel.hidden = true;
            authTotpCodeLabel.hidden = true;
            authTotpConfirm.hidden = true;
            authTotpStart.disabled = false;
            authSecurityStatus.textContent = 'Konfiguracja TOTP wygasła. Wybierz „Włącz TOTP”, aby rozpocząć ponownie.';
        } else {
            authSecurityStatus.textContent = error.status === 401
                ? 'Nieprawidłowy kod TOTP. Wpisz aktualny kod z aplikacji Authenticator.'
                : `Nie udało się potwierdzić TOTP: ${error.message}`;
        }
        authSecurityStatus.style.color = '#bd331d';
    } finally {
        authTotpConfirm.disabled = false;
    }
}

export async function disableTotp() {
    const code = authTotpDisableCode.value.trim();
    if (!/^\d{6}$/.test(code)) {
        authSecurityStatus.textContent = 'Kod TOTP musi zawierać dokładnie 6 cyfr.';
        authSecurityStatus.style.color = '#bd331d';
        authTotpDisableCode.focus();
        return;
    }
    authTotpDisableConfirm.disabled = true;
    authSecurityStatus.textContent = 'Wyłączanie TOTP...';
    try {
        await api('/customers/mfa/totp/disable', {
            method: 'POST',
            body: JSON.stringify({ code })
        });
        authTotpDisableCode.value = '';
        authTotpDisablePanel.hidden = true;
        authSecurityStatus.textContent = 'TOTP zostało wyłączone.';
        authSecurityStatus.style.color = '#087d6e';
        await loadMfaStatus();
    } catch (error) {
        authSecurityStatus.textContent = error.status === 401
            ? 'Nieprawidłowy lub wygasły kod TOTP. Wpisz aktualny kod z aplikacji Authenticator.'
            : `Nie udało się wyłączyć TOTP: ${error.message}`;
        authSecurityStatus.style.color = '#bd331d';
    } finally {
        authTotpDisableConfirm.disabled = false;
    }
}

export async function registerPasskey() {
    if (!state.customer) return;
    const customerId = state.customer.id;
    authPasskeyRegister.disabled = true;
    authSecurityStatus.textContent = 'Oczekiwanie na rejestrację passkey...';
    try {
        if (!window.PublicKeyCredential || !navigator.credentials) {
            throw new Error('Ta przeglądarka nie obsługuje WebAuthn.');
        }
        const options = await api('/customers/mfa/webauthn/register/options');
        if (state.customer?.id !== customerId) return;
        assertWebAuthnHost(window.location, options.rpId);
        const credential = await navigator.credentials.create({publicKey: toRegistrationOptions(options)});
        if (state.customer?.id !== customerId) return;
        await api('/customers/mfa/webauthn/register?label=Browser%20passkey', {
            method: 'POST',
            body: JSON.stringify(serializeAttestation(credential))
        });
        if (state.customer?.id !== customerId) return;
        authSecurityStatus.textContent = 'Passkey został dodany do konta.';
        await loadMfaStatus();
    } catch (error) {
        if (state.customer?.id !== customerId) return;
        authSecurityStatus.textContent = `Nie udało się dodać passkey: ${error.message}`;
        authSecurityStatus.style.color = '#bd331d';
    } finally {
        if (!state.customer || state.customer.id === customerId) authPasskeyRegister.disabled = false;
    }
}
