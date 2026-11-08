export function generateUuid() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : ((r & 0x3) | 0x8)).toString(16);
  });
}

import { createOperatorIntents } from './operator-intents.js?v=20261027-1';
import { toast } from './panel-feedback.js?v=20261027-a42';
import { getPanelMessages } from './panel-i18n.js?v=20261026-1';
import { pollJob, showDrawer } from './panel-jobs.js?v=20261027-a42';
import { api } from './panel-services.js?v=20261027-a42';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { $, $$, escapeHtml } from './panel-utils.js?v=20261027-a42';

export async function loadSecrets() {
    try {
      const data = await api('/api/secrets');
      state.secretProfiles = data.profiles || {};
      state.secretFields = data.fields || [];
      state.secretSetupFields = data.setupFields || [];
      const d = (getPanelMessages(state.language)).dashboard;
      ['dev', 'test'].forEach(profile => {
        const quickAction = $(`button[data-action="secrets-edit-${profile}"], button[data-action="secrets-setup-${profile}"]`);
        if (quickAction) {
          const ready = state.secretProfiles[profile]?.file;
          quickAction.dataset.action = ready ? `secrets-edit-${profile}` : `secrets-setup-${profile}`;
          const title = profile === 'dev' ? d.quickSecretsDev : d.quickSecretsTest;
          const subtitle = ready ? d.quickSecretsEditSub : d.quickSecretsSetupSub;
          const st = $('strong', quickAction); if (st) st.textContent = title;
          const sm = $('small', quickAction); if (sm) sm.textContent = subtitle;
        }
      });
      if (state.actions.length) renderOperations();
    } catch (error) { toast(error.message, true); }
  }

export function renderActionCard(item) {
    const dict = getPanelMessages(state.language);
    if (item.externalUrl) {
      const buttonLabel = state.language === 'en' ? 'Open in new tab ↗' : 'Otwórz w nowej karcie ↗';
      return `<article class="operation-card external-operation-card" style="border-left: 2px solid var(--accent);" data-search="${escapeHtml(`${item.title} ${item.description} ${item.group}`.toLowerCase())}"><h4>${escapeHtml(item.title)}</h4><p>${escapeHtml(item.description)}</p><a href="${escapeHtml(item.externalUrl)}" target="_blank" rel="noopener noreferrer" class="operation-card-link"><span>📖</span> ${buttonLabel}</a></article>`;
    }
    const setup = item.kind === 'secret-setup';
    const secret = setup || item.kind === 'secret-edit';
    const alreadyCreated = setup && state.secretProfiles[item.profile]?.file;
    const buttonLabel = setup ? (alreadyCreated ? dict.operations.btnFileExists : dict.operations.btnCreateFile) : item.kind === 'secret-edit' ? dict.operations.btnOpenEditor : item.fields.length ? dict.operations.btnConfigure : dict.operations.btnRun;
    const disabled = alreadyCreated ? 'disabled' : '';
    return `<article class="operation-card ${item.danger ? 'danger' : ''} ${secret ? 'secret-operation-card' : ''}" data-search="${escapeHtml(`${item.title} ${item.description} ${item.group}`.toLowerCase())}"><h4>${escapeHtml(item.title)}</h4><p>${escapeHtml(item.description)}</p><button data-action="${item.id}" ${disabled}>${buttonLabel}</button></article>`;
  }

export function renderOperations() {
    const dict = getPanelMessages(state.language);
    const groups = [...new Set(state.actions.filter(item => item.group !== 'Testy' && item.group !== 'Tests').map(item => item.group))];
    $('#operation-group').innerHTML = `<option value="">${escapeHtml(dict.operations.allGroups)}</option>` + groups.map(group => `<option>${escapeHtml(group)}</option>`).join('');
    $('#operation-groups').innerHTML = groups.map(group => `<section class="operation-section" data-group="${escapeHtml(group)}"><h3>${escapeHtml(group)}</h3><div class="operation-grid">${state.actions.filter(item => item.group === group).map(renderActionCard).join('')}</div></section>`).join('');
    const tests = state.actions.filter(item => item.group === 'Testy' || item.group === 'Tests');
    $('#test-cards').innerHTML = tests.map(item => `<article class="test-card"><span class="test-icon">✓</span><h3>${escapeHtml(item.title)}</h3><p>${escapeHtml(item.description)}</p><button class="secondary-button" data-action="${item.id}">${escapeHtml(dict.tests.btnRun)}</button></article>`).join('');
  }

