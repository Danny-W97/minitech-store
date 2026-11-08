import { i18n } from './i18n.js?v=20261028-a43-32';
import {
    billingBuyerName, billingCompany, billingCompanyName, billingDataPanel, billingTaxId,
    deliveryAddressFields, deliveryDate, deliveryEmailInput, deliveryMethod, deliveryPhoneInput,
    deliveryQuote, guestContactFields, invoiceRequested, pickupLocation, pickupLocationField,
    promotionCodeApply, promotionCodeFeedback, promotionCodeInput, summaryDeliveryDate,
    summaryDeliveryGross, summaryDiscount, summaryDiscountLabel, summaryDiscountRow, summaryGross,
    summaryNet, summaryProductsGross, summaryStepDelivery, summaryStepDiscount, summaryStepDiscountRow,
    summaryStepGross, summaryStepNet, summaryStepProducts, summaryStepVat, summaryVat,
} from './store-dom.js?v=20261028-a43-32';
import { escapeHtml, formatDeliveryDate, formatMoney, roundMoney } from './store-format.js?v=20261028-a43-32';
import { api } from './store-services.js?v=20261028-a43-32';
import { runtime, state } from './store-state.js?v=20261028-a43-32';

export async function loadDeliveryMethods() {
    try {
        const [methods, pickupPoints] = await Promise.allSettled([
            api('/deliveries/methods'), api('/deliveries/pickup-locations')
        ]);
        if (methods.status !== 'fulfilled') throw methods.reason;
        state.deliveryMethods = Array.isArray(methods.value) ? methods.value : [];
        state.pickupLocations = pickupPoints.status === 'fulfilled' && Array.isArray(pickupPoints.value)
            ? pickupPoints.value : [];
        renderPickupLocations();
        renderDeliveryMethods();
        await loadDeliveryQuote();
    } catch (error) {
        state.deliveryMethods = [];
        renderDeliveryMethods();
        if (deliveryQuote) {
            deliveryQuote.textContent = `Nie można pobrać metod dostawy: ${error.message}`;
            deliveryQuote.classList.add('error');
        }
    }
}

export function renderPickupLocations() {
    if (!pickupLocation) return;
    const selected = pickupLocation.value;
    const isEn = i18n && i18n.getLanguage() === 'en';
    pickupLocation.innerHTML = state.pickupLocations.length
        ? state.pickupLocations.map(point => `<option value="${escapeHtml(point.code)}">${escapeHtml(point.name)}</option>`).join('')
        : `<option value="">${isEn ? 'No pickup locations available' : 'Brak dostępnych punktów odbioru'}</option>`;
    pickupLocation.value = state.pickupLocations.some(point => point.code === selected)
        ? selected : (state.pickupLocations[0]?.code || '');
}

export function renderDeliveryMethods() {
    if (!deliveryMethod) return;
    const isEn = i18n && i18n.getLanguage() === 'en';
    const selected = deliveryMethod.value;
    const available = state.deliveryMethods.filter(method => method.code !== 'STORE_PICKUP' || state.pickupLocations.length);
    deliveryMethod.innerHTML = state.deliveryMethods.length
        ? state.deliveryMethods.map(method => {
            let name = method.name;
            if (isEn) {
                if (method.code === 'STORE_PICKUP') name = 'Store pickup';
                else if (method.code === 'COURIER_STANDARD') name = 'Standard courier';
                else if (method.code === 'PARCEL_LOCKER') name = 'Parcel locker';
                else if (method.code === 'COURIER_EXPRESS') name = 'Express courier';
            }
            return `<option value="${escapeHtml(method.code)}" ${method.code === 'STORE_PICKUP' && !state.pickupLocations.length ? 'disabled' : ''}>${escapeHtml(name)} — ${isEn ? 'from' : 'od'} ${formatMoney(method.basePrice)}</option>`;
        }).join('')
        : `<option value="">${isEn ? 'No methods available' : 'Brak dostępnych metod'}</option>`;
    deliveryMethod.value = available.some(method => method.code === selected)
        ? selected
        : (available[0]?.code || '');
    syncDeliveryModeUi();
}

