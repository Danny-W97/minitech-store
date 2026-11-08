import { correctionAmount } from './operator-intents.js?v=20261027-1';
import {
    clearAiChat, loadAiConfig, openAiSettings, refreshAiKeyStatus, saveAiSettings, sendAiMessage,
} from './panel-assistant.js?v=20261027-a42';
import { loadChat, loadChatHistory, renderConversation, sendReply } from './panel-chat.js?v=20261027-a42';
import { openDiagnosticReportDialog, playSound, triggerDirectAction } from './panel-diagnostics.js?v=20261027-a42';
import { toast } from './panel-feedback.js?v=20261027-a42';
import { loadHealth } from './panel-health.js?v=20261027-a42';
import { loadInvoices, renderDispatchTable } from './panel-invoices.js?v=20261027-a42';
import { loadJobs, selectJob } from './panel-jobs.js?v=20261027-a42';
import {
    correctionIntent, filterOperations, finishCorrectionIntent, openAction, openExternalAppDialog,
    selectChoice, submitAction, submitSecretForm,
} from './panel-operations.js?v=20261027-a42';
import { setView, toggleLanguage } from './panel-routing.js?v=20261027-a42';
import { api, downloadControlFile, logoutPanel } from './panel-services.js?v=20261027-a42';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { sendTerminal, startTerminal } from './panel-terminal.js?v=20261027-a42';
import { $, $$ } from './panel-utils.js?v=20261027-a42';

