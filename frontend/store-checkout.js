import { i18n } from './i18n.js?v=20261028-a43-32';
import { canonicalCheckoutIntent, checkoutAttemptFingerprintMatches } from './checkout-intent.js?v=20261028-a43-32';
import { fillCustomer, openAuthModal } from './store-account.js?v=20261028-a43-32';
import { ensureCartStockAvailable, renderCart, saveCart } from './store-cart.js?v=20261028-a43-32';
import { loadBestsellers, setCatalogMessage } from './store-catalog.js?v=20261028-a43-32';
import {
    cartSubtotal, getPendingDeliveryQuote, loadDeliveryQuote, readBillingData, readDeliveryAddress, renderDeliveryMethods,
    renderPriceSummary, syncBillingUi, syncGuestContactFields, validatePromotionCode,
} from './store-delivery.js?v=20261028-a43-32';
import {
    catalogMessage, checkoutChoiceModal, checkoutForm, checkoutLayout, checkoutModeText,
    checkoutProgressSteps, checkoutSection, checkoutStatus, checkoutSubmit, checkoutSummaryAddress,
    checkoutSummaryDelivery, checkoutSummaryEmail, checkoutSummaryItems, checkoutSummaryPayment,
    checkoutSummaryStep, checkoutSummarySubmit, checkoutToSummary, deliveryDate, deliveryEmailInput,
    deliveryPhoneInput, dialogs, invoiceRequested, promotionCodeFeedback, promotionCodeInput,
    successMessage, successModal,
} from './store-dom.js?v=20261028-a43-32';
import { showToast } from './store-feedback.js?v=20261028-a43-32';
import { escapeHtml, formatDeliveryDate, formatMoney } from './store-format.js?v=20261028-a43-32';
import { api } from './store-services.js?v=20261028-a43-32';
import {
    CHECKOUT_ATTEMPT_STORAGE_PREFIX, LOGIN_REQUIRED_THRESHOLD, runtime, state,
} from './store-state.js?v=20261028-a43-32';

export async function applyPaymentConfiguration() {
    try {
        runtime.cachedPaymentConfiguration = await api('/payments/config', { anonymous: true, timeoutMs: 5000 });
    } catch {
        if (!runtime.cachedPaymentConfiguration) {
            runtime.cachedPaymentConfiguration = {
                provider: 'simulation',
                supportedMethods: ['CARD', 'BLIK', 'TRANSFER', 'CASH_ON_DELIVERY', 'CASH_AT_PICKUP']
            };
        }
    }
    const select = document.querySelector('#payment-method');
    if (!select || !Array.isArray(runtime.cachedPaymentConfiguration?.supportedMethods)) return runtime.cachedPaymentConfiguration;
    const supported = new Set(runtime.cachedPaymentConfiguration.supportedMethods);
    const isEn = i18n?.getLanguage() === 'en';
    for (const option of select.options) {
        option.disabled = !supported.has(option.value);
        if (option.value === 'CARD') option.textContent = runtime.cachedPaymentConfiguration.provider === 'stripe'
            ? (isEn ? 'Card (Stripe Checkout)' : 'Karta (Stripe Checkout)')
            : (isEn ? 'Card (simulation)' : 'Karta (symulacja)');
        if (option.value === 'BLIK') option.textContent = isEn ? 'BLIK (simulation)' : 'BLIK (symulacja)';
        if (option.value === 'TRANSFER') option.textContent = isEn ? 'Bank transfer (simulation)' : 'Przelew (symulacja)';
    }
    if (select.selectedOptions[0]?.disabled) {
        const next = [...select.options].find(option => !option.disabled);
        if (next) select.value = next.value;
    }
    return runtime.cachedPaymentConfiguration;
}