export function syncDeliveryModeUi() {
    const pickup = deliveryMethod?.value === 'STORE_PICKUP';
    if (deliveryAddressFields) {
        deliveryAddressFields.hidden = pickup;
        deliveryAddressFields.querySelectorAll('input').forEach(input => {
            input.required = !pickup;
        });
    }
    if (pickupLocationField) pickupLocationField.hidden = !pickup;
    if (deliveryDate) deliveryDate.required = true;
}

export function syncGuestContactFields() {
    const isGuest = runtime.purchaseMode !== 'account' || !state.customer;
    const recipientEmailField = document.querySelector('#recipient-email-field');
    const recipientEmailInput = document.querySelector('#recipient-email');
    if (guestContactFields) {
        guestContactFields.hidden = !isGuest;
        if (deliveryEmailInput) deliveryEmailInput.required = isGuest;
        if (deliveryPhoneInput) deliveryPhoneInput.required = false;
    }
    if (recipientEmailField) {
        recipientEmailField.hidden = isGuest;
        if (recipientEmailInput) recipientEmailInput.required = !isGuest;
    }
}

export function cartSubtotal() {
    return roundMoney(state.cart.reduce((sum, item) => sum + Number(item.price || 0) * item.quantity, 0));
}

export function renderPriceSummary() {
    if (!summaryGross) return;
    const isEn = i18n && i18n.getLanguage() === 'en';
    const productsGross = roundMoney(cartSubtotal());
    const discountGross = roundMoney(Math.min(productsGross, Math.max(0, Number(state.coupon?.discountAmount || 0))));
    const deliveryGross = roundMoney(state.deliveryQuote?.deliveryCost || 0);
    const gross = roundMoney(Math.max(0, productsGross - discountGross + deliveryGross));
    const net = roundMoney(gross / 1.23);
    const vat = roundMoney(gross - net);

    summaryProductsGross.textContent = formatMoney(productsGross);
    summaryDiscountRow.hidden = discountGross <= 0;
    summaryDiscountLabel.textContent = state.coupon?.code
        ? `${isEn ? 'Discount' : 'Rabat'} (${state.coupon.code}${state.coupon.discountPercent ? ` · ${state.coupon.discountPercent}%` : ''})`
        : (isEn ? 'Discount' : 'Rabat');
    summaryDiscount.textContent = `−${formatMoney(discountGross)}`;
    summaryDeliveryGross.textContent = formatMoney(deliveryGross);
    const modeLabel = state.deliveryQuote?.fulfillmentType === 'STORE_PICKUP'
        ? (isEn ? 'Pickup' : 'Odbiór')
        : (isEn ? 'Delivery' : 'Dostawa');
    summaryDeliveryDate.textContent = state.deliveryQuote?.selectedDate
        ? `${modeLabel} · ${formatDeliveryDate(state.deliveryQuote.selectedDate)}`
        : '—';
    summaryNet.textContent = formatMoney(net);
    summaryVat.textContent = formatMoney(vat);
    summaryGross.textContent = formatMoney(gross);
    if (summaryStepProducts) summaryStepProducts.textContent = formatMoney(productsGross);
    if (summaryStepDiscountRow) summaryStepDiscountRow.hidden = discountGross <= 0;
    if (summaryStepDiscount) summaryStepDiscount.textContent = `−${formatMoney(discountGross)}`;
    if (summaryStepDelivery) summaryStepDelivery.textContent = formatMoney(deliveryGross);
    if (summaryStepNet) summaryStepNet.textContent = formatMoney(net);
    if (summaryStepVat) summaryStepVat.textContent = formatMoney(vat);
    if (summaryStepGross) summaryStepGross.textContent = formatMoney(gross);
}

let activeQuotePromise = null;

export function getPendingDeliveryQuote() {
    return activeQuotePromise;
}