export function filterOperations() {
    const query = $('#operation-search').value.trim().toLowerCase(); const group = $('#operation-group').value;
    $$('.operation-section').forEach(section => {
      let visible = 0;
      $$('.operation-card', section).forEach(card => { const show = (!query || card.dataset.search.includes(query)) && (!group || section.dataset.group === group); card.hidden = !show; if (show) visible++; });
      section.hidden = visible === 0;
    });
  }

export function fieldHtml(field) {
    const attrs = `${field.required ? 'required' : ''} ${field.min !== undefined ? `min="${field.min}"` : ''} ${field.max !== undefined ? `max="${field.max}"` : ''}`;
    const defaultValue = field.name === 'profile' ? state.profile : field.default;
    let control;
    if (field.type === 'select' && field.name === 'language') {
      const languageLabels = {pl: ['Polski', 'PL'], en: ['English', 'EN']};
      const options = field.options.map(value => {
        const [label, code] = languageLabels[value] || [value, value.toUpperCase()];
        const selected = String(defaultValue) === String(value);
        return `<button type="button" class="choice-option ${selected ? 'selected' : ''}" data-choice-value="${escapeHtml(value)}" aria-pressed="${selected}"><strong>${escapeHtml(label)}</strong><small>${escapeHtml(code)}</small></button>`;
      }).join('');
      return `<fieldset class="choice-field"><legend>${escapeHtml(field.label)}</legend><input type="hidden" name="${field.name}" value="${escapeHtml(defaultValue || field.options[0])}"><div class="choice-grid">${options}</div></fieldset>`;
    }
    if (field.type === 'select') control = `<select name="${field.name}" ${attrs}>${field.options.map(value => `<option value="${escapeHtml(value)}" ${String(defaultValue) === String(value) ? 'selected' : ''}>${escapeHtml(value)}</option>`).join('')}</select>`;
    else if (field.type === 'textarea') control = `<textarea name="${field.name}" ${attrs}>${escapeHtml(defaultValue || '')}</textarea>`;
    else if (field.name === 'operation' || (typeof field.label === 'string' && field.label.includes('UUID'))) {
      const val = defaultValue || generateUuid();
      control = `<div style="display:flex; gap:8px; align-items:center;"><input name="${field.name}" type="text" value="${escapeHtml(val)}" ${attrs} style="flex:1; font-family:monospace; font-size:12px;"><button type="button" class="btn-mini" onclick="const inp=this.previousElementSibling; if(inp){ inp.value=(typeof crypto!=='undefined'&&crypto.randomUUID?crypto.randomUUID():'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,c=>(c==='x'?(Math.random()*16|0):((Math.random()*16|0)&0x3|0x8)).toString(16))); }" title="Wygeneruj nowy UUID operacji" style="white-space:nowrap; padding:6px 10px;">↻ Nowy UUID</button></div>`;
    } else control = `<input name="${field.name}" type="${field.type || 'text'}" value="${escapeHtml(defaultValue || '')}" ${attrs}>`;
    return `<label class="field-label">${escapeHtml(field.label)}${control}</label>`;
  }

export function selectChoice(choice) {
    const fieldset = choice.closest('.choice-field');
    const value = $('input[type="hidden"]', fieldset);
    if (value) value.value = choice.dataset.choiceValue;
    $$('.choice-option', fieldset).forEach(option => {
      const selected = option === choice;
      option.classList.toggle('selected', selected);
      option.setAttribute('aria-pressed', String(selected));
    });
  }

const SECRET_SECTIONS = {
  en: {
    'Bazy danych': 'Databases',
    'Usługi lokalne': 'Local Services',
    'Mailpit / SMTP': 'Mailpit / SMTP',
    'Kontakt': 'Contact',
    'Zaawansowane': 'Advanced',
    'Bezpieczeństwo': 'Security',
  },
  pl: {
    'Bazy danych': 'Bazy danych',
    'Usługi lokalne': 'Usługi lokalne',
    'Mailpit / SMTP': 'Mailpit / SMTP',
    'Kontakt': 'Kontakt',
    'Zaawansowane': 'Zaawansowane',
    'Bezpieczeństwo': 'Bezpieczeństwo',
  }
};

