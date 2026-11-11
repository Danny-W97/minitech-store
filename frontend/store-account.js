import { i18n } from './i18n.js?v=20261028-a43-32';
import { loadWishlist, renderWishlistPanel } from './store-account-data.js?v=20261028-a43-32';
import {
    renderCart, saveCart, switchCartScope, updateCartThresholdNote,
} from './store-cart.js?v=20261028-a43-32';
import { renderProducts } from './store-catalog.js?v=20261028-a43-32';
import { showCheckoutPage } from './store-checkout.js?v=20261105-1';
import { syncGuestContactFields } from './store-delivery.js?v=20261028-a43-32';
import {
    accountMenu, accountStatus, authEmail, authForgotPassword, authForm, authLoginChoice, authLogout,
    authMfaPanel, authMfaStatus, authModal, authMode, authModeSelector, authOrdersPanel, authPassword,
    authPasswordConfirm, authPasswordConfirmLabel, authProfilePanel, authRegisterChoice,
    authRegisterFields, authResend, authResendButton, authResendStatus, authResetEmail, authResetForm,
    authResetPassword, authResetPasswordLabel, authResetSubmit, authResetToken, authResetTokenLabel,
    authSecurityPanel, authSubmit, authToggle, authWishlistPanel, deliveryEmailInput,
    deliveryPhoneInput, dialogs,
} from './store-dom.js?v=20261028-a43-32';
import { showToast } from './store-feedback.js?v=20261028-a43-32';
import { renderHighlights } from './store-highlights.js?v=20261028-a43-32';
import { loadMfaStatus, showMfaChallenge } from './store-security.js?v=20261028-a43-32';
import { API_BASE, api, storeClient, customerSession, invoiceDownloads, accountExportDownloads, accountExportJobs } from './store-services.js?v=20261028-a43-32';
import { runtime, sessionGuard, state } from './store-state.js?v=20261028-a43-32';

export async function toggleWishlist(productId, button = null) {
    if (!state.customer) {
        openAuthModal();
        showToast('Zaloguj się, aby zapisywać ulubione produkty.');
        return;
    }
    const numericProductId = Number(productId);
    const isSaved = state.wishlist.has(numericProductId);
    if (button) button.disabled = true;
    try {
        await api(`/wishlists/customer/${encodeURIComponent(state.customer.id)}/products/${encodeURIComponent(productId)}`, {
            method: isSaved ? 'DELETE' : 'PUT'
        });
        if (isSaved) {
            state.wishlist.delete(numericProductId);
            showToast('Usunięto z ulubionych');
        } else {
            state.wishlist.add(numericProductId);
            showToast('Dodano do ulubionych');
        }
        renderProducts();
        renderHighlights();
        renderWishlistPanel();
    } catch (error) {
        showToast(`Nie udało się zmienić ulubionych: ${error.message}`);
    } finally {
        if (button) button.disabled = false;
    }
}

export function openAuthModal(opener) {
    const isEn = i18n && i18n.getLanguage() === 'en';
    if (state.customer) {
        accountMenu.hidden = !accountMenu.hidden;
        authToggle.setAttribute('aria-expanded', String(!accountMenu.hidden));
        return;
    }
    closeAccountMenu();
    dialogs.open(authModal, undefined, opener);
    runtime.passwordResetMode = false;
    authMfaPanel.hidden = true;
    authSecurityPanel.hidden = true;
    authProfilePanel.hidden = true;
    authOrdersPanel.hidden = true;
    authWishlistPanel.hidden = true;
    updateAuthMode();
    if (state.customer) {
        authEmail.value = state.customer.email || '';
        authSubmit.hidden = true;
        authLogout.hidden = false;
        setAccountStatus(isEn ? `Logged in as ${state.customer.email}.` : `Zalogowano jako ${state.customer.email}.`);
    } else {
        authSubmit.hidden = false;
        authLogout.hidden = true;
        showResendVerification(authEmail.value);
        setAccountStatus(isEn ? 'Sign in or create an account to save delivery details.' : 'Zaloguj się albo załóż konto, aby zapisać dane dostawy.');
    }
}

export function closeAccountMenu() {
    accountMenu.hidden = true;
    authToggle.setAttribute('aria-expanded', 'false');
}

export function closeAuthModal() {
    dialogs.close(authModal);
    if (!state.customer) {
        state.mfaChallenge = null;
        authMfaPanel.hidden = true;
    }
}

