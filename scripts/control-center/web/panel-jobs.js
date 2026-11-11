import { toast } from './panel-feedback.js?v=20261027-a42';
import { getPanelMessages } from './panel-i18n.js?v=20261026-1';
import { api } from './panel-services.js?v=20261027-a42';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { $, escapeHtml, formatTime, getJobTitle } from './panel-utils.js?v=20261027-a42';

export function showDrawer(job) {
    const title = getJobTitle(job);
    $('#job-drawer').classList.add('visible');
    $('#drawer-title').textContent = title;
    $('#drawer-status').className = `status-dot ${job.status}`;
  }

export function pollJob(id) { return runtime.jobMonitor.watch(id, {notifyCompletion: true}); }

export function renderJobsList() {
    const dict = getPanelMessages(state.language);
    const historyEl = $('#job-history');
    const recentEl = $('#recent-jobs');
    if (!state.jobs || !state.jobs.length) {
      if (historyEl) {
        historyEl.className = 'job-history empty-state';
        historyEl.innerHTML = `<div class="empty-state">${escapeHtml(dict.jobs.emptyHistory)}</div>`;
      }
      if (recentEl) {
        recentEl.className = 'job-list';
        recentEl.innerHTML = `<div class="empty-state">${escapeHtml(dict.dashboard.emptyJobs)}</div>`;
      }
      return;
    }
    const html = state.jobs.map(job => {
      const title = getJobTitle(job);
      return `<button class="job-row ${state.selectedJob === job.id ? 'active' : ''}" data-job="${job.id}"><span class="job-status ${job.status}">${job.status}</span><strong>${escapeHtml(title)}</strong><small>${formatTime(job.createdAt)} · ${escapeHtml(job.command)}</small></button>`;
    }).join('');

    if (historyEl) {
      historyEl.className = 'job-history';
      historyEl.innerHTML = html;
    }
    if (recentEl) {
      recentEl.className = 'job-list';
      recentEl.innerHTML = state.jobs.slice(0, 4).map(job => {
        const title = getJobTitle(job);
        return `<div class="service-preview-row"><span class="state-light ${job.status === 'success' ? 'online' : ''}"></span><strong>${escapeHtml(title)}</strong><small>${escapeHtml(job.status)}</small></div>`;
      }).join('');
    }
  }

export async function loadJobs() {
    try {
      const data = await api('/api/jobs');
      state.jobs = data.jobs || [];
      renderJobsList();
    } catch (error) { toast(error.message, true); }
  }

export function selectJob(id) { return runtime.jobMonitor.watch(id); }