export function bind() {
    const logout=document.getElementById('panel-logout');
    if(logout){logout.textContent=state.language==='en'?'End session':'Zakończ sesję';logout.addEventListener('click',logoutPanel);}
    document.addEventListener('click', event => {
      const link = event.target instanceof Element ? event.target.closest('a[href^="/api/"]') : null;
      if (!link) return;
      event.preventDefault();
      downloadControlFile(link.getAttribute('href'), link.download || 'dokument.pdf').catch(error => toast(error.message, true));
    });
    document.addEventListener('click', event => {
      const choice = event.target.closest('[data-choice-value]');
      if (choice) {
        event.preventDefault();
        selectChoice(choice);
        return;
      }
      const chip = event.target.closest('.ai-chip');
      if (chip) {
        event.preventDefault();
        sendAiMessage(chip.dataset.prompt);
        return;
      }
      const fixBtn = event.target.closest('[data-fix-action]');
      if (fixBtn) {
        event.preventDefault();
        triggerDirectAction(fixBtn.dataset.fixAction);
        return;
      }
      const askAiBtn = event.target.closest('[data-ask-ai-job]');
      if (askAiBtn) {
        event.preventDefault();
        const jobId = askAiBtn.dataset.askAiJob;
        const job = state.jobs.find(j => j.id === jobId) || (state.selectedJob === jobId ? { id: jobId, title: $('#drawer-title')?.textContent || 'Zadanie', exitCode: 1, output: $('#job-output')?.textContent || '' } : null);
        if (job) {
          setView('assistant');
          const lang = state.language || 'pl';
          const promptText = lang === 'en'
            ? `An error occurred during job "${job.title}".\n\nRecent logs:\n\`\`\`\n${(job.output || '').slice(-1200)}\n\`\`\`\n\nPlease analyze what caused this error and provide step-by-step instructions to fix it.`
            : `Wystąpił błąd w zadaniu „${job.title}”.\n\nOstatnie logi:\n\`\`\`\n${(job.output || '').slice(-1200)}\n\`\`\`\n\nProszę o analizę przyczyny błędu i podanie kroków do jego naprawy.`;
          const aiInput = $('#ai-input');
          if (aiInput) {
            aiInput.value = promptText;
            aiInput.focus();
            sendAiMessage(promptText);
          }
        }
        return;
      }
      const extBtn = event.target.closest('[data-external-app]');
      if (extBtn) {
        openExternalAppDialog(extBtn.dataset.externalApp);
        return;
      }
      const view = event.target.closest('[data-view]'); if (view) setView(view.dataset.view);
      const target = event.target.closest('[data-view-target]'); if (target) setView(target.dataset.viewTarget);
      const actionButton = event.target.closest('button[data-action]');
      if (actionButton) {
        const actionId = actionButton.dataset.action;
        const presetService = actionButton.dataset.service;
        if (presetService) {
          openAction(actionId, { service: presetService });
        } else {
          openAction(actionId);
        }
      }
      const job = event.target.closest('[data-job]'); if (job) selectJob(job.dataset.job);
      if(event.target.closest('[data-chat-older]')) loadChatHistory(state.chatHistoryBefore);
      const chat = event.target.closest('[data-chat]'); if (chat) {
        const selected=state.chatMessages.find(item=>String(item.id)===chat.dataset.chat);
        if(!selected)return;
        const same=state.selectedChat?.id===selected.id;
        if(!same && state.selectedChat) {
          const ta=$('#conversation-detail textarea[name="message"]');
          state.chatDrafts ||= new Map();
          if(ta)state.chatDrafts.set(state.selectedChat.id,{message:ta.value,requestId:ta.form.dataset.requestId,requestBody:ta.form.dataset.requestBody});
        }
        state.selectedChat=selected;renderConversation(same);loadChat();
      }
    });
    $('#action-form').addEventListener('submit', submitAction); $$('#action-dialog .modal-close,#action-dialog .modal-cancel').forEach(button => button.addEventListener('click', () => $('#action-dialog').close()));
    $('#secret-form').addEventListener('submit', submitSecretForm); $$('#secret-dialog .modal-close,#secret-dialog .modal-cancel').forEach(button => button.addEventListener('click', () => $('#secret-dialog').close()));
    $('#ai-settings-form')?.addEventListener('submit', saveAiSettings); $$('#ai-settings-dialog .modal-close,#ai-settings-dialog .modal-cancel').forEach(button => button.addEventListener('click', () => { $('#ai-key-input').value = ''; $('#ai-remove-key').checked = false; $('#ai-settings-dialog').close(); }));
    $('#ai-settings-dialog')?.addEventListener('close', () => { $('#ai-key-input').value = ''; $('#ai-remove-key').checked = false; });
    $('#external-link-form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      $('#external-link-dialog')?.close();
      if (runtime.currentExternalAppUrl) {
        window.open(runtime.currentExternalAppUrl, '_blank', 'noopener,noreferrer');
      }
    });
    $$('#external-link-dialog .modal-close, #external-link-dialog .modal-cancel').forEach(button => {
      button.addEventListener('click', () => $('#external-link-dialog')?.close());
    });

    const deltaInput = $('#correction-delta');
    if (deltaInput) {
      deltaInput.addEventListener('input', () => {
        const orig = parseFloat($('#correction-orig-gross')?.value) || 0;
        const delta = parseFloat(deltaInput.value) || 0;
        const finalGross = orig + delta;
        const deltaEl = $('#calc-delta-text');
        const finalEl = $('#calc-final-text');
        if (deltaEl) {
          deltaEl.textContent = `${delta >= 0 ? '+' : ''}${delta.toFixed(2)} PLN`;
          deltaEl.style.color = delta < 0 ? '#f87171' : '#4ade80';
        }
        if (finalEl) {
          finalEl.textContent = `${finalGross.toFixed(2)} PLN`;
          finalEl.style.color = finalGross < 0 ? '#f87171' : '#4ade80';
        }
      });
    }

    const corrForm = $('#correction-form');
    if (corrForm) {
      corrForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const invId = $('#correction-invoice-id')?.value;
        const reason = ($('#correction-reason')?.value || '').trim();
        let deltaVal;
        const errEl = $('#correction-error');
        const submitBtn = $('#correction-submit-btn');

        if (!reason) {
          if (errEl) { errEl.textContent = state.language === 'en' ? 'Reason is required.' : 'Wymagana jest przyczyna korekty.'; errEl.hidden = false; }
          return;
        }
        try { deltaVal = correctionAmount($('#correction-delta')?.value); }
        catch (error) {
          if (errEl) { errEl.textContent = error.message; errEl.hidden = false; }
          return;
        }
        let intent;
        try {
          if ($('#correction-dialog').dataset.balanceVersion === '') throw new Error('Otwórz formularz ponownie i odczytaj bieżące saldo.');
          if (submitBtn) {
            submitBtn.disabled = true;
            submitBtn.textContent = state.language === 'en' ? 'Issuing...' : 'Wystawianie...';
          }
          intent = await correctionIntent(invId, reason, deltaVal, Number($('#correction-dialog').dataset.balanceVersion), $('#correction-orig-gross').value);
          $('#correction-reason').readOnly = true; $('#correction-delta').readOnly = true;
          const res = await api('/api/data/invoices/correct', {
            method: 'POST',
            body: JSON.stringify({
              invoiceId: parseInt(invId, 10),
              reason: reason,
              correctionGross: deltaVal,
              idempotencyKey: intent.idempotencyKey,
              expectedVersion: intent.expectedVersion
            })
          });

          if (res.correction?.idempotencyKey !== intent.idempotencyKey || res.correction?.balanceVersion !== intent.expectedVersion + 1)
            throw new Error('Nie potwierdzono korekty. Ponów tę samą próbę.');
          await finishCorrectionIntent(intent);
          $('#correction-dialog')?.close();
          toast(state.language === 'en' 
            ? `Correction ${res.correction?.correctionNumber || ''} issued successfully!` 
            : `Wystawiono fakturę korygującą ${res.correction?.correctionNumber || ''}!`);
          playSound('success');
          await loadInvoices(true);
        } catch (err) {
          if (err.definitive && intent) {
            await finishCorrectionIntent(intent);
            $('#correction-dialog').dataset.balanceVersion = '';
            $('#correction-reason').readOnly = false; $('#correction-delta').readOnly = false;
          }
          if (errEl) {
            errEl.textContent = err.message || (state.language === 'en' ? 'Failed to issue correction.' : 'Błąd podczas wystawiania korekty.');
            errEl.hidden = false;
          }
        } finally {
          if (submitBtn) {
            submitBtn.disabled = $('#correction-dialog').dataset.balanceVersion === '';
            submitBtn.textContent = state.language === 'en' ? 'Issue correction' : 'Wystaw korektę';
          }
        }
      });
    }
    $$('#correction-dialog .modal-close, #correction-dialog .modal-cancel').forEach(button => {
      button.addEventListener('click', () => $('#correction-dialog')?.close());
    });

    const dispatchForm = $('#dispatch-form');
    if (dispatchForm) {
      dispatchForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const errEl = $('#dispatch-error');
        if (errEl) { errEl.textContent = ''; errEl.hidden = true; }

        const docType = $('#dispatch-doc-type')?.value || 'INVOICE';
        const docId = $('#dispatch-doc-id')?.value;
        const orderId = $('#dispatch-order-id')?.value;
        const docNumber = $('#dispatch-doc-number')?.value;
        const buyerName = $('#dispatch-buyer-name')?.value;
        const recipient = $('#dispatch-recipient')?.value?.trim();
        const subject = $('#dispatch-subject')?.value?.trim();
        const customMessage = $('#dispatch-custom-message')?.value?.trim() || '';

        if (!recipient) {
          if (errEl) {
            errEl.textContent = state.language === 'en' ? 'Recipient email is required.' : 'Wymagany jest adres e-mail odbiorcy.';
            errEl.hidden = false;
          }
          return;
        }

        const submitBtn = $('#dispatch-submit-btn');
        try {
          if (submitBtn) {
            submitBtn.disabled = true;
            submitBtn.textContent = state.language === 'en' ? 'Sending...' : 'Wysyłanie...';
          }
          const res = await api('/api/data/invoices/send-email', {
            method: 'POST',
            body: JSON.stringify({
              documentType: docType,
              documentId: docId,
              orderId: orderId,
              documentNumber: docNumber,
              buyerName: buyerName,
              recipient: recipient,
              subject: subject,
              customMessage: customMessage
            })
          });

          $('#dispatch-dialog')?.close();
          toast(state.language === 'en'
            ? `Document ${docNumber} sent to ${recipient}! Check Mailpit.`
            : `Dokument ${docNumber} wysłany do ${recipient}! Sprawdź w Mailpit.`);
          playSound('success');

          try {
            const histRes = await api('/api/data/invoices/dispatch-history');
            runtime.cachedDispatchHistory = histRes.history || [];
          } catch (_) {}
          if(res.historyRecorded===false)toast(state.language==='en'?'Document sent, but local history could not be recorded. Do not resend it.':'Dokument wysłano, ale zapis lokalnej historii nie powiódł się. Nie wysyłaj go ponownie.',true);

          if (runtime.dataSubTab === 'dispatch') {
            renderDispatchTable();
          } else if (runtime.dataSubTab === 'invoices') {
            await loadInvoices(true);
          }
        } catch (err) {
          if (errEl) {
            errEl.textContent = err.message || (state.language === 'en' ? 'Failed to send document.' : 'Błąd podczas wysyłki dokumentu.');
            errEl.hidden = false;
          }
        } finally {
          if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.textContent = state.language === 'en' ? '📤 Send email' : '📤 Wyślij e-mail';
          }
        }
      });
    }
    $$('#dispatch-dialog .modal-close, #dispatch-dialog .modal-cancel').forEach(button => {
      button.addEventListener('click', () => $('#dispatch-dialog')?.close());
    });
    $('#ai-settings-button')?.addEventListener('click', openAiSettings);
    $('#ai-chat-form')?.addEventListener('submit', (e) => { e.preventDefault(); sendAiMessage(); });
    $('#ai-clear-btn')?.addEventListener('click', clearAiChat);
    $('#ai-input')?.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendAiMessage();
      }
    });
    $('#ai-provider-select')?.addEventListener('change', (e) => {
      $('#ai-key-input').value = '';
      $('#ai-remove-key').checked = false;
      refreshAiKeyStatus();
      const val = e.target.value;
      const modelInp = $('#ai-model-input');
      if (modelInp) {
        if (val === 'codex') modelInp.placeholder = 'gpt-4o / codex';
        else if (val === 'cursor') modelInp.placeholder = 'cursor-small / default';
        else modelInp.placeholder = 'antigravity-expert / gemini-2.5-flash';
      }
    });
    $('#operation-search').addEventListener('input', filterOperations); $('#operation-group').addEventListener('change', filterOperations);
    $('#health-refresh').addEventListener('click', () => loadHealth()); $('#refresh-button').addEventListener('click', () => { loadHealth(); loadJobs(); if ($('#view-chat').classList.contains('active')) loadChat(); if ($('#view-assistant').classList.contains('active')) loadAiConfig(); });
    $('#copy-report-btn')?.addEventListener('click', openDiagnosticReportDialog);
    $('#report-form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      $('#report-dialog')?.close();
    });
    $$('#report-dialog .modal-close, #report-dialog .modal-cancel, #report-cancel-btn').forEach(button => {
      button.addEventListener('click', () => $('#report-dialog')?.close());
    });
    $('#report-copy-md')?.addEventListener('click', async () => {
      if (runtime.currentReportMarkdown) {
        await navigator.clipboard.writeText(runtime.currentReportMarkdown);
        toast(state.language === 'en' ? '📋 Report copied to clipboard!' : '📋 Raport skopiowano do schowka!');
        playSound('success');
      }
    });
    const langBtn = $('#lang-switch-button'); if (langBtn) langBtn.addEventListener('click', toggleLanguage);
    $('#jobs-refresh').addEventListener('click', loadJobs); $('#chat-refresh').addEventListener('click', loadChat); $('#conversation-detail').addEventListener('submit', sendReply);
    $('#drawer-open').addEventListener('click', () => { setView('jobs'); if (state.selectedJob) selectJob(state.selectedJob); });
    $('#terminal-start').addEventListener('click', startTerminal); $('#terminal-form').addEventListener('submit', sendTerminal);
  }