export async function submitCheckout(event) {
    event.preventDefault();
    if (runtime.checkoutSubmissionInProgress) return;
    if (!state.cart.length) {
        setCheckoutStatus('Najpierw dodaj produkt do koszyka.', true);
        return;
    }

    const recipient = document.querySelector('#recipient-email').value.trim()
        || deliveryEmailInput?.value.trim() || '';
    const paymentMethod = document.querySelector('#payment-method').value;
    if (runtime.purchaseMode === 'account' && !state.customer) {
        setCheckoutStatus('Wybrano zakup przez konto. Zaloguj się przyciskiem obok koszyka.', true);
        runtime.pendingPurchaseMode = 'account';
        openAuthModal();
        return;
    }
    runtime.checkoutSubmissionInProgress = true;
    checkoutSubmit.disabled = true;
    if (checkoutSummarySubmit) checkoutSummarySubmit.disabled = true;
    setCheckoutStatus('Tworzę zamówienie, rezerwuję stan i przetwarzam płatność…');
    const deliveryAddress = readDeliveryAddress();
    const results = [];
    let createdOrderId = null;
    let quote;

    try {
        const paymentConfiguration = await applyPaymentConfiguration();
        if (!paymentConfiguration.supportedMethods?.includes(paymentMethod)) {
            throw new Error(i18n?.getLanguage() === 'en'
                ? 'Choose a payment method enabled by the payment service.'
                : 'Wybierz metodę płatności obsługiwaną przez usługę płatności.');
        }
        await ensureCartStockAvailable();
        if (promotionCodeInput?.value.trim()) {
            await validatePromotionCode();
        }
        quote = await loadDeliveryQuote();
        if (!quote) {
            throw new Error('Wybierz dostępną metodę dostawy');
        }
        if ((paymentMethod === 'CASH_ON_DELIVERY' && quote.methodCode !== 'STANDARD_COURIER')
                || (paymentMethod === 'CASH_AT_PICKUP' && quote.methodCode !== 'STORE_PICKUP')) {
            throw new Error(i18n?.getLanguage() === 'en'
                ? 'Choose courier delivery for cash on delivery, or store pickup for cash at the counter.'
                : 'Wybierz kuriera dla pobrania albo odbiór osobisty dla płatności gotówką w punkcie.');
        }
        const selectedDeliveryDate = deliveryDate?.value || quote.selectedDate;
        if (!selectedDeliveryDate) {
            throw new Error('Wybierz dzień dostawy lub odbioru');
        }
        const billingSnapshot = invoiceRequested?.checked ? readBillingData(deliveryAddress) : null;
        const attemptFingerprint = checkoutFingerprint({
            recipient,
            paymentMethod,
            deliveryAddress,
            deliveryMethod: quote.methodCode,
            deliveryCost: quote.deliveryCost,
            selectedDeliveryDate,
            promotionCode: state.coupon?.code || null,
            customerId: runtime.purchaseMode === 'account' ? state.customer.id : null,
            invoiceRequested: Boolean(invoiceRequested?.checked),
            billingData: billingSnapshot
        });
        let checkoutAttempt = loadCheckoutAttempt();
        if (!checkoutAttempt || checkoutAttempt.version !== 2
                || !checkoutAttemptFingerprintMatches(checkoutAttempt, attemptFingerprint)
                || !checkoutAttempt.orderIdempotencyKey || !checkoutAttempt.orderAccessToken) {
            checkoutAttempt = {
                version: 2,
                fingerprint: attemptFingerprint,
                orderIdempotencyKey: crypto.randomUUID(),
                orderAccessToken: window.MiniTechApi.newAccessToken(),
                orderId: null,
                paymentId: null,
                recipient,
                paymentMethod,
                completed: false
            };
            saveCheckoutAttempt(checkoutAttempt);
        }
        checkoutAttempt.recipient = recipient;
        checkoutAttempt.paymentMethod = paymentMethod;
        saveCheckoutAttempt(checkoutAttempt);
        const orderIdempotencyKey = checkoutAttempt.orderIdempotencyKey;
        if (!checkoutAttemptPersisted(checkoutAttempt)) {
            throw new Error(i18n?.getLanguage() === 'en'
                ? 'Enable session storage before submitting this order so a retry can use the same checkout key.'
                : 'Włącz pamięć sesji przed wysłaniem zamówienia, aby ponowienie zachowało ten sam klucz zakupu.');
        }
        const order = await api('/orders', {
            method: 'POST',
            headers: { 'Idempotency-Key': orderIdempotencyKey, 'X-Order-Access-Token': checkoutAttempt.orderAccessToken },
            body: JSON.stringify({
                items: state.cart.map(item => ({ productId: item.id, quantity: item.quantity })),
                customerId: runtime.purchaseMode === 'account' ? state.customer.id : null,
                customerEmail: recipient,
                deliveryAddress,
                deliveryMethod: quote.methodCode,
                deliveryCost: quote.deliveryCost,
                promotionCode: state.coupon?.code || null,
                fulfillmentType: quote.fulfillmentType,
                deliveryDate: selectedDeliveryDate,
                deliverySlot: null,
                pickupLocation: quote.fulfillmentType === 'STORE_PICKUP'
                    ? quote.pickupLocationCode : null,
                invoiceRequested: Boolean(invoiceRequested?.checked)
            })
        });
        createdOrderId = order.id;
        if (order.customerId == null) window.MiniTechApi.saveOrderAccess(order.id, checkoutAttempt.orderAccessToken);
        checkoutAttempt.orderId = order.id;
        checkoutAttempt.customerId = order.customerId ?? null;
        saveCheckoutAttempt(checkoutAttempt);
        const payment = await api('/payments', {
            method: 'POST',
            headers: { 'Idempotency-Key': `payment-${order.id}-${orderIdempotencyKey}`, 'X-Order-Access-Token': checkoutAttempt.orderAccessToken },
            body: JSON.stringify({ orderId: order.id, paymentMethod, simulateFailure: false, notificationRecipient: recipient,
                customerId: runtime.purchaseMode === 'account' ? state.customer.id : null,
                invoiceRequested: Boolean(invoiceRequested?.checked),
                billingData: billingSnapshot })
        });
        if (payment.status === 'PENDING' && payment.checkoutUrl) {
            checkoutAttempt.orderId = order.id;
            checkoutAttempt.paymentId = payment.id;
            saveCheckoutAttempt(checkoutAttempt);
            if (!checkoutAttemptPersisted(checkoutAttempt)) {
                throw new Error(i18n?.getLanguage() === 'en'
                    ? 'This browser cannot preserve the secure payment return. Enable session storage and retry.'
                    : 'Przeglądarka nie może zapisać powrotu z płatności. Włącz pamięć sesji i ponów próbę.');
            }
            window.location.assign(payment.checkoutUrl);
            return;
        }
        checkoutAttempt.completed = true;
        saveCheckoutAttempt(checkoutAttempt);
        results.push({ order, payment });
        const purchasedProductIds = order.items?.map(item => item.productId) || state.cart.map(item => item.id);
        state.cart = [];
        state.coupon = null;
        clearCheckoutAttempt();
        if (promotionCodeInput) promotionCodeInput.value = '';
        if (promotionCodeFeedback) promotionCodeFeedback.textContent = '';
        saveCart();
        renderCart();
        // Odświeżenie katalogu nie może zmienić poprawnie opłaconego zamówienia
        // w pozorny błąd transakcji. Ewentualny problem odświeżenia jest tylko
        // ostrzeżeniem i zostanie naprawiony przy kolejnym pobraniu danych.
        const paidContext = runtime.catalogContext;
        if (paidContext?.reader) {
            paidContext.reader.load(purchasedProductIds, { force: true }).catch(refreshError => {
                console.warn('Nie udało się odświeżyć katalogu po zakupie:', refreshError);
            });
            loadBestsellers(paidContext);
        }
        const orderNumbers = results.map(result => `#${result.order.id}`).join(', ');
        const deferredPayment = ['CASH_ON_DELIVERY', 'CASH_AT_PICKUP'].includes(paymentMethod);
        setCheckoutStatus(`${deferredPayment ? 'Gotowe. Zamówienie przyjęte — płatność nastąpi przy odbiorze.' : 'Gotowe. Zamówienie zostało opłacone.'} Realizacja: ${quote.name}, termin: ${formatDeliveryDate(selectedDeliveryDate)} (${formatMoney(quote.deliveryCost)}). Potwierdzenie powinno pojawić się w Mailpit. Zamówienie ${orderNumbers}.`);
        checkoutStatus.classList.add('success');
        returnToStoreHome(orderNumbers, recipient, deferredPayment);
    } catch (error) {
        if (error?.code === 'ACCOUNT_REQUIRED') {
            runtime.pendingPurchaseMode = 'account';
            if (state.customer) { runtime.purchaseMode = 'account'; showCheckoutPage(); }
            else openAuthModal();
            const isEn = i18n && i18n.getLanguage() === 'en';
            setCheckoutStatus(isEn ? 'The current basket value requires a customer account. Sign in and review your order.'
                : 'Aktualna wartość koszyka wymaga konta. Zaloguj się i sprawdź zamówienie.', true);
        } else if (['ORDER_IN_PROGRESS', 'ORDER_IDEMPOTENCY_CONFLICT', 'ORDER_IDEMPOTENCY_LEGACY_REVIEW'].includes(error?.code)) {
            const isEn = i18n?.getLanguage() === 'en';
            const messages = {
                ORDER_IN_PROGRESS: isEn ? 'The order is still being processed. Retry with the same basket and details.'
                    : 'Zamówienie jest jeszcze przetwarzane. Ponów próbę z tym samym koszykiem i danymi.',
                ORDER_IDEMPOTENCY_CONFLICT: isEn ? 'This checkout key belongs to different order details. Check the saved order before starting another purchase.'
                    : 'Ten klucz zakupu jest przypisany do innych danych. Sprawdź zapisane zamówienie przed rozpoczęciem kolejnego zakupu.',
                ORDER_IDEMPOTENCY_LEGACY_REVIEW: isEn ? 'This earlier checkout requires review. Check the saved order or contact support before trying another purchase.'
                    : 'Ta wcześniejsza próba zakupu wymaga sprawdzenia. Odczytaj zapisane zamówienie lub skontaktuj się z obsługą przed kolejnym zakupem.'
            };
            const savedOrder = Number(error.orderId);
            const reference = Number.isSafeInteger(savedOrder) && savedOrder > 0
                ? (isEn ? ` Saved order: #${savedOrder}.` : ` Zapisane zamówienie: #${savedOrder}.`) : '';
            setCheckoutStatus(messages[error.code] + reference, true);
        } else if (['INSUFFICIENT_STOCK', 'SOLD_OUT_REMOVED', 'ZERO_QUANTITY_REMOVED'].includes(error?.code)) {
            setCheckoutStatus(error.message, true);
        } else {
            const completedOrderNumbers = results.map(result => `#${result.order.id}`).join(', ');
            const createdOrderMessage = createdOrderId && !completedOrderNumbers
                ? ` Utworzone zamówienie #${createdOrderId} pozostało zapisane i można bezpiecznie ponowić płatność.`
                : '';
            const partialResult = completedOrderNumbers
                ? ` Zapisane i opłacone zamówienie: ${completedOrderNumbers}. Ponowienie bezpiecznie wykorzysta idempotencję.`
                : createdOrderMessage;
            setCheckoutStatus(`Nie udało się dokończyć zamówienia: ${error.message}. Sprawdź status Order/Payment Service.${partialResult}`, true);
        }
    } finally {
        runtime.checkoutSubmissionInProgress = false;
        checkoutSubmit.disabled = false;
        if (checkoutSummarySubmit) checkoutSummarySubmit.disabled = false;
    }
}

