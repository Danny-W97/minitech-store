import { toast } from './panel-feedback.js?v=20261027-a42';
import { getPanelMessages } from './panel-i18n.js?v=20261026-1';
import { api, fetchListPage, pageControls, reloadFiltered } from './panel-services.js?v=20261027-a42';
import { state } from './panel-state.js?v=20261027-a42';
import { $, escapeHtml } from './panel-utils.js?v=20261027-a42';

export function statusLabel(value) {
    if (state.language === 'en') return value === 'NEW' ? 'Pending' : value === 'ANSWERED' ? 'Answered' : value || '—';
    return value === 'NEW' ? 'Oczekuje' : value === 'ANSWERED' ? 'Odpowiedziano' : value || '—';
  }

export async function loadChat(silent = false) {
    const dict = getPanelMessages(state.language);
    try {
      let filters = $('#chat-list-filters');
      if (!filters) {
        filters = document.createElement('div');
        filters.id = 'chat-list-filters';
        filters.className = 'chat-filters';
        filters.innerHTML = '<div class="chat-search-wrap"><span class="chat-search-icon">⌕</span><input id="chat-page-search" class="chat-search-input" aria-label="Search messages" placeholder="Szukaj / Search"></div><div class="chat-filter-actions"><select id="chat-page-status" class="chat-status-select" aria-label="Message status"><option value="ALL">Wszystkie / All</option><option value="NEW">Nowe / New</option><option value="ANSWERED">Odpowiedziano / Answered</option></select></div>';
        const list = $('#conversation-list');
        const sidebar = list?.closest('.chat-sidebar');
        if (sidebar) {
          sidebar.prepend(filters);
        } else {
          list?.before(filters);
        }
      }
      if (filters && !filters.dataset.bound) {
        filters.dataset.bound = 'true';
        filters.oninput = () => reloadFiltered('chat', () => loadChat());
        filters.onchange = () => reloadFiltered('chat', () => loadChat());
      }
      const data = await fetchListPage('chat', '/api/chat/messages', { search: $('#chat-page-search').value.trim(), status: $('#chat-page-status').value });
      if (data.stale) return;
      pageControls('chat', $('#conversation-list'), () => loadChat());
      const messages = data.messages || [];
      const pending = messages.filter(item => item.status === 'NEW').length;
      const badge = $('#chat-badge');
      if (badge) {
        badge.hidden = pending === 0;
        badge.textContent = pending;
        badge.title = state.language === 'en' ? 'Pending messages on this page' : 'Oczekujące wiadomości na tej stronie';
      }

      const list = $('#conversation-list');
      if (list) {
        list.innerHTML = messages.map(item => `
          <button class="conversation-card ${state.selectedChat?.id === item.id ? 'active' : ''}" data-chat="${item.id}">
            <header>
              <strong>${escapeHtml(item.customerName)}</strong>
              <time>${escapeHtml(statusLabel(item.status))}</time>
            </header>
            <p>${escapeHtml(item.subject || (state.language === 'en' ? 'Customer inquiry' : 'Pytanie klienta'))}</p>
            <p>${escapeHtml(item.message)}</p>
          </button>
        `).join('') || `<div class="empty-state">${escapeHtml(dict.chat.noMessages)}</div>`;
      }

      if (state.selectedChat) {
        const updated = messages.find(item => item.id === state.selectedChat.id);
        if (updated) {
          const changed = updated.lastEntryId !== state.selectedChat.lastEntryId || updated.status !== state.selectedChat.status || updated.replyMessage !== state.selectedChat.replyMessage;
          state.selectedChat = updated;
          if (changed) {
            renderConversation(true);
          }
        }
      }
      if (state.selectedChat) loadChatHistory();
      state.chatMessages = messages;
    } catch (error) {
      if (!silent) {
        const list = $('#conversation-list');
        let errMsg = error.message;
        if (state.language === 'en') {
          if (errMsg.includes('Weryfikacja dostępu operatora jest niedostępna')) {
            errMsg = 'Operator access verification is unavailable.';
          } else if (errMsg.includes('Wymagana zweryfikowana sesja operatora')) {
            errMsg = 'Verified operator session required.';
          } else if (errMsg.includes('Nie można zweryfikować dostępu operatora')) {
            errMsg = 'Operator access cannot be verified.';
          }
        }
        if (list) list.innerHTML = `<div class="empty-state">${escapeHtml(errMsg)}</div>`;
      }
    }
  }

