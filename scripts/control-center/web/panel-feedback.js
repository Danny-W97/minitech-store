import { $ } from './panel-utils.js?v=20261027-a42';

export function toast(message, error = false) {
    const node = $('#toast'); node.textContent = message; node.className = `toast visible${error ? ' error' : ''}`;
    clearTimeout(node.timer); node.timer = setTimeout(() => node.className = 'toast', 3600);
  }
