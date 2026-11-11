import {
    handleWishlistPanelAction, openOrderHistoryPanel, openProfilePanel, openWishlistPanel, saveProfile,
} from './store-account-data.js?v=20261028-a43-32';
import {
    closeAccountMenu, closeAuthModal, loadPasswordReset, loadVerification, logoutCustomer,
    openAuthModal, openSecurityPanel, resendVerification, selectAuthMode, selectPasswordResetMode,
    submitAuth, submitPasswordReset, updateAuthMode, updateCustomerSummary, validatePasswordMatch,
} from './store-account.js?v=20261028-a43-32';
import {
    clearCart, closeCartPreview, handleCartAction, openCartPreview, renderCart,
    scheduleCloseCartPreview, toggleCartPreview,
} from './store-cart.js?v=20261028-a43-32';
import {
    fillCategories, fillManufacturers, fillTechnicalParameters, handleProductAction, loadBestsellers,
    loadProducts, renderProducts, resetTechnicalFilterForCategory, selectCategoryFromStrip,
    submitGlobalSearch,
} from './store-catalog.js?v=20261028-a43-32';
import {
    applyPaymentConfiguration, backToCart, choosePurchaseMode, closeCheckoutChoice, closeSuccessModal,
    openCheckoutChoice, prepareCheckoutSummary, resumeStripeCheckoutReturn, setCheckoutStep,
    showCartPage, showStripePaymentStatus, submitCheckout,
} from './store-checkout.js?v=20261105-1';
import {
    clearCompare, closeCompareModal, openCompareModal, renderCompareControls, toggleCompare,
} from './store-comparison.js?v=20261028-a43-32';
import {
    loadDeliveryMethods, loadDeliveryQuote, renderDeliveryMethods, renderPriceSummary, syncBillingUi,
    syncDeliveryModeUi, validateBillingTaxId, validatePromotionCode,
} from './store-delivery.js?v=20261028-a43-32';
import {
    accountArea, accountMenuLogout, accountMenuOrders, accountMenuProfile, accountMenuSecurity,
    accountMenuWishlist, authClose, authForgotPassword, authForm, authLoginChoice, authLogout,
    authMfaCode, authMfaPasskey, authMfaSubmit, authModal, authMode, authPasskeyRegister, authPassword,
    authPasswordConfirm, authProfilePanel, authRegisterChoice, authResendButton, authResetBack,
    authResetForm, authToggle, authTotpConfirm, authTotpDisable, authTotpDisableCode,
    authTotpDisableConfirm, authTotpDisablePanel, authTotpStart, authWishlistList, bestsellerGrid,
    billingCompany, billingTaxId, cartArea, cartCheckout, cartItems, cartPreviewCheckout,
    cartPreviewClose, cartPreviewItems, cartToggle, categoryFilter, categoryPills, checkoutBack,
    checkoutChoiceAccount, checkoutChoiceClose, checkoutChoiceGuest, checkoutChoiceModal, checkoutForm,
    checkoutLayout, checkoutSummaryBack, checkoutSummarySubmit, checkoutToSummary, compareChips,
    compareClear, compareClose, compareModal, compareOpen, deliveryDate, deliveryEmailInput,
    deliveryMethod, dialogs, globalSearchForm, globalSearchInput, heroCarousel, heroNext, heroPause,
    heroPrev, heroTopics, hotShotCard, hotShotDots, hotShotNext, hotShotPrev, hotShotSection,
    invoiceRequested, manufacturerFilter, newProductsCarousel, newProductsGrid, newProductsNext,
    newProductsPrev, pickupLocation, priceMaxFilter, priceMinFilter, productGrid, promotionCodeApply,
    promotionCodeInput, promotionGrid, recentRecommendationsGrid, recentlyViewedGrid, searchInput,
    sortFilter, storeHomeBrands, successModal, technicalParameterFilter, technicalValueFilter,
} from './store-dom.js?v=20261028-a43-32';
import {
    endNewProductsDrag, moveHeroSlide, moveNewProductsDrag, pauseHeroAutoScroll,
    pauseNewProductsAutoScroll, renderHeroSlide, renderHighlights, renderHotShotCard,
    renderRecentlyViewed, resumeHeroAutoScroll, resumeNewProductsAutoScroll, rotateHotShot,
    scrollNewProducts, startHeroAutoScroll, startNewProductsDrag, toggleHeroAutoScroll,
} from './store-highlights.js?v=20261028-a43-32';
import {
    beginTotpEnrollment, confirmTotpEnrollment, disableTotp, registerPasskey, startWebAuthnLogin,
    submitMfaLogin,
} from './store-security.js?v=20261028-a43-32';
import { restoreSession } from './store-services.js?v=20261028-a43-32';
import { runtime } from './store-state.js?v=20261028-a43-32';
import { state } from './store-state.js?v=20261028-a43-32';
import { accountExportDownloads } from './store-services.js?v=20261028-a43-32';
import { accountExportJobs } from './store-services.js?v=20261028-a43-32';
import { bindAccountExportJobsUi } from './account-export-jobs.js?v=20261028-a43-32';
import { authProfileExport, authProfileExportHint, authProfileExportStatus } from './store-dom.js?v=20261028-a43-32';
import { bindAccountExportUi } from './account-export.js?v=20261028-a43-32';
import { i18n } from './i18n.js?v=20261028-a43-32';
import { setAccountStatus } from './store-account.js?v=20261028-a43-32';