export function openCheckoutChoice() {
    if (!state.cart.length) {
        showToast('Najpierw dodaj produkt do koszyka');
        return;
    }
    showCartPage();
    if (state.customer) {
        runtime.purchaseMode = 'account';
        showCheckoutPage();
        return;
    }
    if (cartSubtotal() > LOGIN_REQUIRED_THRESHOLD) {
        runtime.pendingPurchaseMode = 'account';
        showToast('Dla zamówień powyżej 1 000 zł wymagane jest konto.');
        openAuthModal();
        return;
    }
    dialogs.open(checkoutChoiceModal);
}

export function closeCheckoutChoice() {
    dialogs.close(checkoutChoiceModal);
}

export function choosePurchaseMode(mode) {
    closeCheckoutChoice();
    if (mode === 'guest' && cartSubtotal() > LOGIN_REQUIRED_THRESHOLD) {
        runtime.pendingPurchaseMode = 'account';
        showToast('Dla zamówień powyżej 1 000 zł wymagane jest konto.');
        openAuthModal();
        return;
    }
    runtime.purchaseMode = mode;
    if (mode === 'account' && !state.customer) {
        runtime.pendingPurchaseMode = mode;
        openAuthModal();
        return;
    }
    showCheckoutPage();
}

export function showCheckoutPage() {
    const isEn = i18n && i18n.getLanguage() === 'en';
    checkoutSection.hidden = false;
    checkoutLayout.classList.remove('cart-only');
    checkoutLayout.classList.add('checkout-only');
    document.body.classList.remove('cart-screen-active');
    document.body.classList.add('checkout-screen-active');
    window.history.replaceState({}, document.title, '#checkout');
    window.scrollTo({ top: 0, behavior: 'auto' });
    setCheckoutStep('delivery');
    if (runtime.purchaseMode === 'account' && state.customer) {
        fillCustomer(state.customer);
        checkoutModeText.textContent = isEn ? `Order for account ${state.customer.email}` : `Zamówienie na koncie ${state.customer.email}`;
        setCheckoutStatus(isEn ? `Order will be saved to account ${state.customer.email}.` : `Zamówienie zostanie zapisane na koncie ${state.customer.email}.`);
    } else {
        clearCustomerCheckoutFields();
        checkoutModeText.textContent = isEn ? 'Purchasing as guest' : 'Kupuję jako gość';
        setCheckoutStatus(isEn ? 'Purchasing as guest. Enter delivery details, email, and phone.' : 'Kupujesz jako gość. Wpisz dane dostawy, e-mail i telefon.');
    }
    syncGuestContactFields();
    renderDeliveryMethods();
    renderPriceSummary();
    loadDeliveryQuote().catch(() => {});
}

