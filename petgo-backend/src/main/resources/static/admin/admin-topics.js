// ===== 话题管理页（V1.4.2 social-topic · Story 6.1 · AB-10B，UI 稿 F1–F9）=====
// 只在 /admin/topics 引入（admin-core.js、admin-drawer.js 之后）。无第三方依赖、无构建链。
// 用户可见文案一律来自模板（#{…} 渲染进 DOM / data-* 属性），本文件不写任何文案字面量。
//
// ① 列表刷新：监听 admin:topic-list-refresh（服务端 HX-Trigger；新建成功时载荷 {focus:<id>}）。
//    htmx 1.9 的 hx-vals 拿不到事件载荷，所以不用隐形 span：这里按「当前筛选 + 当前页」（或 focus）自己重拉 #topics-rows。
// ② 行内「编辑」：与点行同一入口（Admin.drawer.open + rowEl，地址栏 ?open= 跟着走）。
// ③ 首页话题行开关：点击即乐观切换，请求失败（422 满 5 个 / 403）弹回；序号框只接受 1..N，越界行下报错且不发请求。
// ④ 新建：名称 300ms 防抖查重（hx-get 在模板上）→ 黄条出现时给表单挂原生 confirm（data-confirm）并记下确认的名字；
//    改名即撤销；服务端 422「需确认升级」时自动补查一次，让黄条出现。
// ⑤ 编辑：显示写法只改大小写（与服务端同一判断：NFC + 小写后相等），不合规即时提示并禁用「保存」；未改动「保存」禁用。
(function () {
    var ROWS = '#topics-rows';
    var UPGRADE_CODE = 'admin.topics.upgradeWarn';

    function norm(v) {
        var s = (v || '').trim();
        try { s = s.normalize('NFC'); } catch (e) { /* 老浏览器无 normalize：退化为不做 NFC */ }
        return s.toLowerCase();
    }

    // ---------------------------------------------------------------- ① 列表刷新
    function currentPage() {
        var pager = document.querySelector(ROWS + ' .pager');
        return pager ? (pager.getAttribute('data-page') || '0') : '0';
    }

    function refreshList(focus) {
        if (typeof htmx === 'undefined') { return; }
        var form = document.querySelector('form[data-topic-filters]');
        var base = form ? (form.getAttribute('hx-get') || form.getAttribute('action')) : null;
        if (!base) { return; }
        var params = new URLSearchParams(form ? new FormData(form) : undefined);
        if (focus) { params.set('focus', String(focus)); } else { params.set('page', currentPage()); }
        var err = document.getElementById('admin-inline-error');
        var p = htmx.ajax('GET', base + '?' + params.toString(), { target: ROWS, swap: 'innerHTML' });
        var done = function () {
            if (err) { err.innerHTML = ''; }
            var flash = document.querySelector(ROWS + ' tr.row-flash');
            if (flash && flash.scrollIntoView) { flash.scrollIntoView({ block: 'center' }); }
        };
        if (p && typeof p.then === 'function') { p.then(done); }
    }

    // ---------------------------------------------------------------- ③ 序号框校验
    function orderValid(form) {
        var input = form.querySelector('input[name="order"]');
        var max = parseInt(form.getAttribute('data-max') || '0', 10);
        var raw = input ? (input.value || '').trim() : '';
        var n = /^\d+$/.test(raw) ? parseInt(raw, 10) : NaN;
        return n >= 1 && n <= max;
    }

    function showOrderError(form, show) {
        var cell = form.closest('td');
        var p = cell && cell.querySelector('[data-topic-order-err]');
        if (p) { p.hidden = !show; }
    }

    // ---------------------------------------------------------------- ④ 新建：升级确认
    function createForm() { return document.querySelector('form[data-topic-create]'); }

    function clearUpgrade(form) {
        if (!form) { return; }
        form.removeAttribute('data-confirm');
        var hidden = form.querySelector('[data-topic-upgrade-confirmed]');
        if (hidden) { hidden.value = ''; }
    }

    function applyNameCheck(slot) {
        var form = createForm();
        if (!form) { return; }
        var banner = slot.querySelector('[data-upgrade="true"]');
        var input = form.querySelector('[data-topic-name]');
        if (!banner || !input || norm(banner.getAttribute('data-upgrade-name')) !== norm(input.value)) {
            clearUpgrade(form);
            return;
        }
        form.setAttribute('data-confirm', banner.getAttribute('data-upgrade-confirm') || '');
        var hidden = form.querySelector('[data-topic-upgrade-confirmed]');
        if (hidden) { hidden.value = input.value.trim(); }
        // 服务端 422「需确认升级」那条已被黄条取代，不重复显示
        var err = document.getElementById('topic-drawer-err');
        var dup = err && err.querySelector('.inline-error[data-code="' + UPGRADE_CODE + '"]');
        if (dup) { err.innerHTML = ''; }
    }

    function recheckName() {
        var form = createForm();
        var input = form && form.querySelector('[data-topic-name]');
        if (!input || typeof htmx === 'undefined') { return; }
        var url = input.getAttribute('hx-get');
        if (!url) { return; }
        htmx.ajax('GET', url + '?name=' + encodeURIComponent(input.value || ''),
            { target: '#topic-name-check', swap: 'innerHTML' });
    }

    // ---------------------------------------------------------------- ⑤ 编辑：dirty + case-only
    function initEditForm(form) {
        if (form.getAttribute('data-topic-init')) { return; }
        form.setAttribute('data-topic-init', '1');
        var save = form.querySelector('[data-topic-save]');
        var caseOnly = form.querySelector('[data-topic-caseonly]');
        var display = form.querySelector('[data-topic-display]');
        var initial = {};
        ['displayName', 'descId', 'descEn'].forEach(function (k) {
            var el = form.querySelector('[name="' + k + '"]');
            if (el) { initial[k] = el.value; }
        });
        function sync() {
            var dirty = Object.keys(initial).some(function (k) {
                var el = form.querySelector('[name="' + k + '"]');
                return el && el.value !== initial[k];
            });
            var okCase = !display || norm(display.value) === norm(display.getAttribute('data-original'));
            if (caseOnly) { caseOnly.hidden = okCase; }
            if (save) { save.disabled = !dirty || !okCase; }
        }
        form.addEventListener('input', sync);
        form.addEventListener('change', sync);
        sync();
    }

    function initAll(root) {
        (root || document).querySelectorAll('form[data-topic-edit-form]').forEach(initEditForm);
    }

    // ---------------------------------------------------------------- 事件委托
    document.addEventListener('click', function (e) {
        var t = e.target;
        if (!t || !t.closest) { return; }
        var edit = t.closest('[data-topic-edit]');
        if (edit) {
            e.preventDefault();
            var tr = edit.closest('tr[data-drawer-url]');
            if (tr && window.Admin && window.Admin.drawer) { window.Admin.drawer.open(tr.dataset.drawerUrl, { rowEl: tr }); }
            return;
        }
        var sw = t.closest('form[data-topic-switch] button.switch');
        if (sw && !sw.disabled) {
            // 乐观切换；失败在 htmx:afterRequest 里弹回
            sw.setAttribute('aria-checked', sw.getAttribute('aria-checked') === 'true' ? 'false' : 'true');
        }
    });

    document.addEventListener('input', function (e) {
        var t = e.target;
        if (!t || !t.closest) { return; }
        if (t.matches && t.matches('[data-topic-name]')) { clearUpgrade(createForm()); return; }
        var orderForm = t.closest('form[data-topic-order]');
        if (orderForm) { showOrderError(orderForm, !orderValid(orderForm)); }
    });

    document.addEventListener('DOMContentLoaded', function () {
        initAll(document);
        document.body.addEventListener('admin:topic-list-refresh', function (e) {
            var d = (e && e.detail) || {};
            refreshList(d.focus);
        });
        document.body.addEventListener('htmx:confirm', function (e) {
            var elt = e.detail && e.detail.elt;
            var form = elt && elt.closest ? elt.closest('form[data-topic-order]') : null;
            if (!form) { return; }
            var ok = orderValid(form);
            showOrderError(form, !ok);
            if (!ok) { e.preventDefault(); } // 越界不发请求（服务端另有 422 兜底）
        });
        document.body.addEventListener('htmx:afterRequest', function (e) {
            var elt = e.detail && e.detail.elt;
            var status = e.detail && e.detail.xhr ? e.detail.xhr.status : 0;
            if (!elt || !elt.matches) { return; }
            if (elt.matches('form[data-topic-switch]') && (status < 200 || status >= 300)) {
                var sw = elt.querySelector('button.switch');
                if (sw) { sw.setAttribute('aria-checked', sw.getAttribute('aria-checked') === 'true' ? 'false' : 'true'); }
            }
            if (elt.matches('form[data-topic-order]') && (status < 200 || status >= 300)) {
                var input = elt.querySelector('input[name="order"]');
                if (input) { input.value = input.getAttribute('data-initial') || input.value; }
            }
        });
        document.body.addEventListener('htmx:afterSwap', function (e) {
            var target = e.detail && e.detail.target;
            if (!target) { return; }
            if (target.id === 'topic-name-check') { applyNameCheck(target); }
            if (target.id === 'topic-drawer-err' && target.querySelector('.inline-error[data-code="' + UPGRADE_CODE + '"]')) {
                recheckName();
            }
        });
        document.body.addEventListener('htmx:afterSettle', function () { initAll(document); });
    });
})();