bindAccountExportUi({downloads: accountExportDownloads, button: authProfileExport,
    hint: authProfileExportHint, status: authProfileExportStatus, translate: key => i18n.t(key),
    events: window, getOwner: () => state.customer?.id ?? null,
    onSignInRequired: () => { openAuthModal(); setAccountStatus(i18n.t('accountExportSignIn'), true); }});
window.addEventListener('pagehide', () => accountExportDownloads.cancelAll());
bindAccountExportJobsUi({jobs: accountExportJobs, root: document.querySelector('#auth-account-export'),
    translate: key => i18n.t(key), events: window, language: () => i18n.getLanguage(),
    onSignInRequired: () => {openAuthModal(); setAccountStatus(i18n.t('accountJobsSignIn'), true);}});
window.addEventListener('pagehide', () => accountExportJobs.cancelAll());

dialogs.bind(authModal, closeAuthModal, '#auth-toggle');

dialogs.bind(checkoutChoiceModal, closeCheckoutChoice, '#cart-checkout');

dialogs.bind(compareModal, closeCompareModal, '#compare-open');

dialogs.bind(successModal, closeSuccessModal, '#cart-toggle');

document.querySelector('#refresh-products')?.addEventListener('click', loadProducts);

document.querySelector('#clear-cart')?.addEventListener('click', clearCart);

storeHomeBrands.forEach(brand => brand.addEventListener('click', event => {
    event.preventDefault();
    event.stopPropagation();
    const homeUrl = new URL('./index.html#top', window.location.href);
    window.location.assign(homeUrl.href);
}));

cartCheckout.addEventListener('click', openCheckoutChoice);

cartToggle.addEventListener('click', event => {
    event.stopPropagation();
    toggleCartPreview();
});

cartArea?.addEventListener('mouseenter', () => openCartPreview());

cartArea?.addEventListener('mouseleave', scheduleCloseCartPreview);

cartArea?.addEventListener('focusout', event => {
    if (!cartArea.contains(event.relatedTarget)) scheduleCloseCartPreview();
});

cartPreviewClose?.addEventListener('click', closeCartPreview);

cartPreviewCheckout?.addEventListener('click', () => {
    closeCartPreview();
    if (checkoutLayout.classList.contains('checkout-only')) {
        backToCart();
        return;
    }
    if (!document.body.classList.contains('cart-screen-active')) showCartPage();
});

checkoutBack.addEventListener('click', backToCart);

checkoutToSummary?.addEventListener('click', prepareCheckoutSummary);

checkoutSummaryBack?.addEventListener('click', () => {
    setCheckoutStep('delivery');
    window.history.replaceState({}, document.title, '#checkout');
    window.scrollTo({ top: 0, behavior: 'auto' });
});

checkoutSummarySubmit?.addEventListener('click', event => {
    event?.preventDefault();
    submitCheckout(event);
});

searchInput?.addEventListener('input', renderProducts);

categoryFilter?.addEventListener('change', () => {
    fillManufacturers();
    resetTechnicalFilterForCategory();
    renderProducts();
});

manufacturerFilter?.addEventListener('change', renderProducts);

priceMinFilter?.addEventListener('input', renderProducts);

priceMaxFilter?.addEventListener('input', renderProducts);

technicalParameterFilter?.addEventListener('change', renderProducts);

technicalValueFilter?.addEventListener('input', renderProducts);

sortFilter?.addEventListener('change', renderProducts);

categoryPills.forEach(pill => pill.addEventListener('click', selectCategoryFromStrip));

productGrid?.addEventListener('click', handleProductAction);

promotionGrid?.addEventListener('click', handleProductAction);

hotShotCard?.addEventListener('click', handleProductAction);