export function showCartPage() {
    checkoutSection.hidden = true;
    checkoutLayout.classList.add('cart-only');
    checkoutLayout.classList.remove('checkout-only');
    document.body.classList.remove('checkout-screen-active');
    document.body.classList.add('cart-screen-active');
    window.history.replaceState({}, document.title, '#cart');
    window.scrollTo({ top: 0, behavior: 'auto' });
    setCheckoutStep('cart');
}

export function setCheckoutStep(step) {
    runtime.checkoutStep = step;
    checkoutProgressSteps.forEach(progressStep => {
        const stepName = progressStep.dataset.checkoutProgress;
        const stepIndex = ['cart', 'delivery', 'summary'].indexOf(stepName);
        const currentIndex = ['cart', 'delivery', 'summary'].indexOf(step);
        progressStep.classList.toggle('is-active', stepName === step);
        progressStep.classList.toggle('is-complete', stepIndex < currentIndex);
    });
    if (checkoutForm) checkoutForm.hidden = step === 'summary';
    if (checkoutSummaryStep) checkoutSummaryStep.hidden = step !== 'summary';
    if (checkoutToSummary) checkoutToSummary.hidden = step !== 'delivery';
    if (checkoutSubmit) checkoutSubmit.hidden = step !== 'summary';
    if (!document.querySelector('dialog[open]')) {
        const target = document.querySelector(step === 'summary' ? '#checkout-summary-step-title'
            : step === 'delivery' ? '#checkout-title' : '#cart-title');
        if (target?.getClientRects().length) target.focus({ preventScroll: true });
    }
}

