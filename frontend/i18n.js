import { CATEGORY_NAMES, PARAMETER_LABELS, ORDER_STATUSES, DICTIONARY } from './store-messages.js?v=20261028-a43-32';

const STORAGE_KEY = 'minitech-language';

function getSavedLanguage() {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved === 'en' || saved === 'pl') return saved;
    } catch (_) {}
    return 'pl';
  }

const state = {
    lang: getSavedLanguage()
  };

function getLanguage() {
    return state.lang;
  }

function setLanguage(lang) {
    const nextLang = lang === 'en' ? 'en' : 'pl';
    state.lang = nextLang;
    try {
      localStorage.setItem(STORAGE_KEY, nextLang);
    } catch (_) {}
    applyPageTranslations();
    window.dispatchEvent(new CustomEvent('minitech:language-changed', { detail: { language: nextLang } }));
  }

function toggleLanguage() {
    setLanguage(state.lang === 'en' ? 'pl' : 'en');
  }

function t(key, fallback = '') {
    const dict = DICTIONARY[state.lang] || DICTIONARY.pl;
    return dict[key] !== undefined ? dict[key] : (DICTIONARY.pl[key] || fallback || key);
  }

function translateCategory(category) {
    const entry = CATEGORY_NAMES[category];
    if (entry) return entry[state.lang] || entry.pl;
    return category || (state.lang === 'en' ? 'General' : 'Ogólne');
  }

function translateParamKey(key) {
    const entry = PARAMETER_LABELS[key];
    if (entry) return entry[state.lang] || entry.pl;
    return key;
  }

function translateOrderStatus(status) {
    const key = String(status || '').toUpperCase();
    const entry = ORDER_STATUSES[key];
    if (entry) return entry[state.lang] || entry.pl;
    return status || '—';
  }

function formatMoney(amount) {
    const num = Number(amount || 0);
    if (!Number.isFinite(num)) return state.lang === 'en' ? '0.00 PLN' : '0,00 zł';
    if (state.lang === 'en') {
      return `${num.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} PLN`;
    }
    return `${num.toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} zł`;
  }

function updateLabelText(inputSelector, text) {
    const input = document.querySelector(inputSelector);
    if (!input) return;
    const label = input.closest('label');
    if (label && label.childNodes.length > 0) {
      label.childNodes[0].nodeValue = text;
    }
  }