const SECRET_LABELS = {
  en: {
    PRODUCT_DB_PASSWORD: 'Product DB — password',
    ORDER_DB_PASSWORD: 'Order DB — password',
    INVENTORY_DB_PASSWORD: 'Inventory DB — password',
    PAYMENT_DB_PASSWORD: 'Payment DB — password',
    NOTIFICATION_DB_PASSWORD: 'Notification DB — password',
    CUSTOMER_DB_PASSWORD: 'Customer DB — password',
    DELIVERY_DB_PASSWORD: 'Delivery DB — password',
    PROMOTION_DB_PASSWORD: 'Promotion DB — password',
    WISHLIST_DB_PASSWORD: 'Wishlist DB — password',
    REVIEW_DB_PASSWORD: 'Review DB — password',
    RECOMMENDATION_DB_PASSWORD: 'Recommendation DB — password',
    CHAT_DB_PASSWORD: 'Chat DB — password',
    RETURNS_DB_PASSWORD: 'Returns DB — password',
    BILLING_DB_PASSWORD: 'Billing DB — password',
    GRAFANA_ADMIN_PASSWORD: 'Grafana — admin password',
    GRAFANA_ADMIN_USER: 'Grafana — admin username',
    MAIL_HOST: 'SMTP — host',
    MAIL_PORT: 'SMTP — port',
    MAIL_USERNAME: 'SMTP — username',
    MAIL_PASSWORD: 'SMTP — password',
    MAIL_FROM: 'SMTP — sender address',
    MAIL_SMTP_AUTH: 'SMTP — authentication',
    MAIL_SMTP_STARTTLS_ENABLE: 'SMTP — STARTTLS',
    CHAT_SUPPORT_EMAIL: 'Chat — support email',
    CUSTOMER_JWT_SECRET: 'Legacy JWT secret',
  },
  pl: {
    PRODUCT_DB_PASSWORD: 'Product DB — hasło',
    ORDER_DB_PASSWORD: 'Order DB — hasło',
    INVENTORY_DB_PASSWORD: 'Inventory DB — hasło',
    PAYMENT_DB_PASSWORD: 'Payment DB — hasło',
    NOTIFICATION_DB_PASSWORD: 'Notification DB — hasło',
    CUSTOMER_DB_PASSWORD: 'Customer DB — hasło',
    DELIVERY_DB_PASSWORD: 'Delivery DB — hasło',
    PROMOTION_DB_PASSWORD: 'Promotion DB — hasło',
    WISHLIST_DB_PASSWORD: 'Wishlist DB — hasło',
    REVIEW_DB_PASSWORD: 'Review DB — hasło',
    RECOMMENDATION_DB_PASSWORD: 'Recommendation DB — hasło',
    CHAT_DB_PASSWORD: 'Chat DB — hasło',
    RETURNS_DB_PASSWORD: 'Returns DB — hasło',
    BILLING_DB_PASSWORD: 'Billing DB — hasło',
    GRAFANA_ADMIN_PASSWORD: 'Grafana — hasło administratora',
    GRAFANA_ADMIN_USER: 'Grafana — użytkownik administratora',
    MAIL_HOST: 'SMTP — host',
    MAIL_PORT: 'SMTP — port',
    MAIL_USERNAME: 'SMTP — użytkownik',
    MAIL_PASSWORD: 'SMTP — hasło',
    MAIL_FROM: 'SMTP — adres nadawcy',
    MAIL_SMTP_AUTH: 'SMTP — uwierzytelnianie',
    MAIL_SMTP_STARTTLS_ENABLE: 'SMTP — STARTTLS',
    CHAT_SUPPORT_EMAIL: 'Czat — adres wsparcia',
    CUSTOMER_JWT_SECRET: 'Legacy JWT secret',
  }
};