export async function prepareCheckoutSummary() {
    syncBillingUi();
    const pendingQuote = getPendingDeliveryQuote();
    if (pendingQuote) {
        try { await pendingQuote; } catch {}
    }
    const deliveryMethod = document.querySelector('#delivery-method');
    if (!deliveryDate?.value && state.cart?.length && deliveryMethod?.value) {
        try { await loadDeliveryQuote(); } catch {}
    }
    if (!checkoutForm.reportValidity()) return;
    try {
        await ensureCartStockAvailable();
        const quote = state.deliveryQuote || await loadDeliveryQuote();
        if (!quote || !deliveryDate?.value) {
            throw new Error('Wybierz dostępny dzień dostawy lub odbioru');
        }
        renderCheckoutSummary();
        setCheckoutStep('summary');
        window.history.replaceState({}, document.title, '#checkout-summary');
        window.scrollTo({ top: 0, behavior: 'auto' });
    } catch (error) {
        setCheckoutStatus(error.message, true);
    }
}

export function renderCheckoutSummary() {
    if (!checkoutSummaryItems) return;
    checkoutSummaryItems.innerHTML = state.cart.map(item => `
        <div class="checkout-summary-item">
            <span>${escapeHtml(item.name)} <small>× ${item.quantity}</small></span>
            <strong>${formatMoney(Number(item.price) * item.quantity)}</strong>
        </div>`).join('');
    const address = readDeliveryAddress();
    const addressText = state.deliveryQuote?.fulfillmentType === 'STORE_PICKUP'
        ? state.deliveryQuote.pickupLocation
        : [address.firstName, address.lastName, address.street, address.postalCode, address.city, address.country]
            .filter(Boolean).join(', ');
    const recipientEmail = document.querySelector('#recipient-email')?.value.trim()
        || deliveryEmailInput?.value.trim() || '—';
    checkoutSummaryEmail.textContent = recipientEmail;
    checkoutSummaryAddress.textContent = addressText || '—';
    checkoutSummaryDelivery.textContent = `${state.deliveryQuote?.name || '—'} · ${formatDeliveryDate(deliveryDate?.value || state.deliveryQuote?.selectedDate)}`;
    checkoutSummaryPayment.textContent = document.querySelector('#payment-method')?.selectedOptions[0]?.textContent || '—';
    renderPriceSummary();
}