export function updateAuthMode() {
    const isEn = i18n && i18n.getLanguage() === 'en';
    authModeSelector.hidden = runtime.passwordResetMode;
    authForm.hidden = runtime.passwordResetMode;
    authResetForm.hidden = !runtime.passwordResetMode;
    authForgotPassword.hidden = runtime.passwordResetMode || authMode.value !== 'login' || Boolean(state.customer);
    if (runtime.passwordResetMode) {
        authResetEmail.required = !authResetToken.value.trim();
        document.querySelector('#auth-title').textContent = authResetToken.value.trim()
            ? (isEn ? 'Set new password' : 'Ustaw nowe hasło')
            : (isEn ? 'Forgot password' : 'Nie pamiętam hasła');
        authResetTokenLabel.hidden = !authResetToken.value.trim();
        authResetPasswordLabel.hidden = !authResetToken.value.trim();
        authResetPassword.required = Boolean(authResetToken.value.trim());
        authResetSubmit.textContent = authResetToken.value.trim()
            ? (isEn ? 'Change password' : 'Zmień hasło')
            : (isEn ? 'Send reset link' : 'Wyślij link resetu');
        return;
    }
    authResetEmail.required = true;
    const registering = authMode.value === 'register';
    document.querySelector('#auth-title').textContent = registering
        ? (isEn ? 'Create account' : 'Załóż konto')
        : (state.customer ? (isEn ? 'Account' : 'Konto') : (isEn ? 'Sign in' : 'Zaloguj się'));
    authPasswordConfirmLabel.hidden = !registering;
    authPasswordConfirm.required = registering;
    authRegisterFields.hidden = !registering;
    authLoginChoice?.classList.toggle('active', !registering);
    authRegisterChoice?.classList.toggle('active', registering);
    authLoginChoice?.setAttribute('aria-pressed', String(!registering));
    authRegisterChoice?.setAttribute('aria-pressed', String(registering));
    authSubmit.textContent = registering
        ? (isEn ? 'Create account' : 'Załóż konto')
        : (isEn ? 'Sign in' : 'Zaloguj się');
    authResend.hidden = !registering || Boolean(state.customer);
    [...authRegisterFields.querySelectorAll('input')].forEach(input => {
        input.required = registering;
    });
    if (!registering) {
        authPasswordConfirm.setCustomValidity('');
    } else {
        validatePasswordMatch();
    }
}

export function selectAuthMode(mode) {
    runtime.passwordResetMode = false;
    authMode.value = mode;
    updateAuthMode();
}

export function selectPasswordResetMode(token = '') {
    const isEn = i18n && i18n.getLanguage() === 'en';
    runtime.passwordResetMode = true;
    authResetToken.value = token;
    authResetEmail.value = authEmail.value.trim();
    authResetPassword.value = '';
    dialogs.open(authModal);
    closeAccountMenu();
    updateAuthMode();
    setAccountStatus(token
        ? (isEn ? 'Enter a new password to complete reset.' : 'Wpisz nowe hasło, aby zakończyć reset.')
        : (isEn ? 'Enter email. You will receive a reset link in Mailpit.' : 'Podaj e-mail. Link resetu otrzymasz w Mailpit.'));
}

export async function submitAuth(event) {
    event.preventDefault();
    const isEn = i18n && i18n.getLanguage() === 'en';
    let registering = false;
    try {
        registering = authMode.value === 'register';
        if (registering && !validatePasswordMatch()) {
            setAccountStatus(isEn ? 'Passwords must match.' : 'Hasła muszą być identyczne.', true);
            authPasswordConfirm.reportValidity();
            return;
        }
        setAccountStatus(registering ? (isEn ? 'Creating account…' : 'Tworzenie konta…') : (isEn ? 'Signing in…' : 'Logowanie…'));
        const payload = { email: authEmail.value.trim(), password: authPassword.value };
        if (registering) {
            Object.assign(payload, {
                firstName: document.querySelector('#auth-first-name').value.trim(),
                lastName: document.querySelector('#auth-last-name').value.trim(),
                street: document.querySelector('#auth-street').value.trim(),
                postalCode: document.querySelector('#auth-postal-code').value.trim(),
                city: document.querySelector('#auth-city').value.trim(),
                country: document.querySelector('#auth-country').value.trim()
            });
        }
        const authResult = await api(registering ? '/customers/register' : '/customers/login', {
            method: 'POST',
            body: JSON.stringify(payload)
        });
        if (registering) {
            authEmail.value = authResult.email || payload.email;
            authPassword.value = '';
            authPasswordConfirm.value = '';
            selectAuthMode('login');
            showResendVerification(authEmail.value);
            setAccountStatus(isEn
                ? `Confirmation email sent to ${authEmail.value}. The link is valid for 5 minutes.`
                : `Wiadomość potwierdzająca została wysłana na adres ${authEmail.value}. Link jest ważny przez 5 minut.`);
            showToast(isEn ? 'Check Mailpit and confirm your account' : 'Sprawdź Mailpit i potwierdź konto');
            return;
        }
        if (authResult?.mfaRequired) {
            showMfaChallenge(authResult);
            return;
        }
        setSession(authResult);
        state.mfaChallenge = null;
        const customer = authResult.customer;
        const nextPurchaseMode = runtime.pendingPurchaseMode;
        runtime.pendingPurchaseMode = null;
        if (nextPurchaseMode === 'account') {
            runtime.purchaseMode = 'account';
        }
        setAccountStatus(registering
            ? (isEn ? `Account created for ${customer.email}.` : `Konto utworzone dla ${customer.email}.`)
            : (isEn ? `Logged in as ${customer.email}.` : `Zalogowano jako ${customer.email}.`));
        showToast(registering ? (isEn ? 'Account created' : 'Konto zostało utworzone') : (isEn ? 'Signed in' : 'Zalogowano do konta'));
        window.setTimeout(() => {
            closeAuthModal();
            if (nextPurchaseMode === 'account') showCheckoutPage();
        }, 500);
    } catch (error) {
        if (!state.customer) {
            showResendVerification(authEmail.value);
        }
        const message = !registering && error.status === 401
            ? (isEn ? 'Invalid email or password.' : 'Nieprawidłowy e-mail lub hasło.')
            : error.message;
        setAccountStatus(isEn ? `Operation failed: ${message}` : `Operacja nie powiodła się: ${message}`, true);
    }
}

