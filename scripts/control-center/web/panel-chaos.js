import { toast } from './panel-feedback.js?v=20261027-a42';
import { api } from './panel-services.js?v=20261027-a42';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { $, $$ } from './panel-utils.js?v=20261027-a42';

export function setupChaosViewOnce() {
    if (runtime.chaosViewSetupDone) return;
    runtime.chaosViewSetupDone = true;

    $$('.chaos-toggle-btn').forEach(btn => {
      btn.onclick = async () => {
        const target = btn.dataset.target;
        const action = btn.dataset.action;
        btn.disabled = true;
        try {
          await api('/api/chaos/toggle', { method: 'POST', body: JSON.stringify({ target, action }) });
          toast(state.language === 'en' ? `Chaos action applied: ${action} on ${target}` : `Zastosowano akcję chaosu: ${action} na ${target}`);
          await loadChaosView();
        } catch (err) {
          toast(err.message, true);
        } finally {
          btn.disabled = false;
        }
      };
    });

    const restoreAllBtn = $('#chaos-restore-all-btn');
    if (restoreAllBtn) {
      restoreAllBtn.onclick = async () => {
        restoreAllBtn.disabled = true;
        try {
          await api('/api/chaos/toggle', { method: 'POST', body: JSON.stringify({ target: 'all', action: 'restore_all' }) });
          toast(state.language === 'en' ? 'All services restored.' : 'Przywrócono wszystkie serwisy.');
          await loadChaosView();
        } catch (err) {
          toast(err.message, true);
        } finally {
          restoreAllBtn.disabled = false;
        }
      };
    }
  }

export async function loadChaosView() {
    setupChaosViewOnce();
    try {
      const res = await api('/api/chaos/status');
      const targets = res.targets || {};
      for (const [key, info] of Object.entries(targets)) {
        const badge = $(`#chaos-badge-${key}`);
        if (badge) {
          if (info.paused) {
            badge.className = 'chaos-status-badge warning';
            badge.textContent = state.language === 'en' ? 'PAUSED (Chaos)' : 'WSTRZYMANY (Chaos)';
          } else if (info.running) {
            badge.className = 'chaos-status-badge running';
            badge.textContent = state.language === 'en' ? 'RUNNING (Healthy)' : 'DZIAŁA (Zdrowy)';
          } else {
            badge.className = 'chaos-status-badge stopped';
            badge.textContent = state.language === 'en' ? 'STOPPED' : 'ZATRZYMANY';
          }
        }
      }
    } catch (err) {
      console.warn('Chaos status check failed:', err);
    }
  }