export function backToCart() {
    showCartPage();
}

export function returnToStoreHome(orderNumbers, recipient, deferredPayment = false) {
    checkoutSection.hidden = true;
    checkoutLayout.classList.add('cart-only');
    checkoutLayout.classList.remove('checkout-only');
    document.body.classList.remove('checkout-screen-active', 'cart-screen-active');
    setCheckoutStep('cart');
    dialogs.close(successModal);
    window.history.replaceState({}, document.title, '#top');
    window.scrollTo({ top: 0, behavior: 'auto' });
    const paymentLabel = deferredPayment ? 'zostało przyjęte; płatność nastąpi przy realizacji' : 'zostało opłacone';
    setCatalogMessage(`Zakup zakończony pomyślnie. Zamówienie ${orderNumbers} ${paymentLabel}, a potwierdzenie wysłano na ${recipient}.`);
    catalogMessage?.focus({ preventScroll: true });
    showToast(`Zamówienie ${orderNumbers} przyjęte.`, 6000);
}

export function clearCustomerCheckoutFields() {
    document.querySelector('#delivery-first-name').value = '';
    document.querySelector('#delivery-last-name').value = '';
    document.querySelector('#delivery-street').value = '';
    document.querySelector('#delivery-postal-code').value = '';
    document.querySelector('#delivery-city').value = '';
    document.querySelector('#delivery-country').value = 'Polska';
    document.querySelector('#recipient-email').value = '';
    if (deliveryEmailInput) deliveryEmailInput.value = '';
    if (deliveryPhoneInput) deliveryPhoneInput.value = '';
}

