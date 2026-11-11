// Preview data stays in this dialog and RAM; no storage, HTML interpolation or background sending.
export function createAiDeliveryReview({api, dialog, language = () => 'pl'}) {
  return async (body, {signal}) => {
    const preview = await api('/api/ai/chat/preview', {method: 'POST', body: JSON.stringify(body), signal});
    if (signal.aborted) return null;
    if (preview.mode === 'local') return body;
    if (preview.mode !== 'external' || !preview.reviewToken || !preview.wirePayload || !preview.target)
      throw new Error('Nieprawidłowy podgląd AI / Invalid AI preview.');
    const en = language() === 'en';
    dialog.querySelector('[data-ai-review-title]').textContent = en ? 'Review this AI delivery' : 'Sprawdź wysyłkę tej tury AI';
    dialog.querySelector('[data-ai-review-note]').textContent = en
      ? 'Review all message content below, including earlier replies. Confirm only data you may disclose. Cancelling sends nothing. This does not erase copies held by the recipient.'
      : 'Sprawdź całą treść poniżej, także wcześniejsze odpowiedzi. Potwierdź tylko dane, które możesz udostępnić. Anulowanie niczego nie wysyła. Potwierdzenie nie usuwa kopii u odbiorcy.';
    dialog.querySelector('[data-ai-review-target]').textContent = `${preview.provider} / ${preview.model}\n${preview.target}\n${en ? 'History included' : 'Historia dołączona'}: ${preview.historyIncluded ? (en ? 'yes' : 'tak') : (en ? 'no' : 'nie')}`;
    dialog.querySelector('[data-ai-review-payload]').textContent = JSON.stringify(preview.wirePayload, null, 2);
    const confirm = dialog.querySelector('[data-ai-review-confirm]');
    const cancel = dialog.querySelector('[data-ai-review-cancel]');
    confirm.textContent = en ? 'Confirm and send this turn' : 'Potwierdzam i wysyłam tę turę';
    cancel.textContent = en ? 'Cancel' : 'Anuluj';
    const accepted = await new Promise(resolve => {
      const finish = value => {
        confirm.removeEventListener('click', yes); cancel.removeEventListener('click', no);
        dialog.removeEventListener('cancel', escape); dialog.removeEventListener('close', no);
        signal.removeEventListener('abort', no);
        if (dialog.open) dialog.close();
        dialog.querySelector('[data-ai-review-payload]').textContent = '';
        dialog.querySelector('[data-ai-review-target]').textContent = '';
        resolve(value);
      };
      const yes = () => finish(true), no = () => finish(false);
      const escape = event => {event.preventDefault(); no();};
      confirm.addEventListener('click', yes); cancel.addEventListener('click', no);
      dialog.addEventListener('cancel', escape); dialog.addEventListener('close', no);
      signal.addEventListener('abort', no, {once: true});
      dialog.showModal();
      cancel.focus();
    });
    return accepted && !signal.aborted ? {...body, reviewToken: preview.reviewToken} : null;
  };
}
