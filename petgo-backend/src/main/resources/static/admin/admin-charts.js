// ===== 看板图表（V1.3.0 Story 3.4 · AB-15A，AD-9 / AD-11）=====
// 数据不走 JSON API：每张卡把 {labels, series} 内嵌在 <script type="application/json" data-chart="<cardId>">，
// 切 7 / 30 天由 htmx 整块替换 #dashboard-charts，这里在 htmx:afterSwap 销毁旧 Chart 实例后重建。
// Chart.js 4.5.x（static/admin/vendor/chart.umd.min.js，仅看板页引入，全局 Chart）。
// 口径（D-30）：values 里 null = 该日未物化 → 断点（spanGaps:false）；0 = 合法的 0，照画。
// 卡内 tab（真实用户 / 含种子、现金到账 / 含 PawCoin）纯前端切 series，不再请求。
(function () {
    window.Admin = window.Admin || {};

    var KINDS = {
        users: [
            { key: 'new_users', type: 'bar', axis: 'y' },
            { key: 'cumulative_users', type: 'line', axis: 'y1' }
        ],
        pets: [
            { key: 'new_pet_owners', type: 'bar', axis: 'y' },
            { key: 'diary_pet_owners', type: 'bar', axis: 'y' },
            { key: 'cumulative_pet_owners', type: 'line', axis: 'y1' }
        ],
        content: [
            { key: 'posting_users', type: 'bar', axis: 'y' },
            { key: 'new_posts', type: 'bar', axis: 'y' }
        ],
        engagement: [
            { key: 'interacted_posts', type: 'bar', axis: 'y' },
            { key: 'silent_posts', type: 'bar', axis: 'y' },
            { key: 'engagement_score', type: 'line', axis: 'y1' },
            { key: 'new_posts_score', type: 'line', axis: 'y1' },
            { key: 'all_posts_avg_score', type: 'line', axis: 'y1', dashed: true },
            { key: 'interacted_posts_avg_score', type: 'line', axis: 'y1', dashed: true },
            { key: 'engagement_score_all_time', type: 'line', axis: 'y1', hidden: true }
        ],
        payment: {
            cash: [
                { key: 'paying_users_cash', type: 'bar', axis: 'y' },
                { key: 'payments_cash', type: 'bar', axis: 'y' }
            ],
            pawcoin: [
                { key: 'paying_users_incl_pawcoin', type: 'bar', axis: 'y' },
                { key: 'payments_incl_pawcoin', type: 'bar', axis: 'y' }
            ]
        }
    };
    var PALETTE = ['#4f6df5', '#f5a524', '#2ec27e', '#e5484d', '#8e63d6', '#17a2b8', '#9aa0a6'];
    var MISSING_COLOR = '#b58105';

    function parseJson(card) {
        var el = card.querySelector('script[type="application/json"][data-chart]');
        if (!el) { return null; }
        try { return JSON.parse(el.textContent || '{}'); } catch (e) { return null; }
    }

    function names(card) {
        var map = {};
        card.querySelectorAll('[data-metric-name]').forEach(function (n) {
            map[n.getAttribute('data-metric-name')] = n.textContent.trim();
        });
        return map;
    }

    function missingIndexes(data) {
        var set = {};
        if (!data || !data.labels) { return set; }
        for (var i = 0; i < data.labels.length; i++) {
            var allNull = data.series.length > 0;
            for (var s = 0; s < data.series.length; s++) {
                if (data.series[s].values[i] !== null && data.series[s].values[i] !== undefined) { allNull = false; break; }
            }
            if (allNull) { set[i] = true; }
        }
        return set;
    }

    function findSeries(data, key, scope) {
        for (var i = 0; i < data.series.length; i++) {
            var s = data.series[i];
            if (s.key === key && (!scope || s.scope === scope)) { return s; }
        }
        // 非双口径指标只有 ALL
        for (var j = 0; j < data.series.length; j++) {
            if (data.series[j].key === key) { return data.series[j]; }
        }
        return null;
    }

    function specsFor(card) {
        var kind = card.getAttribute('data-kind');
        if (kind === 'payment') {
            var pay = card.getAttribute('data-pay') || 'cash';
            return KINDS.payment[pay] || KINDS.payment.cash;
        }
        return KINDS[kind] || [];
    }

    function build(card, data) {
        var scope = card.getAttribute('data-scope') || (card.querySelector('[data-scope-tabs]') ? 'REAL' : 'ALL');
        var specs = specsFor(card);
        var labelOf = names(card);
        var missing = missingIndexes(data);
        var datasets = [];
        var hasY1 = false;
        specs.forEach(function (spec, idx) {
            var s = findSeries(data, spec.key, scope);
            if (!s) { return; }
            var color = PALETTE[idx % PALETTE.length];
            if (spec.axis === 'y1') { hasY1 = true; }
            datasets.push({
                type: spec.type,
                label: labelOf[spec.key] || spec.key,
                data: s.values.map(function (v) { return v === null || v === undefined ? null : Number(v); }),
                yAxisID: spec.axis,
                backgroundColor: spec.type === 'bar' ? color + 'cc' : color,
                borderColor: color,
                borderWidth: spec.type === 'bar' ? 0 : 2,
                borderDash: spec.dashed ? [6, 4] : undefined,
                pointRadius: 2,
                tension: 0.2,
                spanGaps: false,
                hidden: !!spec.hidden
            });
        });
        var scales = {
            x: {
                ticks: {
                    autoSkip: true,
                    maxRotation: 0,
                    // 缺数据日刻度标黄（默认能力，不引插件）；卡底另有「N 日缺数据」文字
                    // 非缺日必须显式回 Chart 默认色：回 undefined 会沿用 canvas 当前 fillStyle（#000）
                    color: function (ctx) { return missing[ctx.index] ? MISSING_COLOR : Chart.defaults.color; },
                    font: function (ctx) { return missing[ctx.index] ? { weight: 'bold' } : undefined; }
                }
            },
            y: { beginAtZero: true, position: 'left' }
        };
        if (hasY1) { scales.y1 = { beginAtZero: true, position: 'right', grid: { drawOnChartArea: false } }; }
        return {
            data: { labels: data.labels, datasets: datasets },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: { mode: 'index', intersect: false },
                spanGaps: false,
                scales: scales,
                plugins: {
                    legend: { position: 'bottom', labels: { boxWidth: 10 } },
                    tooltip: {
                        callbacks: {
                            title: function (items) { return items.length ? items[0].label : ''; },
                            label: function (item) {
                                var v = item.raw;
                                return item.dataset.label + ': ' + (v === null || v === undefined ? '—' : v);
                            }
                        }
                    }
                }
            }
        };
    }

    Admin.charts = {
        instances: {},

        render: function (root) {
            if (!root || typeof Chart === 'undefined') { return; }
            // fragment 被整体替换（含 422 行内 err 替换整块图表区）时旧 canvas 已不在 DOM：先全部销毁再重建，避免实例泄漏
            this.destroy();
            var self = this;
            root.querySelectorAll('.chart-card').forEach(function (card) { self.renderCard(card); });
            this.syncRange(root);
        },

        renderCard: function (card) {
            var id = card.getAttribute('data-card');
            var canvas = card.querySelector('canvas');
            var data = parseJson(card);
            if (this.instances[id]) { this.instances[id].destroy(); delete this.instances[id]; }
            if (!canvas || !data || card.getAttribute('data-empty') === 'true') { return; }
            var cfg = build(card, data);
            this.instances[id] = new Chart(canvas, { type: 'bar', data: cfg.data, options: cfg.options });
        },

        destroy: function () {
            var self = this;
            Object.keys(this.instances).forEach(function (k) { self.instances[k].destroy(); });
            this.instances = {};
        },

        /** 范围 chip 的选中态跟随当前 fragment 的 data-range。 */
        syncRange: function (root) {
            var grid = root.querySelector('[data-range]') || root;
            var range = grid.getAttribute('data-range');
            document.querySelectorAll('[data-range-switch] [data-range]').forEach(function (b) {
                b.classList.toggle('chip--on', b.getAttribute('data-range') === range);
                b.setAttribute('aria-pressed', b.getAttribute('data-range') === range ? 'true' : 'false');
            });
        },

        markFailed: function (root) {
            root.querySelectorAll('.chart-card').forEach(function (card) {
                card.classList.add('is-failed');
                var f = card.querySelector('.chart-failed');
                if (f) { f.hidden = false; }
            });
        },

        retry: function () {
            var on = document.querySelector('[data-range-switch] .chip--on') || document.querySelector('[data-range-switch] [data-range]');
            var url = on && on.getAttribute('hx-get');
            // 带 source：hx-indicator 才会给 #dashboard-charts 加 htmx-request（骨架）
            if (url && typeof htmx !== 'undefined') { htmx.ajax('GET', url, { source: on, target: '#dashboard-charts', swap: 'innerHTML' }); }
        }
    };

    function container() { return document.getElementById('dashboard-charts'); }

    document.addEventListener('DOMContentLoaded', function () {
        var root = container();
        if (root) { Admin.charts.render(root); }

        document.body.addEventListener('htmx:afterSwap', function (e) {
            var t = e.detail && e.detail.target;
            if (t && t.id === 'dashboard-charts') { Admin.charts.render(t); }
        });
        function failed(e) {
            var t = e.detail && e.detail.target;
            if (t && t.id === 'dashboard-charts') { Admin.charts.markFailed(t); }
        }
        document.body.addEventListener('htmx:responseError', failed);
        document.body.addEventListener('htmx:sendError', failed);
        document.body.addEventListener('htmx:timeout', failed);

        // 卡内 tab / 重试：事件委托（fragment 会被替换）
        document.addEventListener('click', function (e) {
            var btn = e.target && e.target.closest ? e.target.closest('button') : null;
            if (!btn) { return; }
            var card = btn.closest('.chart-card');
            if (btn.hasAttribute('data-chart-retry')) { Admin.charts.retry(); return; }
            if (!card) { return; }
            if (btn.hasAttribute('data-scope-tab')) {
                card.setAttribute('data-scope', btn.getAttribute('data-scope-tab'));
                card.querySelectorAll('[data-scope-tab]').forEach(function (b) { b.classList.toggle('chip--on', b === btn); });
                Admin.charts.renderCard(card);
            } else if (btn.hasAttribute('data-pay-tab')) {
                card.setAttribute('data-pay', btn.getAttribute('data-pay-tab'));
                card.querySelectorAll('[data-pay-tab]').forEach(function (b) { b.classList.toggle('chip--on', b === btn); });
                Admin.charts.renderCard(card);
            }
        });
    });
})();
