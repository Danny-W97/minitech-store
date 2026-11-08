// One in-flight turn, private bounded history and no delivery after reset/expiry.
export function createAssistantConversation({request, review = null, onStart = () => {}, onReply = () => {}, onCancel = () => {},
  onError = () => {}, onPending = () => {}}) {
  let history = [];
  let active = null;
  let stopped = false;

  function reset({stop = false} = {}) {
    const previous = active;
    active = null;
    history = [];
    stopped = stopped || stop;
    previous?.controller.abort();
    onPending(false, stopped);
  }

  async function send(value) {
    const message = String(value ?? '').trim();
    if (!message || active || stopped) return false;
    const turn = {controller: new AbortController()};
    active = turn;
    onPending(true, false);
    const view = onStart(message);
    try {
      // The server appends `message` itself. History contains only completed turns.
      const input = {message, history: history.map(item => ({...item}))};
      const body = review ? await review(input, {signal: turn.controller.signal}) : input;
      if (active !== turn || stopped) return false;
      if (!body) {onCancel(view); return false;}
      const response = await request(body,
        {signal: turn.controller.signal});
      if (active !== turn || stopped) return false;
      if (typeof response?.reply !== 'string' || !response.reply.trim())
        throw new Error('Nieprawidłowa odpowiedź Asystenta / Invalid assistant reply.');
      history = [...history, {role: 'user', content: message},
        {role: 'assistant', content: response.reply}].slice(-10);
      onReply(response.reply, view);
      return true;
    } catch (error) {
      if (active !== turn || stopped) return false;
      onError(error, view);
      return false;
    } finally {
      // A previous turn cannot unlock the button belonging to a newer turn.
      if (active === turn) {
        active = null;
        onPending(false, stopped);
      }
    }
  }

  return Object.freeze({send, reset, stop: () => reset({stop: true}),
    get busy() {return active !== null;}, get stopped() {return stopped;}});
}