export function renderConversation(preserveDraft = true) {
    const item = state.selectedChat; if (!item) return;
    const dict = getPanelMessages(state.language);
    const currentTa = $('#conversation-detail textarea[name="message"]');
    const isFocused = document.activeElement === currentTa;
    const savedDraft=preserveDraft?null:state.chatDrafts?.get(item.id);
    const draft = preserveDraft ? (currentTa?.value || '') : (savedDraft?.message || '');
    const pendingKey = preserveDraft?currentTa?.form?.dataset.requestId:savedDraft?.requestId;
    const pendingBody = preserveDraft?currentTa?.form?.dataset.requestBody:savedDraft?.requestBody;
    const selStart = currentTa ? currentTa.selectionStart : null;
    const selEnd = currentTa ? currentTa.selectionEnd : null;

    $('#conversation-detail').innerHTML = `
      <p class="eyebrow accent">${state.language === 'en' ? 'CONVERSATION' : 'ROZMOWA'} #${item.id}</p>
      <h3>${escapeHtml(item.subject || (state.language === 'en' ? 'Customer inquiry' : 'Pytanie klienta'))}</h3>
      <p class="customer-meta">${escapeHtml(item.customerName)} · ${escapeHtml(item.customerEmail)}</p>
      <div class="message-bubble">${escapeHtml(item.message)}</div>
      <div id="conversation-history">${item.replyMessage ? `<div class="message-bubble reply"><strong>MiniTech Store</strong><br>${escapeHtml(item.replyMessage)}</div>` : ''}</div>
      <button type="button" class="secondary-button" data-chat-older hidden>${state.language==='en'?'Load older messages':'Wczytaj starsze wiadomości'}</button>
      <p id="conversation-history-status" role="status"></p>
      <form class="reply-form">
        <label class="field-label">${escapeHtml(dict.chat.replyFormLabel)}
          <textarea name="message" required maxlength="4000" placeholder="${escapeHtml(dict.chat.replyPlaceholder)}"></textarea>
        </label>
        <button class="primary-button" ${state.chatReplyPendingId===item.id?'disabled':''}>${escapeHtml(dict.chat.btnSendReply)}</button>
      </form>
    `;

    if (draft) {
      const newTa = $('#conversation-detail textarea[name="message"]');
      if (newTa) {
        newTa.value = draft;
        if (pendingKey) { newTa.form.dataset.requestId=pendingKey;newTa.form.dataset.requestBody=pendingBody || ''; }
        if (isFocused) {
          newTa.focus();
          if (selStart !== null && selEnd !== null) {
            newTa.setSelectionRange(selStart, selEnd);
          }
        }
      }
    }
    loadChatHistory();
  }

export async function loadChatHistory(before=0) {
    const id=state.selectedChat?.id;if(!id)return;
    try {
      const result=await api(`/api/chat/${id}/entries?before=${before}&size=50`);
      if(state.selectedChat?.id!==id)return;
      if(state.chatHistoryId!==id) { state.chatHistoryId=id;state.chatHistoryEntries=new Map();state.chatHistoryBefore=null; }
      const previousLast=Math.max(0,...[...state.chatHistoryEntries.values()].map(entry=>Number(entry.id)));
      const latestFirst=Math.min(...(result.entries || []).map(entry=>Number(entry.id)));
      if(!state.chatHistoryEntries.size || before || (result.hasMore && previousLast<latestFirst)) state.chatHistoryBefore=result.nextCursor;
      for(const entry of result.entries || []) state.chatHistoryEntries.set(entry.id,entry);
      const entries=[...state.chatHistoryEntries.values()].filter(entry=>!entry.initialMessage).sort((a,b)=>a.id-b.id);
      const target=$('#conversation-history');
      if(target)target.innerHTML=entries.map(entry=>`<div class="message-bubble ${entry.author==='SUPPORT'?'reply':''}"><strong>${entry.author==='SUPPORT'?'MiniTech Store':escapeHtml(state.selectedChat.customerName)}</strong><br>${escapeHtml(entry.message)}<br><small>#${entry.id} · ${escapeHtml(entry.createdAt || '')} · ${escapeHtml(entry.notificationStatus || '')}</small></div>`).join('');
      const older=$('[data-chat-older]');if(older)older.hidden=!state.chatHistoryBefore;
      const status=$('#conversation-history-status');if(status)status.textContent='';
    } catch(error) {
      if(state.selectedChat?.id!==id)return;
      const status=$('#conversation-history-status');if(status)status.textContent=state.language==='en'?'History refresh failed. The last view was preserved.':'Nie udało się odświeżyć historii. Zachowano ostatni widok.';
    }
  }

export async function sendReply(event) {
    if (!event.target.matches('.reply-form')) return;
    event.preventDefault();
    const form = event.target;
    const message = new FormData(form).get('message');
    const id=state.selectedChat.id;
    if(state.chatReplyPendingId===id)return;
    state.chatReplyPendingId=id;
    if (!form.dataset.requestId || form.dataset.requestBody!==message) { form.dataset.requestId=crypto.randomUUID();form.dataset.requestBody=message; }
    const button=form.querySelector('button');button.disabled=true;
    const dict = getPanelMessages(state.language);
    try {
      await api(`/api/chat/${id}/reply`, {method:'POST', body:JSON.stringify({message,requestId:form.dataset.requestId})});
      toast(dict.chat.replySuccess);
      const savedDraft=state.chatDrafts?.get(id);
      if(savedDraft?.message===message && savedDraft?.requestId===form.dataset.requestId)state.chatDrafts.delete(id);
      const activeForm=state.selectedChat?.id===id?$('#conversation-detail .reply-form'):null;
      const ta = activeForm?.querySelector('textarea[name="message"]');
      if (ta && ta.value===message && activeForm.dataset.requestId===form.dataset.requestId) { ta.value = '';delete activeForm.dataset.requestId;delete activeForm.dataset.requestBody; }
      await loadChat();
    } catch (error) {
      toast(error.message, true);
    } finally {
      if(state.chatReplyPendingId===id)state.chatReplyPendingId=null;
      if(button.isConnected)button.disabled=false;
      if(state.selectedChat?.id===id) { const current=$('#conversation-detail .reply-form button');if(current)current.disabled=false; }
    }
  }