function applyPageTranslations() {
    const lang = state.lang;
    const isEn = lang === 'en';
    const dict = DICTIONARY[lang] || DICTIONARY.pl;
    document.documentElement.lang = lang;

    // Indicator
    const indicator = document.querySelector('#lang-indicator');
    if (indicator) indicator.textContent = dict.langCode;
    const toggleBtn = document.querySelector('#lang-switch-toggle');
    if (toggleBtn) {
      toggleBtn.title = dict.langTitle;
      toggleBtn.setAttribute('aria-label', dict.langTitle);
    }

    // Global search
    const searchInput = document.querySelector('#global-product-search');
    if (searchInput) searchInput.placeholder = dict.searchPlaceholder;

    // Main navigation
    const navLinks = document.querySelectorAll('.main-nav a');
    navLinks.forEach(link => {
      const href = link.getAttribute('href') || '';
      if (href.includes('catalog.html') || href.includes('#catalog')) link.textContent = dict.navCatalog;
      else if (href.includes('promotions') || href.includes('#promotions')) link.textContent = dict.navPromotions;
      else if (href.includes('new-products') || href.includes('#new-products')) link.textContent = dict.navNew;
      else if (href.includes('delivery.html')) link.textContent = dict.navDelivery;
      else if (href.includes('about') || href.includes('#about')) link.textContent = dict.navAbout;
    });

    // Back button in header (if present)
    const headerBack = document.querySelector('.topbar > .secondary-button');
    if (headerBack) {
      const href = headerBack.getAttribute('href') || '';
      headerBack.textContent = href.includes('catalog') ? dict.navBackToCatalog : dict.navBackToStore;
    }

    // Category pills
    document.querySelectorAll('.category-pill').forEach(pill => {
      const cat = pill.dataset.category || 'all';
      pill.textContent = translateCategory(cat);
    });

    // Account toggle & menu items
    const authToggle = document.querySelector('#auth-toggle');
    if (authToggle && !authToggle.textContent.includes('@')) {
      authToggle.textContent = dict.authLogin;
    }
    const profileBtn = document.querySelector('#account-menu-profile');
    if (profileBtn) profileBtn.textContent = dict.authProfile;
    const ordersBtn = document.querySelector('#account-menu-orders');
    if (ordersBtn) ordersBtn.textContent = dict.authOrders;
    const wishlistBtn = document.querySelector('#account-menu-wishlist');
    if (wishlistBtn) wishlistBtn.textContent = dict.authWishlist;
    const securityBtn = document.querySelector('#account-menu-security');
    if (securityBtn) securityBtn.textContent = dict.authSecurity;
    const logoutBtn = document.querySelector('#account-menu-logout');
    if (logoutBtn) logoutBtn.textContent = dict.authLogout;

    // Cart button
    const cartBtn = document.querySelector('#cart-toggle');
    if (cartBtn) {
      for (let i = 0; i < cartBtn.childNodes.length; i++) {
        if (cartBtn.childNodes[i].nodeType === Node.TEXT_NODE) {
          cartBtn.childNodes[i].nodeValue = `${dict.cartCount} `;
          break;
        }
      }
    }

    // Checkout Breadcrumbs
    const p1 = document.querySelector('[data-checkout-progress="cart"] small');
    if (p1) p1.textContent = isEn ? 'Cart' : 'Koszyk';
    const p2 = document.querySelector('[data-checkout-progress="delivery"] small');
    if (p2) p2.textContent = isEn ? 'Delivery & payment' : 'Dostawa i płatność';
    const p3 = document.querySelector('[data-checkout-progress="summary"] small');
    if (p3) p3.textContent = isEn ? 'Summary' : 'Podsumowanie';

    // Cart Panel
    const cartEye = document.querySelector('#cart-panel .cart-heading .eyebrow');
    if (cartEye) cartEye.textContent = isEn ? 'YOUR CART' : 'TWÓJ KOSZYK';
    const cartH2 = document.querySelector('#cart-panel .cart-heading h2');
    if (cartH2) cartH2.textContent = isEn ? 'Shopping cart' : 'Koszyk';
    const clearCartBtn = document.querySelector('#clear-cart');
    if (clearCartBtn) clearCartBtn.textContent = isEn ? 'Clear' : 'Wyczyść';
    const cartTotSpan = document.querySelector('#cart-panel .cart-total span');
    if (cartTotSpan) cartTotSpan.textContent = isEn ? 'Total' : 'Razem';
    const cartCheckoutBtn = document.querySelector('#cart-checkout');
    if (cartCheckoutBtn) cartCheckoutBtn.textContent = isEn ? 'Proceed to delivery' : 'Przejdź do dostawy';
    const cartEmptyMsg = document.querySelector('#cart-empty');
    if (cartEmptyMsg) cartEmptyMsg.textContent = isEn ? 'Your shopping cart is empty. Add a product from the catalog.' : 'Koszyk jest pusty. Dodaj produkt z katalogu.';

    // Checkout Section
    const chkEye = document.querySelector('.checkout-heading-copy .eyebrow');
    if (chkEye) chkEye.textContent = '02 / ORDER SERVICE';
    const chkH2 = document.querySelector('.checkout-heading-copy h2');
    if (chkH2) chkH2.textContent = isEn ? 'Order Checkout' : 'Finalizacja zamówienia';
    const chkBackBtn = document.querySelector('#checkout-back');
    if (chkBackBtn) chkBackBtn.textContent = isEn ? 'Back to cart' : 'Wróć do koszyka';

    // Step 01 Delivery
    const step1Heading = document.querySelectorAll('.checkout-step-heading');
    if (step1Heading[0]) {
      const eye = step1Heading[0].querySelector('.eyebrow'); if (eye) eye.textContent = isEn ? 'DELIVERY & PICKUP' : 'DOSTAWA I ODBIÓR';
      const h3 = step1Heading[0].querySelector('h3'); if (h3) h3.textContent = isEn ? 'Choose fulfillment method' : 'Wybierz sposób realizacji';
    }
    const delHeadEye = document.querySelector('.delivery-heading .eyebrow');
    if (delHeadEye) delHeadEye.textContent = isEn ? 'DELIVERY DETAILS' : 'DANE DOSTAWY';
    const delHeadHint = document.querySelector('.delivery-heading .form-hint');
    if (delHeadHint) delHeadHint.textContent = isEn ? 'As a guest, enter details manually. When logged in, they are filled from your profile.' : 'Dla gościa podaj dane ręcznie. Po zalogowaniu zostaną uzupełnione z profilu.';

    updateLabelText('#delivery-first-name', isEn ? 'First name' : 'Imię');
    updateLabelText('#delivery-last-name', isEn ? 'Last name' : 'Nazwisko');
    updateLabelText('#delivery-email', isEn ? 'Contact email' : 'E-mail kontaktowy');
    updateLabelText('#delivery-phone', isEn ? 'Phone number (optional)' : 'Numer telefonu (opcjonalnie)');
    updateLabelText('#delivery-street', isEn ? 'Street and number' : 'Ulica i numer');
    updateLabelText('#delivery-postal-code', isEn ? 'Postal code' : 'Kod pocztowy');
    updateLabelText('#delivery-city', isEn ? 'City' : 'Miasto');
    updateLabelText('#delivery-country', isEn ? 'Country' : 'Kraj');
    const delCountry = document.querySelector('#delivery-country');
    if (delCountry && (delCountry.value === 'Polska' || delCountry.value === 'Poland')) {
      delCountry.value = isEn ? 'Poland' : 'Polska';
    }
    updateLabelText('#delivery-method', isEn ? 'Delivery method' : 'Sposób dostawy');
    updateLabelText('#pickup-location', isEn ? 'Pickup point' : 'Punkt odbioru');
    updateLabelText('#delivery-date', isEn ? 'Delivery or pickup date' : 'Dzień dostawy lub odbioru');

    // Step 02 Payment
    if (step1Heading[1]) {
      const eye = step1Heading[1].querySelector('.eyebrow'); if (eye) eye.textContent = isEn ? 'PAYMENT' : 'PŁATNOŚĆ';
      const h3 = step1Heading[1].querySelector('h3'); if (h3) h3.textContent = isEn ? 'Choose payment method' : 'Wybierz metodę płatności';
    }
    updateLabelText('#promotion-code', isEn ? 'Discount code' : 'Kod rabatowy');
    const promoCodeInput = document.querySelector('#promotion-code');
    if (promoCodeInput) promoCodeInput.placeholder = isEn ? 'e.g. WELCOME10' : 'np. WELCOME10';
    const promoApplyBtn = document.querySelector('#promotion-code-apply');
    if (promoApplyBtn) promoApplyBtn.textContent = isEn ? 'Apply code' : 'Zastosuj kod';

    // Checkout Price Summary
    const chkPriceEye = document.querySelector('.checkout-price-summary-heading .eyebrow');
    if (chkPriceEye) chkPriceEye.textContent = isEn ? 'PAYMENT SUMMARY' : 'PODSUMOWANIE PŁATNOŚCI';
    const chkPriceH3 = document.querySelector('#checkout-price-summary-title');
    if (chkPriceH3) chkPriceH3.textContent = isEn ? 'Final price' : 'Cena końcowa';
    const priceRows = document.querySelectorAll('.checkout-price-summary .price-summary-row');
    if (priceRows[0]) priceRows[0].querySelector('span').textContent = isEn ? 'Products gross' : 'Produkty brutto';
    const discLabel = document.querySelector('#summary-discount-label');
    if (discLabel && !discLabel.textContent.includes('(')) discLabel.textContent = isEn ? 'Discount' : 'Rabat';
    if (priceRows[2]) priceRows[2].querySelector('span').textContent = isEn ? 'Delivery gross' : 'Dostawa brutto';
    if (priceRows[3]) priceRows[3].querySelector('span').textContent = isEn ? 'Fulfillment date' : 'Termin realizacji';
    if (priceRows[4]) priceRows[4].querySelector('span').textContent = isEn ? 'Net' : 'Netto';
    if (priceRows[5]) priceRows[5].querySelector('span').textContent = 'VAT (23%)';
    const grossRow = document.querySelector('.price-summary-row.price-summary-total span');
    if (grossRow) grossRow.textContent = isEn ? 'Gross to pay' : 'Brutto do zapłaty';
    const vatHint = document.querySelector('.checkout-price-summary > .form-hint');
    if (vatHint) vatHint.textContent = isEn ? 'Demonstration settlement with standard VAT rate 23%.' : 'Rozliczenie demonstracyjne z podstawową stawką VAT 23%.';

    updateLabelText('#recipient-email', isEn ? 'Confirmation email' : 'E-mail do potwierdzenia');
    const invReqLabel = document.querySelector('label[for="invoice-requested"]') || document.querySelector('#invoice-requested')?.closest('label');
    if (invReqLabel && invReqLabel.childNodes.length > 1) invReqLabel.childNodes[1].nodeValue = isEn ? ' I want an invoice' : ' Chcę otrzymać fakturę';
    const billCompLabel = document.querySelector('label[for="billing-company"]') || document.querySelector('#billing-company')?.closest('label');
    if (billCompLabel && billCompLabel.childNodes.length > 1) billCompLabel.childNodes[1].nodeValue = isEn ? ' Company invoice' : ' Faktura na firmę';

    updateLabelText('#billing-buyer-name', isEn ? 'Full name' : 'Imię i nazwisko');
    updateLabelText('#billing-company-name', isEn ? 'Company name' : 'Nazwa firmy');
    updateLabelText('#billing-tax-id', isEn ? 'Tax ID (NIP)' : 'NIP');
    updateLabelText('#billing-street', isEn ? 'Street and number' : 'Ulica i numer');
    updateLabelText('#billing-postal-code', isEn ? 'Postal code' : 'Kod pocztowy');
    updateLabelText('#billing-city', isEn ? 'City' : 'Miasto');
    updateLabelText('#billing-country', isEn ? 'Country' : 'Kraj');
    const billCountry = document.querySelector('#billing-country');
    if (billCountry && (billCountry.value === 'Polska' || billCountry.value === 'Poland')) {
      billCountry.value = isEn ? 'Poland' : 'Polska';
    }

    updateLabelText('#payment-method', isEn ? 'Payment method' : 'Metoda płatności');
    const payMethodSelect = document.querySelector('#payment-method');
    if (payMethodSelect && payMethodSelect.options.length >= 4) {
      payMethodSelect.options[0].textContent = isEn ? 'Card (simulation)' : 'Karta (symulacja)';
      payMethodSelect.options[1].textContent = isEn ? 'BLIK (simulation)' : 'BLIK (symulacja)';
      payMethodSelect.options[2].textContent = isEn ? 'Pay on delivery' : 'Gotówka przy odbiorze';
      payMethodSelect.options[3].textContent = isEn ? 'Online bank transfer (simulation)' : 'Przelew (symulacja)';
      const cashPickup = payMethodSelect.querySelector('option[value="CASH_AT_PICKUP"]');
      if (cashPickup) cashPickup.textContent = isEn ? 'Cash at store pickup' : 'Gotówka w punkcie odbioru';
    }

    const chkToSummaryBtn = document.querySelector('#checkout-to-summary');
    if (chkToSummaryBtn) chkToSummaryBtn.textContent = isEn ? 'Proceed to summary' : 'Przejdź do podsumowania';
    const chkSubmitBtn = document.querySelector('#checkout-submit');
    if (chkSubmitBtn) chkSubmitBtn.textContent = isEn ? 'Place and pay order' : 'Złóż i opłać zamówienie';
    const chkDevHint = document.querySelector('#checkout-form > .form-hint');
    if (chkDevHint) chkDevHint.textContent = isEn ? 'In dev profile, payment is simulated and receipt with delivery address is sent to Mailpit.' : 'W profilu dev płatność jest symulowana, a potwierdzenie z adresem dostawy trafia do Mailpit.';

    // Step 03 Summary Step
    const step3Heading = document.querySelector('#checkout-summary-step .checkout-step-heading');
    if (step3Heading) {
      const eye = step3Heading.querySelector('.eyebrow'); if (eye) eye.textContent = isEn ? 'SUMMARY' : 'PODSUMOWANIE';
      const h3 = step3Heading.querySelector('h3'); if (h3) h3.textContent = isEn ? 'Review order before payment' : 'Sprawdź zamówienie przed opłaceniem';
    }
    const sumDetails = document.querySelectorAll('.checkout-summary-details > div');
    if (sumDetails.length >= 4) {
      sumDetails[0].querySelector('span').textContent = isEn ? 'Email' : 'E-mail';
      sumDetails[1].querySelector('span').textContent = isEn ? 'Address or pickup point' : 'Adres lub punkt odbioru';
      sumDetails[2].querySelector('span').textContent = isEn ? 'Delivery' : 'Dostawa';
      sumDetails[3].querySelector('span').textContent = isEn ? 'Payment' : 'Płatność';
    }
    const step3PriceEye = document.querySelector('#checkout-summary-step .checkout-price-summary-heading .eyebrow');
    if (step3PriceEye) step3PriceEye.textContent = isEn ? 'PAYMENT SUMMARY' : 'PODSUMOWANIE PŁATNOŚCI';
    const step3PriceH3 = document.querySelector('#checkout-summary-total-title');
    if (step3PriceH3) step3PriceH3.textContent = isEn ? 'Final price' : 'Cena końcowa';
    const step3Rows = document.querySelectorAll('#checkout-summary-step .price-summary-row');
    if (step3Rows[0]) step3Rows[0].querySelector('span').textContent = isEn ? 'Products gross' : 'Produkty brutto';
    if (step3Rows[1]) step3Rows[1].querySelector('span').textContent = isEn ? 'Rabat' : 'Rabat';
    if (step3Rows[2]) step3Rows[2].querySelector('span').textContent = isEn ? 'Delivery gross' : 'Dostawa brutto';
    if (step3Rows[3]) step3Rows[3].querySelector('span').textContent = isEn ? 'Net' : 'Netto';
    if (step3Rows[4]) step3Rows[4].querySelector('span').textContent = 'VAT (23%)';
    const step3Gross = document.querySelector('#checkout-summary-step .price-summary-row.price-summary-total span');
    if (step3Gross) step3Gross.textContent = isEn ? 'Gross to pay' : 'Brutto do zapłaty';
    const step3Back = document.querySelector('#checkout-summary-back');
    if (step3Back) step3Back.textContent = isEn ? 'Back to delivery & payment' : 'Wróć do dostawy i płatności';
    const step3Sub = document.querySelector('#checkout-summary-submit');
    if (step3Sub) step3Sub.textContent = isEn ? 'Place and pay order' : 'Złóż i opłać zamówienie';

    // Checkout Choice Modal
    const chkChoiceTitle = document.querySelector('#checkout-choice-title');
    if (chkChoiceTitle) chkChoiceTitle.textContent = isEn ? 'How would you like to purchase?' : 'Jak chcesz kupić?';
    const chkChoiceHints = document.querySelectorAll('#checkout-choice-modal .form-hint');
    if (chkChoiceHints[0]) chkChoiceHints[0].textContent = isEn ? 'Select your purchase method and then complete checkout.' : 'Wybierz sposób zakupu, a następnie uzupełnij finalizację zamówienia.';
    if (chkChoiceHints[1]) chkChoiceHints[1].textContent = isEn ? 'For carts up to 1,000 PLN an account is not required — you can buy as a guest.' : 'Przy koszyku do 1 000 zł konto nie jest wymagane — możesz kupić jako gość.';
    const chkChoiceAcc = document.querySelector('#checkout-choice-account');
    if (chkChoiceAcc) chkChoiceAcc.textContent = isEn ? 'I have an account — Sign in' : 'Mam konto — zaloguj się';
    const chkChoiceGst = document.querySelector('#checkout-choice-guest');
    if (chkChoiceGst) chkChoiceGst.textContent = isEn ? 'Buy as guest' : 'Kupuję jako gość';

    // Auth modal
    const authModalEye = document.querySelector('#auth-modal .eyebrow');
    if (authModalEye) authModalEye.textContent = isEn ? 'MINITECH ACCOUNT' : 'KONTO MINITECH';
    const authModeLabel = document.querySelector('#auth-mode-selector > span');
    if (authModeLabel) authModeLabel.textContent = isEn ? 'Select action' : 'Wybierz operację';
    const authLoginChoice = document.querySelector('#auth-login-choice');
    if (authLoginChoice) authLoginChoice.textContent = isEn ? 'Sign in' : 'Zaloguj się';
    const authRegisterChoice = document.querySelector('#auth-register-choice');
    if (authRegisterChoice) authRegisterChoice.textContent = isEn ? 'Create account' : 'Załóż konto';
    const authModalTitle = document.querySelector('#auth-title');
    if (authModalTitle) {
      const isRegister = document.querySelector('#auth-register-fields') && !document.querySelector('#auth-register-fields').hidden;
      authModalTitle.textContent = isRegister ? (isEn ? 'Create account' : 'Załóż konto') : (isEn ? 'Sign in' : 'Zaloguj się');
    }
    updateLabelText('#auth-email', isEn ? 'Email' : 'E-mail');
    updateLabelText('#auth-password', isEn ? 'Password' : 'Hasło');
    updateLabelText('#auth-password-confirm', isEn ? 'Confirm password' : 'Powtórz hasło');
    updateLabelText('#auth-first-name', isEn ? 'First name' : 'Imię');
    updateLabelText('#auth-last-name', isEn ? 'Last name' : 'Nazwisko');
    updateLabelText('#auth-street', isEn ? 'Street and number' : 'Ulica i numer');
    updateLabelText('#auth-postal-code', isEn ? 'Postal code' : 'Kod pocztowy');
    updateLabelText('#auth-city', isEn ? 'City' : 'Miasto');
    updateLabelText('#auth-country', isEn ? 'Country' : 'Kraj');
    const authCountry = document.querySelector('#auth-country');
    if (authCountry && (authCountry.value === 'Polska' || authCountry.value === 'Poland')) {
      authCountry.value = isEn ? 'Poland' : 'Polska';
    }
    const authSubmit = document.querySelector('#auth-submit');
    if (authSubmit) {
      const isRegister = document.querySelector('#auth-register-fields') && !document.querySelector('#auth-register-fields').hidden;
      authSubmit.textContent = isRegister ? (isEn ? 'Create account' : 'Załóż konto') : (isEn ? 'Sign in' : 'Zaloguj się');
    }
    const authForgotPassword = document.querySelector('#auth-forgot-password');
    if (authForgotPassword) authForgotPassword.textContent = isEn ? 'Forgot password' : 'Nie pamiętam hasła';
    const authResetBack = document.querySelector('#auth-reset-back');
    if (authResetBack) authResetBack.textContent = isEn ? 'Back to login' : 'Wróć do logowania';
    const authResetSubmit = document.querySelector('#auth-reset-submit');
    if (authResetSubmit) authResetSubmit.textContent = isEn ? 'Send reset link' : 'Wyślij link resetu';
    const authRegisterHint = document.querySelector('#auth-register-fields .form-hint');
    if (authRegisterHint) authRegisterHint.textContent = isEn ? 'The address saved during registration will be used for future orders.' : 'Adres zapisany przy rejestracji zostanie użyty przy kolejnych zamówieniach.';
    const authResendHint = document.querySelector('#auth-resend .form-hint');
    if (authResendHint) authResendHint.textContent = isEn ? "Didn't receive the email or the link expired? You can request a new activation link." : 'Nie otrzymałeś wiadomości albo link wygasł? Możesz poprosić o nowy link aktywacyjny.';
    const authResendBtn = document.querySelector('#auth-resend-button');
    if (authResendBtn) authResendBtn.textContent = isEn ? 'Resend link' : 'Wyślij ponownie link';
    const authFormHint = document.querySelector('#auth-form + .form-hint') || document.querySelector('.auth-dialog > .form-hint');
    if (authFormHint) authFormHint.textContent = isEn ? 'Sign in or create an account to save delivery details.' : 'Zaloguj się albo załóż konto, aby zapisać dane dostawy.';

    const promoGridHeading = document.querySelector('.promotion-grid-heading .eyebrow');
    if (promoGridHeading) promoGridHeading.textContent = isEn ? 'BEST PRICES' : 'NAJLEPSZE CENY';
    const promoGridH3 = document.querySelector('.promotion-grid-heading h3');
    if (promoGridH3) promoGridH3.textContent = isEn ? 'Daily deals' : 'Promocje dnia';

    // Cart preview
    const cartPreviewHeading = document.querySelector('.cart-preview-heading h2');
    if (cartPreviewHeading) {
      for (let i = 0; i < cartPreviewHeading.childNodes.length; i++) {
        if (cartPreviewHeading.childNodes[i].nodeType === Node.TEXT_NODE) {
          cartPreviewHeading.childNodes[i].nodeValue = `${dict.cartTitle} (`;
          break;
        }
      }
    }
    const cartPreviewTotalSpan = document.querySelector('.cart-preview-footer div span');
    if (cartPreviewTotalSpan) cartPreviewTotalSpan.textContent = dict.cartTotal;
    const cartPreviewCheckout = document.querySelector('#cart-preview-checkout');
    if (cartPreviewCheckout) cartPreviewCheckout.textContent = dict.cartCheckout;

    // Hero section
    const heroSlideNew = document.querySelector('.hero-slide-new');
    if (heroSlideNew) {
      const eye = heroSlideNew.querySelector('[data-hero-eyebrow]'); if (eye) eye.textContent = dict.heroEyebrowNew;
      const h1 = heroSlideNew.querySelector('h1'); if (h1) h1.innerHTML = dict.heroTitleNew;
      const lead = heroSlideNew.querySelector('[data-hero-lead]'); if (lead) lead.textContent = dict.heroLeadNew;
      const link = heroSlideNew.querySelector('[data-hero-link]'); if (link) link.innerHTML = dict.heroBtnNew;
      const badge = heroSlideNew.querySelector('[data-hero-badge]'); if (badge) badge.textContent = dict.heroBadgeNew;
    }
    const heroSlidePromo = document.querySelector('.hero-slide-promo');
    if (heroSlidePromo) {
      const eye = heroSlidePromo.querySelector('[data-hero-eyebrow]'); if (eye) eye.textContent = dict.heroEyebrowPromo;
      const h1 = heroSlidePromo.querySelector('h1'); if (h1) h1.innerHTML = dict.heroTitlePromo;
      const lead = heroSlidePromo.querySelector('[data-hero-lead]'); if (lead) lead.textContent = dict.heroLeadPromo;
      const link = heroSlidePromo.querySelector('[data-hero-link]'); if (link) link.innerHTML = dict.heroBtnPromo;
      const badge = heroSlidePromo.querySelector('[data-hero-badge]'); if (badge) badge.textContent = dict.heroBadgePromo;
    }
    const heroSlideBuild = document.querySelector('.hero-slide-build');
    if (heroSlideBuild) {
      const eye = heroSlideBuild.querySelector('[data-hero-eyebrow]'); if (eye) eye.textContent = dict.heroEyebrowBuild;
      const h1 = heroSlideBuild.querySelector('h1'); if (h1) h1.innerHTML = dict.heroTitleBuild;
      const lead = heroSlideBuild.querySelector('[data-hero-lead]'); if (lead) lead.textContent = dict.heroLeadBuild;
      const link = heroSlideBuild.querySelector('[data-hero-link]'); if (link) link.innerHTML = dict.heroBtnBuild;
      const badge = heroSlideBuild.querySelector('[data-hero-badge]'); if (badge) badge.textContent = dict.heroBadgeBuild;
    }
    const heroTopics = document.querySelectorAll('.hero-topic');
    if (heroTopics[0]) heroTopics[0].textContent = dict.heroTopicNew;
    if (heroTopics[1]) heroTopics[1].textContent = dict.heroTopicPromo;
    if (heroTopics[2]) heroTopics[2].textContent = dict.heroTopicBuild;

    // Stats strip
    const statsDivs = document.querySelectorAll('.stats-strip > div');
    if (statsDivs[0]) {
      const strong = statsDivs[0].querySelector('strong');
      if (strong) strong.textContent = '15';
      const span = statsDivs[0].querySelector('span');
      if (span) span.textContent = dict.statsMicroservices;
    }
    if (statsDivs[1]) {
      const strong = statsDivs[1].querySelector('strong');
      if (strong) strong.textContent = '14+';
      const span = statsDivs[1].querySelector('span');
      if (span) span.textContent = dict.statsDatabases;
    }
    if (statsDivs[2]) {
      const strong = statsDivs[2].querySelector('strong');
      if (strong) strong.textContent = '24/7';
      const span = statsDivs[2].querySelector('span');
      if (span) span.textContent = dict.statsBroker;
    }
    if (statsDivs[3]) {
      const strong = statsDivs[3].querySelector('strong');
      if (strong) strong.textContent = 'DEV';
      const span = statsDivs[3].querySelector('span');
      if (span) span.textContent = dict.statsDev;
    }

    // Promotions section
    const promoEye = document.querySelector('.promotion-showcase-heading .eyebrow');
    if (promoEye) promoEye.textContent = dict.promoHeadingEyebrow;
    const promoTitle = document.querySelector('#promotions-title');
    if (promoTitle) promoTitle.textContent = dict.promoHeadingTitle;
    const promoNote = document.querySelector('#promotions-rotation-note');
    if (promoNote) promoNote.textContent = dict.promoRotationNote;

    // Hot shot heading
    const hotShotEye = document.querySelector('.hot-shot-heading .eyebrow');
    if (hotShotEye) hotShotEye.textContent = dict.hotShotEyebrow;
    const hotShotTitle = document.querySelector('#hot-shot-title');
    if (hotShotTitle) hotShotTitle.textContent = dict.hotShotTitle;

    // New products heading
    const newEye = document.querySelector('#new-products .section-heading .eyebrow');
    if (newEye) newEye.textContent = dict.newProductsEyebrow;
    const newTitle = document.querySelector('#new-products-title');
    if (newTitle) newTitle.textContent = dict.newProductsTitle;
    const newNote = document.querySelector('#new-products-note');
    if (newNote) newNote.textContent = dict.newProductsNote;

    // Catalog section
    const catEye = document.querySelector('#catalog .section-heading .eyebrow');
    if (catEye) catEye.textContent = dict.catalogEyebrow;
    const catTitle = document.querySelector('#catalog-title');
    if (catTitle) catTitle.textContent = dict.catalogTitle;
    const sortLabel = document.querySelector('.catalog-sort label span');
    if (sortLabel) sortLabel.textContent = dict.catalogSortLabel;
    const sortSelect = document.querySelector('#sort-filter');
    if (sortSelect && sortSelect.options.length >= 5) {
      sortSelect.options[0].textContent = isEn ? 'Default' : 'Domyślne';
      sortSelect.options[1].textContent = isEn ? 'Price: low to high' : 'Cena: rosnąco';
      sortSelect.options[2].textContent = isEn ? 'Price: high to low' : 'Cena: malejąco';
      sortSelect.options[3].textContent = isEn ? 'Name: A–Z' : 'Nazwa: A–Z';
      sortSelect.options[4].textContent = isEn ? 'Availability: high to low' : 'Dostępność: malejąco';
    }
    const refreshBtn = document.querySelector('#refresh-products');
    if (refreshBtn) refreshBtn.textContent = dict.catalogRefresh;

    // About section
    const aboutEye = document.querySelector('#about .eyebrow');
    if (aboutEye) aboutEye.textContent = dict.aboutEyebrow;
    const aboutH2 = document.querySelector('#about h2');
    if (aboutH2) aboutH2.textContent = dict.aboutTitle;

    // Footer
    const footSub = document.querySelector('.footer-brand p');
    if (footSub) footSub.textContent = dict.footerSubtitle;
    const footLinksH3 = document.querySelector('.footer-links h3');
    if (footLinksH3) footLinksH3.textContent = dict.footerLinksTitle;
    const footTechH3 = document.querySelector('.footer-tech h3');
    if (footTechH3) footTechH3.textContent = dict.footerTechTitle;
    const footCopy = document.querySelector('.footer-bottom p');
    if (footCopy) footCopy.textContent = dict.footerCopy;

    // Delivery page
    const delHeroEye = document.querySelector('.delivery-hero .eyebrow');
    if (delHeroEye) delHeroEye.textContent = dict.deliveryHeroEyebrow;
    const delHeroH1 = document.querySelector('.delivery-hero h1');
    if (delHeroH1) delHeroH1.textContent = dict.deliveryPageTitle;
    const delHeroLead = document.querySelector('.delivery-hero > p');
    if (delHeroLead) delHeroLead.textContent = dict.deliveryPageLead;
    const delHeroBtn = document.querySelector('.delivery-hero .primary-button');
    if (delHeroBtn) delHeroBtn.innerHTML = isEn ? 'Back to store <span>→</span>' : 'Wróć do sklepu <span>→</span>';

    const delSec1Eye = document.querySelector('#delivery-methods .eyebrow');
    if (delSec1Eye) delSec1Eye.textContent = dict.deliverySec1Eye;
    const delSec1H2 = document.querySelector('#delivery-methods h2');
    if (delSec1H2) delSec1H2.textContent = dict.deliverySec1Title;
    const delSec1Lead = document.querySelector('#delivery-methods .delivery-section-heading p');
    if (delSec1Lead) delSec1Lead.textContent = dict.deliverySec1Lead;

    const delCards = document.querySelectorAll('.delivery-card');
    if (delCards.length >= 3) {
      delCards[0].querySelector('h3').textContent = dict.deliveryCard1Title;
      delCards[0].querySelector('p').textContent = dict.deliveryCard1Desc;
      delCards[0].querySelector('strong').textContent = dict.deliveryCard1Tag;
      delCards[1].querySelector('h3').textContent = dict.deliveryCard2Title;
      delCards[1].querySelector('p').textContent = dict.deliveryCard2Desc;
      delCards[1].querySelector('strong').textContent = dict.deliveryCard2Tag;
      delCards[2].querySelector('h3').textContent = dict.deliveryCard3Title;
      delCards[2].querySelector('p').textContent = dict.deliveryCard3Desc;
      delCards[2].querySelector('strong').textContent = dict.deliveryCard3Tag;
    }

    const delSec2Eye = document.querySelector('#payment-methods .eyebrow');
    if (delSec2Eye) delSec2Eye.textContent = dict.deliverySec2Eye;
    const delSec2H2 = document.querySelector('#payment-methods h2');
    if (delSec2H2) delSec2H2.textContent = dict.deliverySec2Title;
    const delPayItems = document.querySelectorAll('.delivery-info-list > div');
    if (delPayItems.length >= 3) {
      delPayItems[0].querySelector('h3').textContent = dict.deliveryPay1Title;
      delPayItems[0].querySelector('p').textContent = dict.deliveryPay1Desc;
      delPayItems[1].querySelector('h3').textContent = dict.deliveryPay2Title;
      delPayItems[1].querySelector('p').textContent = dict.deliveryPay2Desc;
      delPayItems[2].querySelector('h3').textContent = dict.deliveryPay3Title;
      delPayItems[2].querySelector('p').textContent = dict.deliveryPay3Desc;
    }

    const delNotEye = document.querySelector('.delivery-notice .eyebrow');
    if (delNotEye) delNotEye.textContent = dict.deliveryNoticeEye;
    const delNotH2 = document.querySelector('.delivery-notice h2');
    if (delNotH2) delNotH2.textContent = dict.deliveryNoticeTitle;
    const delNotP = document.querySelector('.delivery-notice p:not(.eyebrow)');
    if (delNotP) delNotP.textContent = dict.deliveryNoticeDesc;

    const delFaqEye = document.querySelector('#delivery-faq .eyebrow');
    if (delFaqEye) delFaqEye.textContent = dict.deliveryFaqEye;
    const delFaqH2 = document.querySelector('#delivery-faq h2');
    if (delFaqH2) delFaqH2.textContent = dict.deliveryFaqTitle;
    const delFaqDetails = document.querySelectorAll('.delivery-faq-list details');
    if (delFaqDetails.length >= 3) {
      delFaqDetails[0].querySelector('summary').textContent = dict.deliveryFaq1Q;
      delFaqDetails[0].querySelector('p').textContent = dict.deliveryFaq1A;
      delFaqDetails[1].querySelector('summary').textContent = dict.deliveryFaq2Q;
      delFaqDetails[1].querySelector('p').innerHTML = dict.deliveryFaq2A;
      delFaqDetails[2].querySelector('summary').textContent = dict.deliveryFaq3Q;
      delFaqDetails[2].querySelector('p').textContent = dict.deliveryFaq3A;
    }

    // Success modal
    const succEye = document.querySelector('#success-modal .eyebrow');
    if (succEye) succEye.textContent = dict.successEyebrow;
    const succTitle = document.querySelector('#success-title');
    if (succTitle) succTitle.textContent = dict.successTitle;
    const succMsg = document.querySelector('#success-message');
    if (succMsg) succMsg.textContent = dict.successMessage;
    const succHint = document.querySelector('#success-modal .form-hint');
    if (succHint) succHint.textContent = dict.successMailpitHint;
    const succBtn = document.querySelector('#success-modal .primary-button');
    if (succBtn) succBtn.textContent = dict.successMailpitBtn;

    // Compare modal
    const compEye = document.querySelector('#compare-modal .eyebrow');
    if (compEye) compEye.textContent = 'PRODUCT SERVICE';
    const compTitle = document.querySelector('#compare-dialog-title');
    if (compTitle) compTitle.textContent = dict.compareTitle;
    const compHint = document.querySelector('#compare-modal .form-hint');
    if (compHint) compHint.textContent = dict.compareSubtitle;
  }

