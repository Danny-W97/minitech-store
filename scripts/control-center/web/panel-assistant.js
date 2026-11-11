import { toast } from './panel-feedback.js?v=20261027-a42';
import { getPanelMessages } from './panel-i18n.js?v=20261026-1';
import { api } from './panel-services.js?v=20261027-a42';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { $, escapeHtml } from './panel-utils.js?v=20261027-a42';

export function updateAiModeBadge() {
    const pill = $('#ai-mode-pill');
    const label = $('#ai-mode-label');
    if (!pill || !label) return;

    const cfg = state.aiConfig || {};
    const prov = cfg.provider || 'antigravity';
    const isEn = state.language === 'en';

    if (prov === 'cursor') {
      const modelName = cfg.model || 'Local';
      pill.className = 'ai-mode-pill custom-agent';
      label.textContent = isEn ? `Custom Agent: Ollama (${modelName})` : `Własny Agent: Ollama (${modelName})`;
      pill.title = isEn 
        ? `Connected to local LLM via endpoint: ${cfg.customEndpoint || 'http://localhost:11434/v1'}. Click to configure.`
        : `Połączono z lokalnym modelem Ollama pod adresem: ${cfg.customEndpoint || 'http://localhost:11434/v1'}. Kliknij, aby zmienić.`;
    } else if (prov === 'codex' && cfg.hasApiKey) {
      const modelName = cfg.model || 'gpt-4o';
      pill.className = 'ai-mode-pill custom-agent';
      label.textContent = isEn ? `Custom Agent: OpenAI (${modelName})` : `Własny Agent: OpenAI (${modelName})`;
      pill.title = isEn ? 'Connected to OpenAI API. Click to configure.' : 'Połączono z API OpenAI. Kliknij, aby zmienić.';
    } else if (prov === 'antigravity' && cfg.hasApiKey) {
      const modelName = cfg.model && cfg.model !== 'antigravity-expert' ? cfg.model : 'gemini-2.5-flash';
      pill.className = 'ai-mode-pill custom-agent';
      label.textContent = isEn ? `AI Model: Gemini (${modelName})` : `Model AI: Gemini (${modelName})`;
      pill.title = isEn ? 'Connected to Google Gemini API. Click to configure.' : 'Połączono z Google Gemini API. Kliknij, aby zmienić.';
    } else {
      pill.className = 'ai-mode-pill built-in';
      label.textContent = isEn ? 'Tryb wbudowany (Offline)' : 'Tryb wbudowany (Offline)';
      pill.title = isEn 
        ? 'Using built-in MiniTech architecture knowledge base (offline, no API keys). Click to configure external LLM.'
        : 'Używasz wbudowanej bazy wiedzy o architekturze 15 mikroserwisów (offline, bez kluczy API). Kliknij, aby podpiąć własnego Agenta.';
    }
  }

export async function loadAiConfig() {
    try {
      const data = await api('/api/ai/config');
      state.aiConfig = data;
      updateAiModeBadge();
    } catch (e) {
      toast(e.message, true);
    }
  }

export function refreshAiKeyStatus() {
    const isEn = state.language === 'en';
    const cfg = state.aiConfig || {};
    const matches = $('#ai-provider-select').value === cfg.provider;
    $('#ai-key-input').placeholder = matches && cfg.maskedApiKey ? cfg.maskedApiKey : (isEn ? 'Leave empty to keep the current key' : 'Puste pole zachowuje obecny klucz');
    const sources = isEn ? {sops: 'encrypted vault', environment: 'environment', none: 'no key'} : {sops: 'zaszyfrowany skarbiec', environment: 'zmienna środowiskowa', none: 'brak klucza'};
    $('#ai-key-status').textContent = matches
      ? `${isEn ? 'Current key' : 'Obecny klucz'}: ${sources[cfg.keySource] || sources.none}${cfg.credentialProfile ? ` (${cfg.credentialProfile})` : ''}${cfg.maskedApiKey ? ` · ${cfg.maskedApiKey}` : ''}`
      : (isEn ? 'The saved key of the selected provider will be used.' : 'Zostanie użyty zapisany klucz wybranego dostawcy.');
    $('#ai-key-help').textContent = isEn
      ? 'Leave empty to keep the key. New keys are saved in the active profile’s encrypted vault. Environment keys remain in memory only.'
      : 'Puste pole zachowuje klucz. Nowy klucz jest zapisywany w zaszyfrowanym skarbcu aktywnego profilu. Klucze ze zmiennych środowiskowych pozostają tylko w pamięci.';
    $('#ai-remove-key-label').textContent = isEn ? 'Remove the selected provider’s saved key from the vault' : 'Usuń zapisany klucz wybranego dostawcy ze skarbca';
  }