hotShotDots?.addEventListener('click', event => {
    const dot = event.target.closest('[data-hot-shot-index]');
    if (!dot) return;
    runtime.hotShotIndex = Number(dot.dataset.hotShotIndex) || 0;
    renderHotShotCard();
});

hotShotPrev?.addEventListener('click', () => rotateHotShot(-1));

hotShotNext?.addEventListener('click', () => rotateHotShot(1));

hotShotSection?.addEventListener('mouseenter', () => { runtime.hotShotRotationPaused = true; });

hotShotSection?.addEventListener('mouseleave', () => { runtime.hotShotRotationPaused = false; });

hotShotSection?.addEventListener('focusin', () => { runtime.hotShotRotationPaused = true; });

hotShotSection?.addEventListener('focusout', event => {
    if (!hotShotSection.contains(event.relatedTarget)) runtime.hotShotRotationPaused = false;
});

bestsellerGrid?.addEventListener('click', handleProductAction);

recentlyViewedGrid?.addEventListener('click', handleProductAction);

recentRecommendationsGrid?.addEventListener('click', handleProductAction);

newProductsGrid?.addEventListener('click', handleProductAction);

compareOpen?.addEventListener('click', openCompareModal);

compareClear?.addEventListener('click', clearCompare);

compareClose?.addEventListener('click', closeCompareModal);

compareModal?.addEventListener('click', event => {
    if (event.target === compareModal) closeCompareModal();
});

compareChips?.addEventListener('click', event => {
    const button = event.target.closest('[data-compare-remove]');
    if (button) toggleCompare(button.dataset.compareRemove);
});

newProductsPrev?.addEventListener('click', () => scrollNewProducts(-1));

newProductsNext?.addEventListener('click', () => scrollNewProducts(1));

newProductsGrid?.addEventListener('pointerdown', startNewProductsDrag);

newProductsGrid?.addEventListener('pointermove', moveNewProductsDrag);

newProductsGrid?.addEventListener('pointerup', endNewProductsDrag);

newProductsGrid?.addEventListener('pointercancel', endNewProductsDrag);

newProductsGrid?.addEventListener('click', event => {
    if (!runtime.suppressNewProductsClick) return;
    event.preventDefault();
    event.stopPropagation();
    runtime.suppressNewProductsClick = false;
}, true);

newProductsCarousel?.addEventListener('mouseenter', pauseNewProductsAutoScroll);

newProductsCarousel?.addEventListener('mouseleave', resumeNewProductsAutoScroll);

newProductsCarousel?.addEventListener('focusin', pauseNewProductsAutoScroll);

newProductsCarousel?.addEventListener('focusout', event => {
    if (!newProductsCarousel.contains(event.relatedTarget)) resumeNewProductsAutoScroll();
});

heroPrev?.addEventListener('click', () => moveHeroSlide(-1));

heroNext?.addEventListener('click', () => moveHeroSlide(1));

heroTopics.forEach(topic => topic.addEventListener('click', () => renderHeroSlide(Number(topic.dataset.heroTopic))));

heroPause?.addEventListener('click', toggleHeroAutoScroll);

heroCarousel?.addEventListener('mouseenter', pauseHeroAutoScroll);

heroCarousel?.addEventListener('mouseleave', resumeHeroAutoScroll);

heroCarousel?.addEventListener('focusin', pauseHeroAutoScroll);

heroCarousel?.addEventListener('focusout', event => {
    if (!heroCarousel.contains(event.relatedTarget)) resumeHeroAutoScroll();
});

cartItems.addEventListener('click', handleCartAction);

cartPreviewItems?.addEventListener('click', handleCartAction);

checkoutForm.addEventListener('submit', submitCheckout);

promotionCodeApply?.addEventListener('click', () => validatePromotionCode().catch(() => {}));

promotionCodeInput?.addEventListener('keydown', event => {
    if (event.key === 'Enter') {
        event.preventDefault();
        validatePromotionCode().catch(() => {});
    }
});

deliveryMethod?.addEventListener('change', () => {
    if (deliveryDate) deliveryDate.value = '';
    syncDeliveryModeUi();
    loadDeliveryQuote().catch(() => {});
});

deliveryDate?.addEventListener('change', () => loadDeliveryQuote().catch(() => {}));

pickupLocation?.addEventListener('change', () => loadDeliveryQuote().catch(() => {}));

document.querySelector('#delivery-postal-code')?.addEventListener('change', () => loadDeliveryQuote().catch(() => {}));

invoiceRequested?.addEventListener('change', syncBillingUi);

billingCompany?.addEventListener('change', syncBillingUi);