export function openSuccessModal(results, recipient) {
    successMessage.textContent = `Zamówienia ${results.map(result => `#${result.order.id}`).join(', ')} zostały opłacone. Potwierdzenie wysłano na ${recipient}.`;
    dialogs.open(successModal);
}

export function closeSuccessModal() {
    dialogs.close(successModal);
}

export function setCheckoutStatus(message, isError = false) {
    checkoutStatus.textContent = message;
    checkoutStatus.classList.toggle('error', isError);
    if (!isError) checkoutStatus.classList.remove('success');
}

export function checkoutAttemptStorageKey() {
    return `${CHECKOUT_ATTEMPT_STORAGE_PREFIX}${runtime.activeCartStorageKey}`;
}

export function checkoutFingerprint(context) {
    return canonicalCheckoutIntent({
        cart: state.cart.map(item => ({ id: item.id, quantity: item.quantity })),
        ...context
    });
}

export function loadCheckoutAttempt() {
    try {
        const attempt = JSON.parse(sessionStorage.getItem(checkoutAttemptStorageKey()) || 'null');
        return attempt && typeof attempt === 'object' ? attempt : null;
    } catch {
        return null;
    }
}

export function checkoutAttemptPersisted(attempt) {
    try {
        const saved = JSON.parse(sessionStorage.getItem(checkoutAttemptStorageKey()) || 'null');
        return saved?.orderId === attempt.orderId && saved?.paymentId === attempt.paymentId
            && saved?.orderAccessToken === attempt.orderAccessToken
            && saved?.orderIdempotencyKey === attempt.orderIdempotencyKey
            && saved?.fingerprint === attempt.fingerprint;
    } catch {
        return false;
    }
}

export function findCheckoutAttemptByOrder(orderId) {
    try {
        for (let index = 0; index < sessionStorage.length; index++) {
            const storageKey = sessionStorage.key(index);
            if (!storageKey?.startsWith(CHECKOUT_ATTEMPT_STORAGE_PREFIX)) continue;
            if (storageKey !== checkoutAttemptStorageKey()) continue;
            const attempt = JSON.parse(sessionStorage.getItem(storageKey) || 'null');
            if (Number(attempt?.orderId) === Number(orderId) && attempt?.orderAccessToken && attempt?.paymentId) {
                return { attempt, storageKey };
            }
        }
    } catch {
        return null;
    }
    return null;
}

export function showStripePaymentStatus(message, checkoutUrl = null, isError = false) {
    checkoutStatus.replaceChildren();
    const text = document.createElement('span');
    text.textContent = message;
    checkoutStatus.append(text);
    if (checkoutUrl && /^https:\/\/checkout\.stripe\.com\//.test(checkoutUrl)) {
        const link = document.createElement('a');
        link.href = checkoutUrl;
        link.rel = 'noopener noreferrer';
        link.textContent = i18n?.getLanguage() === 'en' ? 'Continue to Stripe Checkout' : 'Wróć do płatności Stripe';
        checkoutStatus.append(document.createTextNode(' '), link);
    }
    checkoutStatus.classList.toggle('error', isError);
    checkoutStatus.classList.toggle('success', !isError && !checkoutUrl);
}