export async function loadDeliveryQuote() {
    const isEn = i18n && i18n.getLanguage() === 'en';
    if (!deliveryMethod || !state.cart.length || !deliveryMethod.value) {
        state.deliveryQuote = null;
        if (deliveryQuote) deliveryQuote.textContent = isEn ? 'Select delivery method to see the cost.' : 'Wybierz sposób dostawy, aby zobaczyć koszt.';
        renderPriceSummary();
        return null;
    }
    const postalCode = document.querySelector('#delivery-postal-code')?.value.trim() || '00-000';
    const itemCount = state.cart.reduce((sum, item) => sum + item.quantity, 0);
    const quotePromise = (async () => {
        try {
            const quote = await api('/deliveries/quote', {
                method: 'POST',
                body: JSON.stringify({
                    methodCode: deliveryMethod.value,
                    postalCode,
                    itemCount,
                    // JavaScript stores money as binary floating-point values. Without
                    // normalisation a basket such as 2886.97 can be serialized as
                    // 2886.9700000000003 and rejected by Delivery Service (@Digits(…, 2)).
                    subtotal: roundMoney(cartSubtotal()),
                    requestedDate: deliveryDate?.value || null,
                    pickupLocation: deliveryMethod.value === 'STORE_PICKUP' ? pickupLocation?.value || null : null
                })
            });
            if (deliveryDate && Array.isArray(quote.availableDates)) {
                const selected = deliveryDate.value;
                deliveryDate.innerHTML = quote.availableDates.map(date => `
                    <option value="${escapeHtml(date)}">${escapeHtml(formatDeliveryDate(date))}</option>
                `).join('');
                deliveryDate.value = quote.availableDates.includes(selected)
                    ? selected
                    : (quote.selectedDate || quote.availableDates[0] || '');
            }
            if (pickupLocation && quote.pickupLocationCode) pickupLocation.value = quote.pickupLocationCode;
            state.deliveryQuote = quote;
            syncDeliveryModeUi();
            renderPriceSummary();
            if (deliveryQuote) {
                const eta = isEn
                    ? (quote.etaMinDays === quote.etaMaxDays ? `${quote.etaMinDays} day` : `${quote.etaMinDays}–${quote.etaMaxDays} days`)
                    : (quote.etaMinDays === quote.etaMaxDays ? `${quote.etaMinDays} dzień` : `${quote.etaMinDays}–${quote.etaMaxDays} dni`);
                const modeLabel = quote.fulfillmentType === 'STORE_PICKUP'
                    ? (isEn ? 'store pickup' : 'odbiór osobisty')
                    : (isEn ? 'delivery' : 'dostawa');
                let quoteName = quote.name;
                if (isEn) {
                    if (quote.methodCode === 'STORE_PICKUP' || quoteName === 'Odbiór osobisty w salonie') quoteName = 'Store pickup';
                    else if (quote.methodCode === 'COURIER_STANDARD' || quoteName === 'Kurier standardowy') quoteName = 'Standard courier';
                    else if (quote.methodCode === 'PARCEL_LOCKER' || quoteName === 'Paczkomat') quoteName = 'Parcel locker';
                    else if (quote.methodCode === 'COURIER_EXPRESS' || quoteName === 'Kurier ekspresowy') quoteName = 'Express courier';
                }
                deliveryQuote.textContent = `${quoteName}: ${modeLabel}, ${quote.freeDelivery ? (isEn ? '0.00 PLN' : '0,00 zł') : formatMoney(quote.deliveryCost)} · ${isEn ? 'date' : 'termin'}: ${formatDeliveryDate(quote.selectedDate)} · ${isEn ? 'estimated time' : 'przewidywany czas'}: ${eta}. ${isEn ? 'Total' : 'Razem'}: ${formatMoney(quote.totalPrice)}`;
                deliveryQuote.classList.remove('error');
            }
            return quote;
        } catch (error) {
            state.deliveryQuote = null;
            renderPriceSummary();
            if (deliveryQuote) {
                deliveryQuote.textContent = isEn ? `Cannot calculate delivery cost: ${error.message}` : `Nie można wyliczyć kosztu dostawy: ${error.message}`;
                deliveryQuote.classList.add('error');
            }
            throw error;
        } finally {
            if (activeQuotePromise === quotePromise) {
                activeQuotePromise = null;
            }
        }
    })();
    activeQuotePromise = quotePromise;
    return quotePromise;
}