const SECRET_NOTES = {
  en: {
    CUSTOMER_JWT_SECRET_EDIT: 'Changing this may invalidate tokens in legacy mode. Perform RSA key rotation using a separate JWT operation.',
    CUSTOMER_JWT_SECRET_SETUP: 'Minimum 32 bytes after Base64 decoding.',
  },
  pl: {
    CUSTOMER_JWT_SECRET_EDIT: 'Zmiana może unieważnić tokeny w trybie legacy. Rotację kluczy RSA wykonaj osobną operacją JWT.',
    CUSTOMER_JWT_SECRET_SETUP: 'Minimum 32 bajty po dekodowaniu Base64.',
  }
};

export function secretFieldHtml(field) {
    const isEn = state.language === 'en';
    const label = (isEn && SECRET_LABELS.en[field.name]) || field.label;
    const placeholder = field.type === 'password'
      ? (isEn ? 'New value · leave empty to keep unchanged' : 'Nowa wartość · puste = bez zmiany')
      : (isEn ? 'Leave empty to keep unchanged' : 'Puste = bez zmiany');
    let control;
    if (field.type === 'select') {
      const emptyOption = isEn ? '— keep unchanged —' : '— pozostaw bez zmian —';
      control = `<select name="${escapeHtml(field.name)}"><option value="">${emptyOption}</option>${field.options.map(value => `<option value="${escapeHtml(value)}">${escapeHtml(value)}</option>`).join('')}</select>`;
    } else {
      control = `<input name="${escapeHtml(field.name)}" type="${escapeHtml(field.type || 'text')}" autocomplete="new-password" placeholder="${escapeHtml(placeholder)}" maxlength="4096">`;
    }
    const noteText = isEn
      ? (field.name === 'CUSTOMER_JWT_SECRET' ? SECRET_NOTES.en.CUSTOMER_JWT_SECRET_EDIT : (SECRET_NOTES.en[field.name] || field.note))
      : (SECRET_NOTES.pl[field.name] || field.note);
    const note = noteText ? `<small class="field-note">${escapeHtml(noteText)}</small>` : '';
    return `<label class="field-label">${escapeHtml(label)}${control}${note}</label>`;
  }

export function secretSetupFieldHtml(field) {
    const isEn = state.language === 'en';
    let label = (isEn && SECRET_LABELS.en[field.name]) || field.label;
    if (field.name === 'CUSTOMER_JWT_SECRET') {
      label = isEn ? 'Legacy JWT secret (Base64)' : 'Legacy JWT secret (Base64)';
    }
    const attrs = `${field.required ? 'required' : ''} ${field.min !== undefined ? `min="${field.min}"` : ''} ${field.max !== undefined ? `max="${field.max}"` : ''}`;
    const defaultValue = field.default ?? '';
    let control;
    if (field.type === 'select') {
      control = `<select name="${escapeHtml(field.name)}" ${attrs}>${field.options.map(value => `<option value="${escapeHtml(value)}" ${String(defaultValue) === String(value) ? 'selected' : ''}>${escapeHtml(value)}</option>`).join('')}</select>`;
    } else {
      const placeholder = field.type === 'password'
        ? (isEn ? 'Value will be encrypted' : 'Wartość zostanie zaszyfrowana')
        : (isEn ? 'Optional — use default' : 'Opcjonalne — użyj wartości domyślnej');
      control = `<input name="${escapeHtml(field.name)}" type="${escapeHtml(field.type || 'text')}" value="${escapeHtml(defaultValue)}" ${attrs} autocomplete="new-password" placeholder="${escapeHtml(placeholder)}" maxlength="4096">`;
    }
    const noteText = isEn
      ? (field.name === 'CUSTOMER_JWT_SECRET' ? SECRET_NOTES.en.CUSTOMER_JWT_SECRET_SETUP : (SECRET_NOTES.en[field.name] || field.note))
      : (SECRET_NOTES.pl[field.name] || field.note);
    const note = noteText ? `<small class="field-note">${escapeHtml(noteText)}</small>` : '';
    return `<label class="field-label">${escapeHtml(label)}${control}${note}</label>`;
  }