export function finishLogin(session) {
    customerSession.invalidate();
    setSession(session);
    state.mfaChallenge = null;
    authMfaPanel.hidden = true;
    authMfaStatus.textContent = '';
    const customer = session.customer;
    const nextPurchaseMode = runtime.pendingPurchaseMode;
    runtime.pendingPurchaseMode = null;
    if (nextPurchaseMode === 'account') runtime.purchaseMode = 'account';
    setAccountStatus(`Zalogowano jako ${customer.email}.`);
    showToast('Zalogowano do konta');
    window.setTimeout(() => {
        closeAuthModal();
        if (nextPurchaseMode === 'account') showCheckoutPage();
    }, 500);
}

export async function openSecurityPanel() {
    closeAccountMenu();
    dialogs.open(authModal);
    runtime.passwordResetMode = false;
    authForm.hidden = true;
    authModeSelector.hidden = true;
    authForgotPassword.hidden = true;
    authResetForm.hidden = true;
    authMfaPanel.hidden = true;
    authSecurityPanel.hidden = false;
    authProfilePanel.hidden = true;
    authOrdersPanel.hidden = true;
    authWishlistPanel.hidden = true;
    authSubmit.hidden = true;
    authLogout.hidden = true;
    const isEn = i18n && i18n.getLanguage() === 'en';
    document.querySelector('#auth-title').textContent = isEn ? 'Account Security' : 'Bezpieczeństwo konta';
    await loadMfaStatus();
}

export async function submitPasswordReset(event) {
    event.preventDefault();
    const token = authResetToken.value.trim();
    try {
        if (!token) {
            if (!authResetEmail.checkValidity()) {
                authResetEmail.reportValidity();
                return;
            }
            await api('/customers/password-reset/request', {
                method: 'POST',
                body: JSON.stringify({ email: authResetEmail.value.trim() })
            }, false);
            setAccountStatus('Jezeli konto istnieje, link resetu zostal wyslany. Sprawdz Mailpit.');
            showToast('Sprawdz Mailpit - wyslano link resetu');
            return;
        }
        if (!authResetPassword.checkValidity()) {
            authResetPassword.reportValidity();
            return;
        }
        await api('/customers/password-reset/confirm', {
            method: 'POST',
            body: JSON.stringify({ token, password: authResetPassword.value })
        }, false);
        const url = new URL(window.location.href);
        url.searchParams.delete('reset');
        window.history.replaceState({}, document.title, `${url.pathname}${url.search}${url.hash}`);
        runtime.passwordResetMode = false;
        authResetToken.value = '';
        authResetPassword.value = '';
        selectAuthMode('login');
        setAccountStatus('Haslo zostalo zmienione. Mozesz sie zalogowac.');
        showToast('Haslo zostalo zmienione');
    } catch (error) {
        setAccountStatus(`Reset hasla nie powiodl sie: ${error.message}`, true);
    }
}

export function showResendVerification(email = authEmail.value) {
    authResend.hidden = Boolean(state.customer) || authMode.value !== 'register';
    if (email?.trim()) {
        authEmail.value = email.trim();
    }
    authResendStatus.textContent = '';
}

