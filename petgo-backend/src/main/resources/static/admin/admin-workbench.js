// ===== 模板 A 工作台（V1.3.0 Story 2.3b · AD-11）=====
// 只在模板 A 页引入。对外 window.Admin.workbench = { selectNext(scope), select(rowEl) }。
//   · htmx:afterSwap：swap 进来的根元素带 data-next-id → 自动 GET 队列里 data-id=next 行的 data-detail-url 并高亮；
//     data-next-id 为空 → 右栏显示空态（服务端可直接返回 detail-empty fragment）；
//   · 队列行选中态 .q-row.on（移除其它行）；窄屏（<1024px）自动切到详情面板（data-pane）；
//   · 处置按钮 hx-post 期间 .is-loading + disabled（htmx:beforeRequest / afterRequest），杜绝双击（UI 稿 9-7 第 7 条）；
//   · 🔴 **不绑定 ArrowUp / ArrowDown**（D-14，全组不做键盘导航）。
(function () {
    window.Admin = window.Admin || {};
    var narrow = window.matchMedia ? window.matchMedia('(max-width: 1023px)') : { matches: false };

    function wbOf(el) { return el && el.closest ? el.closest('[data-workbench]') : null; }

    function setPane(wb, pane) {
        if (!wb) { return; }
        wb.classList.toggle('wb--narrow', narrow.matches);
        wb.setAttribute('data-active-pane', pane);
    }

    function select(rowEl) {
        var wb = wbOf(rowEl);
        if (!wb || !rowEl) { return; }
        wb.querySelectorAll('.q-row.on, .q-row[aria-current]').forEach(function (r) { r.classList.remove('on'); r.removeAttribute('aria-current'); });
        rowEl.classList.add('on');
        rowEl.setAttribute('aria-current', 'true');
        setPane(wb, 'detail');
    }

    function selectNext(scope) {
        var wb = wbOf(scope) || document.querySelector('[data-workbench]');
        if (!wb) { return; }
        var src = scope && scope.getAttribute ? scope : null;
        var next = src ? src.getAttribute('data-next-id') : null;
        if (!next) {
            var body = wb.querySelector('.wb-detail-body');
            var tpl = wb.querySelector('[data-detail-empty-template]');
            if (body && tpl) { body.innerHTML = tpl.innerHTML; }
            wb.querySelectorAll('.q-row.on').forEach(function (r) { r.classList.remove('on'); });
            setPane(wb, 'queue');
            return;
        }
        var row = wb.querySelector('.q-row[data-id="' + CSS.escape(next) + '"]');
        if (!row) { return; }
        select(row);
        if (typeof htmx !== 'undefined' && row.dataset.detailUrl) {
            htmx.ajax('GET', row.dataset.detailUrl, { target: wb.querySelector('.wb-detail-body'), swap: 'innerHTML' });
        }
    }

    window.Admin.workbench = { selectNext: selectNext, select: select };

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('[data-workbench]').forEach(function (wb) {
            setPane(wb, 'queue');
            // ?open=<id> 页内深链（D-23）：页面在任一槽位放 [data-open][data-open-url]；行在队列里则选中它，否则直接拉 detail
            var deep = wb.querySelector('[data-open]');
            var openId = deep && deep.getAttribute('data-open');
            if (openId && window.htmx) {
                var row = wb.querySelector('.q-row[data-id="' + CSS.escape(openId) + '"]');
                if (row) { select(row); }
                var url = (row && row.getAttribute('data-detail-url')) || deep.getAttribute('data-open-url');
                if (url) { window.htmx.ajax('GET', url, { target: '.wb-detail-body', swap: 'innerHTML' }); }
            }
        });
        document.body.addEventListener('htmx:afterSwap', function (e) {
            var wb = wbOf(e.detail && e.detail.target);
            if (!wb) { return; }
            var root = e.detail.target && e.detail.target.firstElementChild;
            // data-next-id 为空串时 Thymeleaf 会整个去掉该属性 → 处置 fragment 另带 data-done 标记，保证清空态也走 selectNext
            if (root && (root.hasAttribute('data-next-id') || root.hasAttribute('data-done'))) { selectNext(root); }
        });
        // 操作区的 POST：HX-Target 头改指壳底部的 #admin-inline-error（复审 #2）。htmx 默认把 hx-target 的 id
        // （wb-detail-body）放进 HX-Target，AdminBusinessExceptionAdvice 会据此 HX-Retarget → 422/403 把右栏三卡整体
        // 清空。改成行内错误宿主后：失败只落一行 err、不跳条；成功仍按 hx-target 正常 swap（该头只被服务端错误分支读）。
        document.body.addEventListener('htmx:configRequest', function (e) {
            var elt = e.detail && e.detail.elt;
            if (elt && elt.closest && elt.closest('.wb-actions, [data-inline-error]') && wbOf(elt)) {
                e.detail.headers['HX-Target'] = 'admin-inline-error';
            }
        });
        document.body.addEventListener('htmx:beforeRequest', function (e) {
            var btn = e.detail && e.detail.elt && e.detail.elt.closest ? e.detail.elt.closest('button') : null;
            if (btn && wbOf(btn)) { btn.classList.add('is-loading'); btn.disabled = true; }
            var host = document.getElementById('admin-inline-error');
            if (host && btn && wbOf(btn)) { host.textContent = ''; }
        });
        document.body.addEventListener('htmx:afterRequest', function (e) {
            var btn = e.detail && e.detail.elt && e.detail.elt.closest ? e.detail.elt.closest('button') : null;
            if (btn) { btn.classList.remove('is-loading'); btn.disabled = false; }
        });
        document.addEventListener('click', function (e) {
            var row = e.target && e.target.closest ? e.target.closest('.q-row') : null;
            if (row && !e.target.closest('a, button')) { select(row); }
            var back = e.target && e.target.closest ? e.target.closest('[data-pane-back]') : null;
            if (back) { setPane(wbOf(back), 'queue'); }
        });
        if (narrow.addEventListener) {
            narrow.addEventListener('change', function () {
                document.querySelectorAll('[data-workbench]').forEach(function (wb) {
                    setPane(wb, wb.getAttribute('data-active-pane') || 'queue');
                });
            });
        }
    });
})();

// ===== 工作台操作区（Story 2.4）：拒绝原因未选 → 拒绝钮禁用（常驻，不做二级面板）；违规钮级 data-confirm =====
(function () {
    function syncRequires(form) {
        var sel = form.querySelector('select[name="category"]');
        var btn = form.querySelector('[data-requires-target]');
        if (sel && btn) { btn.disabled = !sel.value; }
    }
    document.addEventListener('change', function (e) {
        var form = e.target && e.target.closest ? e.target.closest('form[data-requires]') : null;
        if (form) { syncRequires(form); }
    });
    document.body.addEventListener('htmx:afterSwap', function () {
        document.querySelectorAll('form[data-requires]').forEach(syncRequires);
    });
    // 违规钮（同一表单里有「通过」）：只在点违规时确认。capture 阶段先于 htmx 的 submit 处理。
    document.addEventListener('click', function (e) {
        var btn = e.target && e.target.closest ? e.target.closest('button[data-confirm-button]') : null;
        if (btn && btn.getAttribute('data-confirm') && !window.confirm(btn.getAttribute('data-confirm'))) {
            e.preventDefault(); e.stopPropagation();
        }
    }, true);
})();