export async function validatePromotionCode() {
    if (!promotionCodeInput || !promotionCodeFeedback) return null;
    const code = promotionCodeInput.value.trim();
    state.coupon = null;
    renderPriceSummary();
    if (!code) {
        promotionCodeFeedback.textContent = 'Wpisz kod rabatowy.';
        promotionCodeFeedback.className = 'coupon-feedback error';
        return null;
    }
    promotionCodeApply.disabled = true;
    promotionCodeFeedback.textContent = 'Sprawdzam kod…';
    promotionCodeFeedback.className = 'coupon-feedback';
    try {
        const response = await api('/promotions/codes/validate', {
            method: 'POST',
            body: JSON.stringify({ code, subtotal: roundMoney(cartSubtotal()) })
        });
        if (!response.valid) throw new Error(response.message || 'Kod rabatowy jest nieprawidłowy.');
        state.coupon = response;
        renderPriceSummary();
        promotionCodeInput.value = response.code;
        promotionCodeFeedback.textContent = `${response.message} Rabat: ${formatMoney(response.discountAmount)} (${response.discountPercent}%).`;
        promotionCodeFeedback.className = 'coupon-feedback success';
        return response;
    } catch (error) {
        state.coupon = null;
        renderPriceSummary();
        promotionCodeFeedback.textContent = error.message;
        promotionCodeFeedback.className = 'coupon-feedback error';
        throw error;
    } finally {
        promotionCodeApply.disabled = false;
    }
}

export function readDeliveryAddress() {
    const pickup = deliveryMethod?.value === 'STORE_PICKUP';
    return {
        firstName: document.querySelector('#delivery-first-name').value.trim(),
        lastName: document.querySelector('#delivery-last-name').value.trim(),
        street: pickup ? 'MiniTech Store' : document.querySelector('#delivery-street').value.trim(),
        postalCode: pickup ? '00-001' : document.querySelector('#delivery-postal-code').value.trim(),
        city: pickup ? 'Warszawa' : document.querySelector('#delivery-city').value.trim(),
        country: pickup ? 'Polska' : document.querySelector('#delivery-country').value.trim(),
        phone: deliveryPhoneInput?.value.trim() || null
    };
}

export function readBillingData(deliveryAddress) {
    const company = Boolean(billingCompany?.checked);
    return {
        company,
        buyerName: billingBuyerName?.value.trim() || `${deliveryAddress.firstName} ${deliveryAddress.lastName}`.trim(),
        companyName: company ? (billingCompanyName?.value.trim() || null) : null,
        taxId: company ? (billingTaxId?.value.trim() || null) : null,
        street: document.querySelector('#billing-street')?.value.trim() || deliveryAddress.street,
        postalCode: document.querySelector('#billing-postal-code')?.value.trim() || deliveryAddress.postalCode,
        city: document.querySelector('#billing-city')?.value.trim() || deliveryAddress.city,
        country: document.querySelector('#billing-country')?.value.trim() || deliveryAddress.country
    };
}

export function syncBillingUi() {
    if (!billingDataPanel) return;
    const invoice = Boolean(invoiceRequested?.checked);
    billingDataPanel.hidden = !invoice;
    const company = Boolean(billingCompany?.checked);
    const companyNameLabel = document.querySelector('#billing-company-name-label');
    const taxIdLabel = document.querySelector('#billing-tax-id-label');
    if (companyNameLabel) companyNameLabel.hidden = !invoice || !company;
    if (taxIdLabel) taxIdLabel.hidden = !invoice || !company;

    if (invoice && billingBuyerName && !billingBuyerName.value.trim()) {
        const firstName = document.querySelector('#delivery-first-name')?.value.trim() || '';
        const lastName = document.querySelector('#delivery-last-name')?.value.trim() || '';
        billingBuyerName.value = `${firstName} ${lastName}`.trim();
    }

    const requiredForInvoice = [
        billingBuyerName,
        document.querySelector('#billing-street'),
        document.querySelector('#billing-postal-code'),
        document.querySelector('#billing-city'),
        document.querySelector('#billing-country')
    ];
    requiredForInvoice.forEach(input => {
        if (input) input.required = invoice;
    });
    if (billingCompanyName) billingCompanyName.required = invoice && company;
    if (billingTaxId) billingTaxId.required = invoice && company;
    validateBillingTaxId();
}

export function validateBillingTaxId() {
    if (!billingTaxId) return true;
    const companyInvoice = Boolean(invoiceRequested?.checked && billingCompany?.checked);
    const valid = !companyInvoice || /^[0-9]{10}$/.test(billingTaxId.value.trim());
    billingTaxId.setCustomValidity(valid ? '' : 'NIP musi składać się dokładnie z 10 cyfr.');
    return valid;
}