export function openSecretEditor(profile) {
    const dict = getPanelMessages(state.language);
    const status = state.secretProfiles[profile] || {};
    const fields = state.secretFields;
    if (!fields.length) return toast(state.language === 'en' ? 'Could not load secret fields.' : 'Nie udało się pobrać pól sekretów.', true);
    const dialog = $('#secret-dialog');
    dialog.dataset.mode = 'edit';
    dialog.dataset.profile = profile;
    $('#secret-dialog-title').textContent = state.language === 'en' ? `${profile.toUpperCase()} Profile Secrets` : `Sekrety profilu ${profile.toUpperCase()}`;
    const keyOk = status.key ? 'OK' : (state.language === 'en' ? 'MISSING' : 'BRAK');
    const fileOk = status.file ? 'OK' : (state.language === 'en' ? 'MISSING' : 'BRAK');
    $('#secret-dialog-status').textContent = state.language === 'en' ? `Age key: ${keyOk} · Encrypted file: ${fileOk}` : `Klucz age: ${keyOk} · plik zaszyfrowany: ${fileOk}`;
    $('#secret-dialog-warning').textContent = status.key && status.file
      ? (state.language === 'en' ? 'Current values are not displayed. Fill in only the fields you wish to update; empty fields remain unchanged.' : 'Aktualne wartości są niewidoczne. Wypełnij tylko pola, które chcesz zmienić; puste pola pozostaną bez zmian.')
      : (state.language === 'en' ? 'Vault is incomplete for this profile. Use age init and secret creator first.' : 'Brak kompletnego skarbca tego profilu. Najpierw użyj inicjalizacji i kreatora sekretów w Operacjach albo Terminalu WSL.');
    const sections = [...new Set(fields.map(field => field.section || 'Sekrety'))];
    const isEn = state.language === 'en';
    $('#secret-fields').innerHTML = sections.map(section => `<fieldset class="secret-fieldset"><legend>${escapeHtml((isEn && SECRET_SECTIONS.en[section]) || section)}</legend>${fields.filter(field => field.section === section).map(secretFieldHtml).join('')}</fieldset>`).join('');
    const secCancel = $('#secret-dialog .modal-cancel'); if (secCancel) secCancel.textContent = dict.modals.btnCancel;
    $('#secret-confirm').checked = false;
    $('#secret-confirm-label').textContent = dict.modals.secretSaveConfirm;
    $('#secret-submit').textContent = dict.modals.secretSaveSubmit;
    $('#secret-submit').disabled = !(status.key && status.file);
    dialog.showModal();
  }

export function openSecretSetup(profile) {
    const dict = getPanelMessages(state.language);
    const status = state.secretProfiles[profile] || {};
    const fields = state.secretSetupFields;
    if (!fields.length) return toast(state.language === 'en' ? 'Could not load setup fields.' : 'Nie udało się pobrać pól pierwszej konfiguracji.', true);
    if (status.file) return toast(state.language === 'en' ? `Secrets file for ${profile.toUpperCase()} already exists. Use the editor.` : `Plik sekretów profilu ${profile.toUpperCase()} już istnieje. Użyj edytora.`, true);
    const dialog = $('#secret-dialog');
    dialog.dataset.mode = 'setup';
    dialog.dataset.profile = profile;
    $('#secret-dialog-title').textContent = state.language === 'en' ? `Create ${profile.toUpperCase()} Secrets` : `Utwórz sekrety ${profile.toUpperCase()}`;
    const keyStatus = status.key ? 'OK' : (state.language === 'en' ? 'will be generated' : 'zostanie utworzony');
    const fileStatus = state.language === 'en' ? 'will be created' : 'zostanie utworzony';
    $('#secret-dialog-status').textContent = state.language === 'en' ? `Age key: ${keyStatus} · Encrypted file: ${fileStatus}` : `Klucz age: ${keyStatus} · plik zaszyfrowany: ${fileStatus}`;
    $('#secret-dialog-warning').textContent = state.language === 'en'
      ? 'Initial setup wizard. For existing PostgreSQL volumes, enter existing passwords. For new instances, you may provide custom passwords. Passwords are never logged.'
      : 'To jest pierwsza konfiguracja. Dla istniejących wolumenów PostgreSQL wpisz dokładnie dotychczasowe hasła; dla nowego środowiska możesz ustawić własne silne wartości. Hasła nie trafią do logu zadania.';
    const sections = [...new Set(fields.map(field => field.section || 'Sekrety'))];
    const isEn = state.language === 'en';
    $('#secret-fields').innerHTML = sections.map(section => `<fieldset class="secret-fieldset"><legend>${escapeHtml((isEn && SECRET_SECTIONS.en[section]) || section)}</legend>${fields.filter(field => field.section === section).map(secretSetupFieldHtml).join('')}</fieldset>`).join('');
    const secCancel = $('#secret-dialog .modal-cancel'); if (secCancel) secCancel.textContent = dict.modals.btnCancel;
    $('#secret-confirm').checked = false;
    $('#secret-confirm-label').textContent = dict.modals.secretSaveConfirm;
    $('#secret-submit').textContent = dict.modals.secretSetupSubmit;
    $('#secret-submit').disabled = false;
    dialog.showModal();
  }

