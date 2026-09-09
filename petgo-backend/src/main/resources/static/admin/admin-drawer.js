// ===== 模板 B 抽屉（V1.3.0 Story 2.3b · AD-11）=====
// 只在模板 B 页引入（<script defer th:src="@{/admin/admin-drawer.js}">，在 admin-core.js 之后）。
// 对外 window.Admin.drawer = { open(url, {res, rowEl}), close(res), sync() }：
//   · open：htmx.ajax('GET', url, {target:'#<res>-drawer .drawer-body', swap:'innerHTML'}) 后显示抽屉 + 遮罩；
//   · 关闭三途径：✕（[data-drawer-close]）/ 点遮罩 / Esc；关闭后焦点回到触发行；
//   · 监听 admin:drawer-close（2.3a AdminHxEvents.DRAWER_CLOSE，服务端 HX-Trigger 带出；对象消失类动作才发）；
//   · 抽屉内操作成功**不自动关**（UI 稿 10-6）；
//   · ?open=<id> 页内深链（D-23：不是旧详情页 URL 的跳转规则）：DOMContentLoaded 找 tr[data-id=…] 自动 open；
//     打开 / 关闭同步 history.replaceState 的 open 参数（sync）。
// 委托：tr[data-drawer-url] 点击（排除 a / button / input / label / select 内的点击）；[data-drawer-open] 按钮式入口（5.4 新建）。
(function () {
    window.Admin = window.Admin || {};
    var state = { res: null, rowEl: null, hideTimer: null };

    function drawerEl(res) { return document.getElementById((res || state.res || 'item') + '-drawer'); }
    function maskEl() { return document.querySelector('[data-drawer-mask]'); }

    function sync() {
        try {
            var url = new URL(window.location.href);
            if (state.rowEl && state.rowEl.dataset.id) { url.searchParams.set('open', state.rowEl.dataset.id); }
            else { url.searchParams.delete('open'); }
            history.replaceState(history.state, '', url.toString());
        } catch (e) { /* 非浏览器环境 / 不支持 URL 时静默 */ }
    }

    function open(url, opts) {
        opts = opts || {};
        var res = opts.res || (opts.rowEl && opts.rowEl.closest('[data-list]') && opts.rowEl.closest('[data-list]').querySelector('.drawer') && opts.rowEl.closest('[data-list]').querySelector('.drawer').dataset.res) || 'item';
        var d = drawerEl(res);
        if (!d) { return; }
        if (state.hideTimer) { clearTimeout(state.hideTimer); state.hideTimer = null; } // 关后 200ms 内再开：取消延迟隐藏
        state.res = res;
        state.rowEl = opts.rowEl || null;
        var body = d.querySelector('.drawer-body');
        if (typeof htmx !== 'undefined' && url) {
            htmx.ajax('GET', url, { target: body, swap: 'innerHTML' });
        }
        d.hidden = false;
        var m = maskEl(); if (m) { m.hidden = false; }
        requestAnimationFrame(function () { d.classList.add('is-open'); if (m) { m.classList.add('is-open'); } });
        document.querySelectorAll('tr.is-selected').forEach(function (tr) { tr.classList.remove('is-selected'); });
        if (state.rowEl) { state.rowEl.classList.add('is-selected'); }
        sync();
        d.focus();
    }

    function close(res) {
        var d = drawerEl(res);
        if (!d) { return; }
        var m = maskEl();
        d.classList.remove('is-open'); if (m) { m.classList.remove('is-open'); }
        state.hideTimer = setTimeout(function () { d.hidden = true; if (m) { m.hidden = true; } state.hideTimer = null; }, 200);
        var row = state.rowEl;
        state.rowEl = null;
        sync();
        if (row && typeof row.focus === 'function') { row.setAttribute('tabindex', '-1'); row.focus(); }
    }

    window.Admin.drawer = { open: open, close: close, sync: sync };

    document.addEventListener('click', function (e) {
        var t = e.target;
        if (!t || !t.closest) { return; }
        if (t.closest('[data-drawer-close]')) { close(); return; }
        if (t.closest('[data-drawer-mask]')) { close(); return; }
        // 非行触发的抽屉入口（Story 5.4「＋ 新建场所」）：<button data-drawer-open="<url>" data-drawer-res="places">
        var opener = t.closest('[data-drawer-open]');
        if (opener) { open(opener.dataset.drawerOpen, { res: opener.dataset.drawerRes }); return; }
        if (t.closest('a, button, input, label, select, textarea, [data-no-drawer]')) { return; }
        var tr = t.closest('tr[data-drawer-url]');
        if (tr) { open(tr.dataset.drawerUrl, { rowEl: tr }); }
    });
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') {
            var d = document.querySelector('.drawer.is-open');
            if (d) { close(d.dataset.res); }
        }
    });
    document.addEventListener('DOMContentLoaded', function () {
        document.body.addEventListener('admin:drawer-close', function () { close(); });
        var auto = document.querySelector('[data-drawer-open][data-drawer-autoopen]'); // ?create=1 深链（非 htmx 访问新建表单 URL 的落点）
        if (auto) {
            open(auto.dataset.drawerOpen, { res: auto.dataset.drawerRes });
            try { var u = new URL(window.location.href); u.searchParams.delete('create'); history.replaceState(history.state, '', u.toString()); } catch (e) { /* 忽略 */ }
            return; // 打开后立即清掉 ?create=，关抽屉后 F5 不再自动重开（复审 #11）
        }
        try {
            var id = new URLSearchParams(window.location.search).get('open');
            if (!id) { return; }
            var tr = document.querySelector('tr[data-id="' + CSS.escape(id) + '"][data-drawer-url]');
            if (tr) { open(tr.dataset.drawerUrl, { rowEl: tr }); }
        } catch (e) { /* 忽略：id 不在当前页 */ }
    });
})();
