// ===== 模板 B 抽屉（V1.3.0 Story 2.3b · AD-11）=====
// 只在模板 B 页引入（<script defer th:src="@{/admin/admin-drawer.js}">，在 admin-core.js 之后）。
// 对外 window.Admin.drawer = { open(url, {res, rowEl, openId, keepOpenParam}), close(res), sync() }：
//   （openId：没有触发行时由调用方指定地址栏 ?open= 的值；keepOpenParam：跨页深链打开后原样保留已有的 ?open=）
//   · open：htmx.ajax('GET', url, {target:'#<res>-drawer .drawer-body', swap:'innerHTML'}) 后显示抽屉 + 遮罩；
//   · 关闭三途径：✕（[data-drawer-close]）/ 点遮罩 / Esc；关闭后焦点回到触发行；
//   · 监听 admin:drawer-close（2.3a AdminHxEvents.DRAWER_CLOSE，服务端 HX-Trigger 带出；对象消失类动作才发）
//     与 admin:drawer-open（7.4 AdminHxEvents.DRAWER_OPEN，载荷带抽屉 URL + 对象 id：建完对象直接打开它）；
//   · 抽屉内操作成功**不自动关**（UI 稿 10-6）；
//   · ?open=<id> 页内深链（D-23：不是旧详情页 URL 的跳转规则）：DOMContentLoaded 找 tr[data-id=…] 自动 open；
//     行不在当前页时退到页面提供的 [data-drawer-deeplink]（按 id 直取抽屉 URL，7.1 跨页深链）；
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
            else if (state.openId) { url.searchParams.set('open', state.openId); } // 服务端指定打开（admin:drawer-open）
            else if (state.keepOpenParam) { /* 跨页深链打开：地址栏上的 ?open= 原样留着，刷新还能回到同一条 */ }
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
        state.keepOpenParam = !!opts.keepOpenParam;
        state.openId = opts.openId || null;
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
        state.keepOpenParam = false; // 关掉之后地址栏不该还挂着 ?open=
        state.openId = null;
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
        // 服务端指定打开某个抽屉（V1.3.0 Story 7.4 新增 AdminHxEvents.DRAWER_OPEN，载荷 {url, id?, res?}）。
        // 🔴 Story 7.4 AC4「新建标签成功后自动打开新标签抽屉并停在分配记录页签」走的就是它：
        //    直接把抽屉体换掉的话，JS 里记的「当前对象」还是新建态，地址栏的 ?open= 也不会跟着走，
        //    刷新一下就回到空表单 —— 所以让服务端把 URL 递过来，走正常的 open 流程。
        document.body.addEventListener('admin:drawer-open', function (e) {
            var d = (e && e.detail) || {};
            if (!d.url) { return; }
            var fallback = document.querySelector('.drawer[data-res]');
            open(d.url, {
                res: d.res || (fallback && fallback.dataset.res),
                openId: d.id == null ? null : String(d.id)
            });
        });
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
            if (tr) { open(tr.dataset.drawerUrl, { rowEl: tr }); return; }
            // 🔴 行不在当前页（跨页深链：从复核队列 / 工单点「查看内容」过来的帖子多半不在首屏那一页）。
            //    页面可以给一个按 id 直取抽屉的兜底入口；没有它就只能静默什么都不发生 —— 那正是删掉整页详情后
            //    最容易留下的坑（V1.3.0 Story 7.1 复审 C6）。
            var fb = document.querySelector('[data-drawer-deeplink]');
            if (fb) { open(fb.dataset.drawerDeeplink, { res: fb.dataset.drawerRes, keepOpenParam: true }); }
        } catch (e) { /* 忽略：id 非法 / 环境不支持 */ }
    });
})();