export function openAction(actionOrId, presetValues = null) {
    const actionId = typeof actionOrId === 'object' && actionOrId !== null ? actionOrId.id : actionOrId;
    const dict = getPanelMessages(state.language);
    const item = state.actions.find(entry => entry.id === actionId); if (!item) return;
    if (item.kind === 'secret-edit') return openSecretEditor(item.profile);
    if (item.kind === 'secret-setup') return openSecretSetup(item.profile);
    const dialog = $('#action-dialog'); dialog.dataset.action = item.id;
    $('#dialog-group').textContent = item.group; $('#dialog-title').textContent = item.title; $('#dialog-description').textContent = item.description;
    const fieldsToRender = item.fields.map(f => {
      if (presetValues && presetValues[f.name] !== undefined) {
        return { ...f, default: presetValues[f.name] };
      }
      return f;
    });
    $('#dialog-fields').innerHTML = fieldsToRender.map(fieldHtml).join('') || `<p class="muted">${escapeHtml(dict.modals.noParams)}</p>`;
    const danger = $('#danger-confirm');
    danger.hidden = !item.danger;
    danger.innerHTML = `<input type="checkbox"> ${escapeHtml(dict.modals.dangerConfirm)}`;
    const submitBtn = $('#action-form button[type="submit"]');
    if (submitBtn) submitBtn.textContent = dict.modals.btnRun;
    const cancelBtn = $('#action-dialog .modal-cancel');
    if (cancelBtn) cancelBtn.textContent = dict.modals.btnCancel;
    dialog.showModal();
  }

export async function submitAction(event) {
    event.preventDefault(); const dialog = $('#action-dialog'); const item = state.actions.find(entry => entry.id === dialog.dataset.action);
    const dict = getPanelMessages(state.language);
    const values = Object.fromEntries(new FormData(event.currentTarget).entries()); const confirmed = !item.danger || $('input', $('#danger-confirm')).checked;
    if (item.danger && !confirmed) return toast(dict.modals.confirmOpToast, true);
    try {
      const job = await api('/api/jobs', {method:'POST', body:JSON.stringify({action:item.id, values, confirmed})});
      dialog.close(); showDrawer(job); pollJob(job.id); toast(`${dict.toasts.jobStarted}: ${item.title}`);
    } catch (error) { toast(error.message, true); }
  }

export async function submitSecretEdit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const dialog = $('#secret-dialog');
    if (!$('#secret-confirm').checked) return toast(state.language === 'en' ? 'Please confirm saving changed secrets.' : 'Potwierdź zapis zmienionych sekretów.', true);
    const values = Object.fromEntries([...new FormData(form).entries()].filter(([name, value]) => name !== 'confirm' && value !== ''));
    try {
      const result = await api('/api/secrets/update', {method: 'POST', body: JSON.stringify({profile: dialog.dataset.profile, values, confirmed: true})});
      dialog.close();
      await loadSecrets();
      toast(result.message || (state.language === 'en' ? 'Secrets saved encrypted.' : 'Sekrety zapisano zaszyfrowane.'));
    } catch (error) { toast(error.message, true); }
  }