function initHeaderLanguageToggle() {
    const existing = document.querySelector('#lang-switch-toggle');
    if (existing) {
      existing.removeEventListener('click', toggleLanguage);
      existing.addEventListener('click', toggleLanguage);
      return;
    }
    const topbar = document.querySelector('.topbar');
    if (!topbar) return;

    const button = document.createElement('button');
    button.id = 'lang-switch-toggle';
    button.className = 'lang-switch-button';
    button.type = 'button';
    button.title = state.lang === 'en' ? 'Switch language to Polish' : 'Zmień język na angielski';
    button.setAttribute('aria-label', button.title);
    button.innerHTML = `🌐 <strong id="lang-indicator">${state.lang.toUpperCase()}</strong>`;
    button.addEventListener('click', toggleLanguage);

    const cartArea = document.querySelector('#cart-area');
    if (cartArea) {
      topbar.insertBefore(button, cartArea);
    } else {
      topbar.appendChild(button);
    }
  }

export const i18n = {
    getLanguage,
    setLanguage,
    toggleLanguage,
    t,
    translateCategory,
    translateParamKey,
    translateOrderStatus,
    formatMoney,
    applyPageTranslations,
    initHeaderLanguageToggle,
    CATEGORY_NAMES,
    PARAMETER_LABELS,
    ORDER_STATUSES,
    DICTIONARY
  };
window.MiniTechI18n = i18n;

document.addEventListener('DOMContentLoaded', () => {
    initHeaderLanguageToggle();
    applyPageTranslations();
  });