export async function resumeStripeCheckoutReturn() {
    const params = new URLSearchParams(window.location.search);
    const returnState = params.get('paymentReturn');
    const orderId = Number(params.get('paymentOrder'));
    if (!['success', 'cancelled'].includes(returnState) || !Number.isSafeInteger(orderId) || orderId < 1) return;

    const isEn = i18n?.getLanguage() === 'en';
    checkoutSection.hidden = false;
    checkoutLayout.classList.remove('cart-only');
    checkoutLayout.classList.add('checkout-only');
    document.body.classList.add('checkout-screen-active');
    setCheckoutStep('summary');
    window.history.replaceState({}, document.title, `${window.location.pathname}#checkout`);

    const stored = findCheckoutAttemptByOrder(orderId);
    if (!stored) {
        showStripePaymentStatus(isEn
            ? `Order #${orderId} is awaiting payment confirmation. This tab no longer has the secure order access needed to check it.`
            : `Zamówienie #${orderId} oczekuje na potwierdzenie płatności. Ta karta nie ma już bezpiecznego dostępu do sprawdzenia statusu.`, null, true);
        return;
    }

    let latest = null;
    try {
        for (let attempt = 0; attempt < 12; attempt++) {
            latest = await api(`/payments/${stored.attempt.paymentId}`, {
                anonymous: stored.attempt.customerId == null,
                headers: { 'X-Order-Access-Token': stored.attempt.orderAccessToken }
            });
            if (latest.status !== 'PENDING') break;
            if (attempt < 11) await new Promise(resolve => window.setTimeout(resolve, 1000));
        }
    } catch (error) {
        showStripePaymentStatus(isEn
            ? `The status of order #${orderId} could not be checked: ${error.message}`
            : `Nie udało się sprawdzić statusu zamówienia #${orderId}: ${error.message}`, null, true);
        return;
    }

    if (latest?.status === 'SUCCEEDED') {
        const cartKey = stored.storageKey.slice(CHECKOUT_ATTEMPT_STORAGE_PREFIX.length);
        if (cartKey === runtime.activeCartStorageKey) {
            state.cart = [];
            state.coupon = null;
            saveCart();
            renderCart();
        } else {
            try { localStorage.removeItem(cartKey); } catch { /* The completed checkout remains verified by Payment. */ }
        }
        try { sessionStorage.removeItem(stored.storageKey); } catch { /* The payment result is already committed. */ }
        returnToStoreHome(`#${orderId}`, stored.attempt.recipient || '—', false);
        return;
    }

    if (latest?.status === 'REFUNDED' || latest?.status === 'FAILED') {
        try { sessionStorage.removeItem(stored.storageKey); } catch { /* The terminal result remains in Payment. */ }
        showStripePaymentStatus(latest.status === 'REFUNDED'
            ? (isEn ? `Payment for order #${orderId} has been refunded. Check the order details for its current status.`
                : `Płatność za zamówienie #${orderId} została zwrócona. Aktualny stan sprawdzisz w szczegółach zamówienia.`)
            : (isEn ? `Payment for order #${orderId} was not completed. Your cart is available for a new order.`
                : `Płatność za zamówienie #${orderId} nie została zakończona. Koszyk jest dostępny do nowego zamówienia.`), null, true);
        return;
    }
    showStripePaymentStatus(isEn
        ? `Order #${orderId} has not been confirmed as paid yet. You can continue Checkout or wait for Stripe to finish processing.`
        : `Zamówienie #${orderId} nie ma jeszcze potwierdzonej płatności. Możesz kontynuować Checkout albo poczekać na potwierdzenie Stripe.`,
        latest?.checkoutUrl || null, latest?.status === 'FAILED');
}

export function saveCheckoutAttempt(attempt) {
    try {
        sessionStorage.setItem(checkoutAttemptStorageKey(), JSON.stringify(attempt));
    } catch {
        // A retry in the same JavaScript execution still uses the in-memory object.
    }
}

export function clearCheckoutAttempt() {
    try {
        sessionStorage.removeItem(checkoutAttemptStorageKey());
    } catch {
        // No persisted attempt to remove in privacy mode.
    }
}