export async function resendVerification() {
    const email = authEmail.value.trim();
    if (!email || !authEmail.checkValidity()) {
        authEmail.reportValidity();
        return;
    }

    authResendButton.disabled = true;
    authResendStatus.textContent = 'Sprawdzam zgłoszenie…';
    try {
        await api('/customers/verification/resend', {
            method: 'POST',
            body: JSON.stringify({ email })
        }, false);
        authResendStatus.textContent = 'Jeżeli konto wymaga potwierdzenia, nowy link został wysłany. Sprawdź Mailpit.';
        showToast('Sprawdź Mailpit — link aktywacyjny został ponowiony');
        let secondsLeft = 60;
        const countdown = () => {
            secondsLeft -= 1;
            if (secondsLeft <= 0) {
                authResendButton.disabled = false;
                authResendButton.textContent = 'Wyślij ponownie link';
                return;
            }
            authResendButton.textContent = `Wyślij ponownie link (${secondsLeft}s)`;
            window.setTimeout(countdown, 1000);
        };
        authResendButton.textContent = 'Wyślij ponownie link (60s)';
        window.setTimeout(countdown, 1000);
    } catch (error) {
        authResendStatus.textContent = `Nie udało się wysłać linku: ${error.message}`;
        authResendButton.disabled = false;
    }
}

export function validatePasswordMatch() {
    if (authMode.value !== 'register') {
        authPasswordConfirm.setCustomValidity('');
        return true;
    }
    const matches = authPassword.value === authPasswordConfirm.value;
    authPasswordConfirm.setCustomValidity(matches ? '' : 'Hasła muszą być identyczne.');
    return matches;
}

export async function loadVerification() {
    const url = new URL(window.location.href);
    const token = url.searchParams.get('verify');
    if (!token) return;

    try {
        setAccountStatus('Confirming account...');
        const customer = await api(`/customers/verify?token=${encodeURIComponent(token)}`);
        url.searchParams.delete('verify');
        window.history.replaceState({}, document.title, `${url.pathname}${url.search}${url.hash}`);
        dialogs.open(authModal);
        authEmail.value = customer.email || '';
        authPassword.value = '';
        authSubmit.hidden = false;
        authLogout.hidden = true;
        showResendVerification(authEmail.value);
        selectAuthMode('login');
        setAccountStatus(`Account ${customer.email} confirmed. You can now log in.`);
        showToast('Account confirmed');
    } catch (error) {
        url.searchParams.delete('verify');
        window.history.replaceState({}, document.title, `${url.pathname}${url.search}${url.hash}`);
        dialogs.open(authModal);
        authSubmit.hidden = false;
        authLogout.hidden = true;
        selectAuthMode('register');
        showResendVerification(authEmail.value);
        setAccountStatus(`Account confirmation failed: ${error.message}`, true);
    }
}

export function loadPasswordReset() {
    const token = new URL(window.location.href).searchParams.get('reset');
    if (token) {
        selectPasswordResetMode(token);
    }
}

export async function logoutCustomer(reason = 'manual') {
    customerSession.invalidate();
    accountExportDownloads.cancelAll();
    accountExportJobs.cancelAll();
    invoiceDownloads.cancelAll();
    const revocation=fetch(`${API_BASE}/customers/logout`,{method:'POST',credentials:'include',cache:'no-store',redirect:'error',signal:AbortSignal.timeout(5000)}).catch(()=>{});
    sessionGuard?.clear();
    clearCustomerSession();
    authSubmit.disabled=true;
    try {await revocation;}finally{authSubmit.disabled=false;}
    const timedOut = reason === 'timeout';
    setAccountStatus(timedOut
        ? 'Sesja wygasła po 30 minutach bezczynności. Zaloguj się ponownie.'
        : 'Wylogowano. Możesz nadal kupować jako gość.');
    showToast(timedOut ? 'Sesja wygasła — zaloguj się ponownie' : 'Wylogowano');
}

export async function expireBrowserSession(showMessage) {
    customerSession.invalidate();
    accountExportDownloads.cancelAll();
    accountExportJobs.cancelAll();
    invoiceDownloads.cancelAll();
    const revocation=fetch(`${API_BASE}/customers/logout`,{method:'POST',credentials:'include',cache:'no-store',redirect:'error',signal:AbortSignal.timeout(5000)}).catch(()=>{});
    sessionGuard?.clear();
    clearCustomerSession();
    await revocation;
    if (showMessage) {
        setAccountStatus('Sesja wygasła po 30 minutach bezczynności. Zaloguj się ponownie.', true);
        showToast('Sesja wygasła — zaloguj się ponownie');
    }
}