billingTaxId?.addEventListener('input', validateBillingTaxId);

deliveryEmailInput?.addEventListener('input', () => {
    const recipientEmail = document.querySelector('#recipient-email');
    if (recipientEmail) recipientEmail.value = deliveryEmailInput.value;
});

authToggle.addEventListener('click', openAuthModal);

accountMenuProfile.addEventListener('click', openProfilePanel);

accountMenuOrders.addEventListener('click', openOrderHistoryPanel);

accountMenuWishlist.addEventListener('click', openWishlistPanel);

accountMenuSecurity.addEventListener('click', openSecurityPanel);

accountMenuLogout.addEventListener('click', () => logoutCustomer());

document.addEventListener('click', event => {
    if (!accountArea.contains(event.target)) closeAccountMenu();
    // Quantity updates replace the clicked node before this event reaches document.
    if (cartArea && !event.composedPath().includes(cartArea)) closeCartPreview();
});

document.addEventListener('keydown', event => {
    if (event.key === 'Escape') closeCartPreview();
});

checkoutChoiceClose.addEventListener('click', closeCheckoutChoice);

checkoutChoiceAccount.addEventListener('click', () => choosePurchaseMode('account'));

checkoutChoiceGuest.addEventListener('click', () => choosePurchaseMode('guest'));

authClose.addEventListener('click', closeAuthModal);

authMode.addEventListener('change', updateAuthMode);

authLoginChoice?.addEventListener('click', () => selectAuthMode('login'));

authRegisterChoice?.addEventListener('click', () => selectAuthMode('register'));

authPassword.addEventListener('input', validatePasswordMatch);

authPasswordConfirm.addEventListener('input', validatePasswordMatch);

authForm.addEventListener('submit', submitAuth);

authForgotPassword?.addEventListener('click', () => selectPasswordResetMode());

authResetForm?.addEventListener('submit', submitPasswordReset);

authResetBack?.addEventListener('click', () => {
    runtime.passwordResetMode = false;
    selectAuthMode('login');
    updateAuthMode();
});

authResendButton?.addEventListener('click', resendVerification);

authLogout.addEventListener('click', () => logoutCustomer());

authMfaSubmit.addEventListener('click', submitMfaLogin);

authMfaCode.addEventListener('keydown', event => {
    if (event.key === 'Enter') {
        event.preventDefault();
        submitMfaLogin();
    }
});

authMfaPasskey.addEventListener('click', startWebAuthnLogin);

authTotpStart.addEventListener('click', beginTotpEnrollment);

authTotpConfirm.addEventListener('click', confirmTotpEnrollment);

authTotpDisable.addEventListener('click', () => {
    authTotpDisablePanel.hidden = false;
    authTotpDisableCode.focus();
});

authTotpDisableConfirm.addEventListener('click', disableTotp);

authPasskeyRegister.addEventListener('click', registerPasskey);

authProfilePanel.addEventListener('submit', saveProfile);

authWishlistList.addEventListener('click', handleWishlistPanelAction);

document.querySelector('#success-close').addEventListener('click', closeSuccessModal);

globalSearchInput?.addEventListener('keydown', event => {
    if (event.key === 'Enter') {
        event.preventDefault();
        submitGlobalSearch();
    }
});

globalSearchForm?.addEventListener('submit', event => {
    event.preventDefault();
    submitGlobalSearch();
});

renderCart();

updateCustomerSummary();

renderHeroSlide(0);

startHeroAutoScroll();

const checkoutSessionReady = restoreSession().finally(() => {
    loadProducts();
    loadDeliveryMethods();
    loadVerification();
    loadPasswordReset();
});

window.addEventListener('minitech:language-changed', () => {
    fillCategories();
    fillManufacturers();
    fillTechnicalParameters();
    renderHighlights();
    renderProducts();
    renderRecentlyViewed();
    loadBestsellers().catch(() => {});
    renderCart();
    renderDeliveryMethods();
    renderPriceSummary();
    updateCustomerSummary();
    applyPaymentConfiguration().catch(() => {});
    if (typeof updateAuthMode === 'function') updateAuthMode();
    renderCompareControls();
    if (compareModal && !compareModal.hidden) {
        openCompareModal();
    }
});

applyPaymentConfiguration().catch(() => {});

checkoutSessionReady.catch(() => {}).then(() => resumeStripeCheckoutReturn()).catch(error => {
    showStripePaymentStatus(`Nie udało się wznowić płatności: ${error.message}`, null, true);
});

export { openSuccessModal } from './store-checkout.js?v=20261105-1';