export async function submitSecretSetup(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const dialog = $('#secret-dialog');
    if (!$('#secret-confirm').checked) return toast(state.language === 'en' ? 'Please confirm creating encrypted secrets file.' : 'Potwierdź utworzenie zaszyfrowanego pliku sekretów.', true);
    const values = Object.fromEntries([...new FormData(form).entries()].filter(([name]) => name !== 'confirm'));
    try {
      const result = await api('/api/secrets/setup', {method: 'POST', body: JSON.stringify({profile: dialog.dataset.profile, values, confirmed: true})});
      dialog.close();
      await loadSecrets();
      toast(result.message || (state.language === 'en' ? 'Secrets created and encrypted.' : 'Sekrety utworzono zaszyfrowane.'));
    } catch (error) { toast(error.message, true); }
  }

export function openExternalAppDialog(appKey) {
    const app = EXTERNAL_APPS[appKey];
    if (!app) return;
    const lang = state.language === 'en' ? 'en' : 'pl';
    runtime.currentExternalAppUrl = app.url;

    const dialog = $('#external-link-dialog');
    if (!dialog) return;

    $('#external-modal-icon').textContent = app.icon;
    $('#external-modal-eyebrow').textContent = app.eyebrow[lang];
    $('#external-modal-title').textContent = app.title[lang];
    $('#external-modal-desc').innerHTML = app.desc[lang];
    $('#external-modal-url').textContent = app.url;
    $('#external-modal-cancel').textContent = lang === 'en' ? 'Stay (Cancel)' : 'Zostań (Anuluj)';
    $('#external-modal-confirm').textContent = lang === 'en' ? 'Open in new tab ↗' : 'Otwórz stronę ↗';

    dialog.showModal();
  }

export function submitSecretForm(event) {
    if ($('#secret-dialog').dataset.mode === 'setup') return submitSecretSetup(event);
    return submitSecretEdit(event);
  }

export const {readPendingCorrection, correctionIntent, finishCorrectionIntent,
    readPendingStock, stockIntent, finishStockIntent} = createOperatorIntents({getProfile: () => state.profile});

export const EXTERNAL_APPS = {
    swagger: {
      name: 'Swagger UI',
      icon: '📖',
      url: 'http://127.0.0.1:18080/swagger-ui.html',
      eyebrow: { pl: 'DOKUMENTACJA OPENAPI & TESTY API', en: 'OPENAPI DOCS & API TESTING' },
      title: { pl: 'Przejście do Swagger UI', en: 'Navigate to Swagger UI' },
      desc: {
        pl: 'Za chwilę przejdziesz do interaktywnej dokumentacji <strong>Swagger UI</strong> (port :18080) z zagregowaną listą wszystkich 15 mikroserwisów. Czy chcesz otworzyć ją w nowej karcie?',
        en: 'You are about to navigate to <strong>Swagger UI</strong> documentation (port :18080) aggregated for all 15 microservices. Would you like to open it in a new browser tab?'
      }
    },
    grafana: {
      name: 'Grafana',
      icon: '📊',
      url: 'http://127.0.0.1:13000',
      eyebrow: { pl: 'PULPITY METRYK & MONITORING', en: 'METRICS & MONITORING' },
      title: { pl: 'Przejście do panelu Grafana', en: 'Navigate to Grafana Dashboard' },
      desc: {
        pl: 'Za chwilę przejdziesz na zewnętrzną stronę narzędzia <strong>Grafana</strong> (port :13000) do wizualizacji metryk i wykresów mikroserwisów. Czy chcesz otworzyć ją w nowej karcie?',
        en: 'You are about to navigate to external <strong>Grafana</strong> monitoring dashboard (port :13000). Would you like to open it in a new browser tab?'
      }
    },
    prometheus: {
      name: 'Prometheus',
      icon: '🔥',
      url: 'http://127.0.0.1:19090',
      eyebrow: { pl: 'ZBIERACZ METRYK & ALERTING', en: 'METRICS SCRAPER & ALERTS' },
      title: { pl: 'Przejście do panelu Prometheus', en: 'Navigate to Prometheus' },
      desc: {
        pl: 'Za chwilę przejdziesz na zewnętrzną stronę narzędzia <strong>Prometheus</strong> (port :19090) do badania metryk i celów (Targets/PromQL). Czy chcesz otworzyć ją w nowej karcie?',
        en: 'You are about to navigate to external <strong>Prometheus</strong> metrics scraper (port :19090). Would you like to open it in a new browser tab?'
      }
    }
  };

export const expandedInvoiceCorrections = new Set();
