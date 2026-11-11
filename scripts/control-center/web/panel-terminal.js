import { toast } from './panel-feedback.js?v=20261027-a42';
import { api } from './panel-services.js?v=20261027-a42';
import { state } from './panel-state.js?v=20261027-a42';
import { createTerminalSession } from './terminal-session.js?v=20261027-a42';
import { $ } from './panel-utils.js?v=20261027-a42';

let terminal = null;
function controller() {
  if (!terminal) terminal = createTerminalSession({request: api,
    onSession(id) {state.terminalId = id; queueMicrotask(() => $('#terminal-input').focus());},
    onOutput(text) {const out = $('#terminal-output'); out.textContent = text; out.scrollTop = out.scrollHeight;},
    onAccepted(data) {const input = $('#terminal-input'); if (`${input.value}\n` === data) input.value = '';},
    onState({starting, writing, alive, disposed}) {
      $('#terminal-start').disabled = starting || writing || disposed;
      $('#terminal-input').disabled = starting || !alive || disposed;
      $('#terminal-form button').disabled = starting || writing || !alive || disposed;
    },
    onError: error => toast(error.message, true),
  });
  return terminal;
}
export const startTerminal = () => controller().start();
export const pollTerminal = () => controller().refresh();
export function sendTerminal(event) {
  event.preventDefault(); const value = $('#terminal-input').value;
  if (value) return controller().send(`${value}\n`);
}
export function disposeTerminal() {controller().dispose();}