export function clearCustomerSession(reason) {
    storeClient.invalidate();
    customerSession.invalidate();
    accountExportDownloads.cancelAll(reason);
    accountExportJobs.cancelAll(reason);
    invoiceDownloads.cancelAll();
    const hadAccount = runtime.authenticatedSessionExpected || Boolean(state.customer || state.accessToken);
    saveCart();
    state.accessToken = null;
    state.customer = null;
    state.mfaChallenge = null;
    switchCartScope(null);
    state.wishlist = new Set();
    state.deliveryQuote = null;
    runtime.orderHistoryGeneration++;
    runtime.mfaEnrollment = null;
    runtime.pendingPurchaseMode = null;
    runtime.purchaseMode = 'guest';
    setAccountStatus('');
    document.querySelectorAll('#auth-modal input').forEach(input => { input.value = ''; });
    for (const id of ['auth-orders-list','auth-wishlist-list','auth-security-status','auth-mfa-status','auth-orders-status','auth-wishlist-status','auth-profile-status','auth-recovery-codes','auth-totp-qr']) document.getElementById(id)?.replaceChildren();
    document.getElementById('auth-totp-qr')?.removeAttribute('src');
    if(document.getElementById('auth-totp-setup'))document.getElementById('auth-totp-setup').hidden=true;
    if (hadAccount) for (const id of ['recipient-email','delivery-email','delivery-phone','delivery-first-name','delivery-last-name','delivery-street','delivery-postal-code','delivery-city']) {
        const input=document.getElementById(id);if(input)input.value='';
    }
    if(hadAccount){
        state.coupon=null;
        const coupon=document.getElementById('promotion-code');if(coupon)coupon.value='';
        document.querySelectorAll('#billing-data input:not([type="checkbox"])').forEach(input=>{input.value='';});
        const country=document.getElementById('delivery-country');if(country)country.value='Polska';
        for(const id of ['checkout-summary-email','checkout-summary-address','checkout-summary-payment','checkout-summary-items','checkout-status','promotion-code-feedback'])document.getElementById(id)?.replaceChildren();
    }
    runtime.authenticatedSessionExpected = false;
    renderCart();
    closeAccountMenu();
    authSecurityPanel.hidden = true;
    authMfaPanel.hidden = true;
    authProfilePanel.hidden = true;
    authOrdersPanel.hidden = true;
    authWishlistPanel.hidden = true;
    updateCustomerSummary();
    authSubmit.hidden = false;
    authLogout.hidden = true;
}

export function fillCustomer(customer) {
    authEmail.value = customer.email || '';
    document.querySelector('#recipient-email').value = customer.email || '';
    document.querySelector('#delivery-first-name').value = customer.firstName || '';
    document.querySelector('#delivery-last-name').value = customer.lastName || '';
    document.querySelector('#delivery-street').value = customer.street || '';
    document.querySelector('#delivery-postal-code').value = customer.postalCode || '';
    document.querySelector('#delivery-city').value = customer.city || '';
    document.querySelector('#delivery-country').value = customer.country || 'Polska';
    if (deliveryPhoneInput) deliveryPhoneInput.value = customer.phoneNumber || '';
    if (deliveryEmailInput) deliveryEmailInput.value = customer.email || '';
    syncGuestContactFields();
}

export function setSession(session) {
    if (state.customer?.id !== session.customer?.id) storeClient.invalidate();
    if (state.customer?.id !== session.customer?.id) accountExportDownloads.cancelAll();
    if (state.customer?.id !== session.customer?.id) accountExportJobs.cancelAll();
    if (state.customer?.id !== session.customer?.id) invoiceDownloads.cancelAll();
    state.accessToken = session.accessToken;
    state.customer = session.customer;
    switchCartScope(state.customer);
    runtime.authenticatedSessionExpected = true;
    state.wishlist = new Set();
    fillCustomer(session.customer);
    updateCustomerSummary();
    sessionGuard?.start(() => expireBrowserSession(true));
    sessionGuard?.touch();
    loadWishlist().catch(() => {});
}

export function updateCustomerSummary() {
    const isEn = i18n && i18n.getLanguage() === 'en';
    if (state.customer) {
        authToggle.textContent = `${isEn ? 'Account' : 'Konto'}: ${state.customer.email}`;
    } else {
        authToggle.textContent = isEn ? 'Sign in' : 'Zaloguj się';
        closeAccountMenu();
    }
    updateCartThresholdNote();
}

export function setAccountStatus(message, isError = false) {
    accountStatus.textContent = message;
    accountStatus.style.color = isError ? '#bd331d' : '';
}
