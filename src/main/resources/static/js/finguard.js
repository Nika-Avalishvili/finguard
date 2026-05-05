/* ==================================================================
 * FinGuard shared front-end helpers
 * ==================================================================
 * Loaded once from fragments/layout.html :: scripts, available on
 * every page. Pulls together JS that was previously duplicated across
 * dashboard.html, evaluation.html, alerts.html, detection.html,
 * ingestion.html, models.html, explanations.html:
 *
 *   - Chart.js dark-theme defaults + shared COLORS palette
 *   - FG.initTooltips()        — enables Bootstrap tooltips on all
 *                                 elements with data-bs-toggle="tooltip".
 *                                 Fires automatically on DOMContentLoaded.
 *   - FG.num(v)                — safe numeric coercion (null/"N/A" -> null)
 *   - FG.escapeHtml(s)         — defensive escape for innerHTML interpolation
 *   - FG.fetchJson(url, opts)  — fetch() wrapper that throws on !res.ok
 *                                 and parses JSON. Centralises error UX.
 *
 * Adding new page-level helpers here is preferred over embedding them
 * in individual <script> blocks — keeps the code reviewable and
 * prevents palette / colour / behaviour drift across pages.
 * ==================================================================*/

(function () {
    'use strict';

    // ── Shared colour palette ────────────────────────────────────
    // Tied to the CSS variables in finguard.css (--fg-primary,
    // --fg-success, etc.). Any addition here must stay in sync
    // with .badge-soft--* / .metric-tile--* variants.
    const COLORS = [
        '#6366f1', // primary (indigo)
        '#22c55e', // success (green)
        '#f59e0b', // warning (amber)
        '#ef4444', // danger  (red)
        '#06b6d4', // info    (cyan)
        '#a855f7', // purple
        '#ec4899', // pink
        '#14b8a6'  // teal
    ];

    // ── Chart.js dark-theme defaults ─────────────────────────────
    // Chart.js is loaded on dashboard.html and evaluation.html but
    // not on every page — guard so this file is safe to load
    // everywhere.
    if (typeof window !== 'undefined' && typeof window.Chart !== 'undefined') {
        Chart.defaults.color       = '#8b8fa3';   // --fg-muted
        Chart.defaults.borderColor = '#2d3140';   // --fg-border
        Chart.defaults.font.family = "'Inter', -apple-system, sans-serif";
        Chart.defaults.font.size   = 11;
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Coerce an API value to a Number. Tolerates the many shapes our
     * backend can emit: numbers, stringified numbers, "N/A", null,
     * undefined. Returns null for anything non-numeric so callers can
     * render "—" consistently.
     */
    function num(v) {
        if (v === null || v === undefined || v === 'N/A' || v === '') return null;
        const n = typeof v === 'number' ? v : Number(v);
        return Number.isFinite(n) ? n : null;
    }

    /**
     * Escape a string for safe use in innerHTML. Used wherever we
     * assemble HTML with untrusted values (e.g., LLM output, user-
     * supplied experiment names).
     */
    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g,
            c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;',
                    '"': '&quot;', "'": '&#39;' })[c]);
    }

    /**
     * fetch() wrapper that throws on non-2xx responses and parses JSON.
     * Errors carry .status and .body so UI code can render useful toasts.
     */
    async function fetchJson(url, opts) {
        const res = await fetch(url, opts);
        let body = null;
        try { body = await res.json(); } catch (_) { /* empty body is fine */ }
        if (!res.ok) {
            const err = new Error(
                (body && (body.detail || body.message || body.error)) ||
                `HTTP ${res.status}`
            );
            err.status = res.status;
            err.body = body;
            throw err;
        }
        return body;
    }

    /**
     * Enable Bootstrap tooltips on every element with
     * data-bs-toggle="tooltip". Safe to call multiple times — it
     * re-initialises cleanly. Called once automatically on
     * DOMContentLoaded; page-level code can also call it after
     * injecting dynamic HTML that contains new tooltip triggers.
     */
    function initTooltips(root) {
        if (typeof bootstrap === 'undefined' || !bootstrap.Tooltip) return;
        const scope = root || document;
        scope.querySelectorAll('[data-bs-toggle="tooltip"]').forEach(el => {
            // Dispose any previous instance so re-running doesn't stack listeners.
            const existing = bootstrap.Tooltip.getInstance(el);
            if (existing) existing.dispose();
            new bootstrap.Tooltip(el, {
                placement: 'top',
                delay: { show: 150, hide: 50 }
            });
        });
    }

    /**
     * Poll a job-status endpoint on an interval until it reports a terminal
     * phase. Replaces the three near-identical setInterval loops that used to
     * live in ingestion.html / evaluation.html / models.html.
     *
     * <pre>
     * const handle = FG.pollJob({
     *   url:   '/api/v1/evaluation/status/' + jobId,
     *   intervalMs: 2000,
     *   terminalStates: ['COMPLETED', 'FAILED', 'CANCELLED'],
     *   statePath: 'phase',         // key on the response holding the state
     *   onTick:     (status) => { ... render progress ... },
     *   onComplete: (status) => { ... final DOM update, reload, etc. ... },
     *   onError:    (err)    => { ... stop polling, show error ... }
     * });
     *
     * handle.stop();   // cancel polling early (e.g., user navigated away)
     * </pre>
     *
     * <p><b>Behaviour:</b> Fires the first poll immediately (not after one
     * interval) so the UI shows progress as quickly as possible. Network
     * errors trigger {@code onError} and stop the poll — callers decide
     * whether to restart. Terminal states are matched via a configurable
     * {@code statePath} because different backend endpoints use different
     * field names (e.g. {@code phase} vs {@code status}).</p>
     *
     * @returns {{stop: function}} a handle with a stop() method to cancel the poll.
     */
    function pollJob(options) {
        const opts = Object.assign({
            url: null,
            intervalMs: 2000,
            terminalStates: ['COMPLETED', 'FAILED', 'CANCELLED', 'DONE'],
            statePath: 'phase',
            onTick: null,
            onComplete: null,
            onError: null
        }, options || {});
        if (!opts.url) throw new Error('FG.pollJob: url is required');

        let stopped = false;
        let timerId = null;

        function terminal(status) {
            const state = status ? status[opts.statePath] : null;
            return state && opts.terminalStates.indexOf(state) >= 0;
        }

        async function tick() {
            if (stopped) return;
            try {
                const status = await fetchJson(opts.url);
                if (stopped) return;
                if (terminal(status)) {
                    stopped = true;
                    if (timerId) clearInterval(timerId);
                    if (opts.onComplete) opts.onComplete(status);
                } else {
                    if (opts.onTick) opts.onTick(status);
                }
            } catch (err) {
                stopped = true;
                if (timerId) clearInterval(timerId);
                if (opts.onError) opts.onError(err);
            }
        }

        // Fire immediately, then on the interval. Users see the first update
        // without waiting a full polling tick.
        tick();
        timerId = setInterval(tick, opts.intervalMs);

        return {
            stop() {
                stopped = true;
                if (timerId) clearInterval(timerId);
            }
        };
    }

    // ── Public namespace ─────────────────────────────────────────
    window.FG = Object.assign(window.FG || {}, {
        COLORS: COLORS,
        num: num,
        escapeHtml: escapeHtml,
        fetchJson: fetchJson,
        initTooltips: initTooltips,
        pollJob: pollJob
    });

    // Auto-init tooltips once the DOM is ready. Pages that render
    // additional tooltip triggers later (e.g., inside dynamically-
    // built progress panels) can call FG.initTooltips() again.
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => initTooltips());
    } else {
        initTooltips();
    }
})();