export function openAiSettings() {
    const dialog = $('#ai-settings-dialog');
    const cfg = state.aiConfig || {};
    const providerSelect = $('#ai-provider-select');
    if (providerSelect) providerSelect.value = cfg.provider || 'antigravity';
    const modelInput = $('#ai-model-input');
    if (modelInput) modelInput.value = cfg.model || 'antigravity-expert';
    const endpointInput = $('#ai-endpoint-input');
    if (endpointInput) endpointInput.value = cfg.customEndpoint || 'http://localhost:11434/v1';
    const keyInput = $('#ai-key-input');
    if (keyInput) keyInput.value = '';
    $('#ai-remove-key').checked = false;
    refreshAiKeyStatus();
    const ctxCb = $('#ai-context-checkbox');
    if (ctxCb) ctxCb.checked = cfg.includeContext !== false;
    dialog.showModal();
  }

export async function saveAiSettings(event) {
    event.preventDefault();
    const dict = getPanelMessages(state.language);
    const form = event.currentTarget;
    const formData = new FormData(form);
    const body = {
      provider: formData.get('provider'),
      model: formData.get('model'),
      customEndpoint: formData.get('customEndpoint'),
      apiKey: formData.get('apiKey'),
      removeApiKey: formData.get('removeApiKey') === 'on',
      includeContext: formData.get('includeContext') === 'on',
    };
    try {
      const saved = await api('/api/ai/config', { method: 'POST', body: JSON.stringify(body) });
      state.aiConfig = saved;
      $('#ai-key-input').value = '';
      $('#ai-remove-key').checked = false;
      updateAiModeBadge();
      $('#ai-settings-dialog').close();
      toast(dict.assistant.configSaved);
    } catch (error) {
      toast(error.message, true);
    }
  }

export function renderAiBubble(role, htmlContent) {
    const messages = $('#ai-messages');
    if (!messages) return null;
    const bubble = document.createElement('div');
    bubble.className = `assistant-bubble ${role === 'user' ? 'user-msg' : 'bot-msg'}`;
    bubble.innerHTML = `
      <span class="bubble-avatar">${role === 'user' ? '👤' : '✦'}</span>
      <div class="bubble-content">${htmlContent}</div>
    `;
    messages.appendChild(bubble);
    messages.scrollTop = messages.scrollHeight;
    return bubble;
  }

export function sendAiMessage(promptText) {
    if (runtime.assistantConversation.busy || runtime.assistantConversation.stopped) return;
    const input = $('#ai-input');
    const text = (promptText || input?.value || '').trim();
    if (!text) return toast(getPanelMessages(state.language).assistant.errorEmpty, true);
    if (input && !promptText) input.value = '';
    return runtime.assistantConversation.send(text);
  }

export function clearAiChat() {
    runtime.assistantConversation.reset();
    const dict = getPanelMessages(state.language);
    const a = dict.assistant;
    const messages = $('#ai-messages');
    if (messages) {
      messages.innerHTML = `
        <div class="assistant-bubble bot-msg">
          <span class="bubble-avatar">✦</span>
          <div class="bubble-content">
            <strong>${escapeHtml(a.welcomeTitle)}</strong>
            <p>${escapeHtml(a.welcomeP1)}</p>
            <p>${escapeHtml(a.welcomeP2)}</p>
          </div>
        </div>
      `;
    }
  }
