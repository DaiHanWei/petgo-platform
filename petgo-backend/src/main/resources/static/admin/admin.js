// TailTopia 运营后台轻量交互（Story 1.6）。本地静态托管，无第三方依赖。
// 危险操作二次确认：表单带 data-confirm="提示文案" 时，提交前弹 confirm，取消则阻止提交。
// 用 data-* + 监听（而非 th:onsubmit 内联字符串）以兼容 i18n 文案并规避 Thymeleaf 事件属性限制。
document.addEventListener('submit', function (e) {
    var form = e.target;
    var msg = form.getAttribute && form.getAttribute('data-confirm');
    if (msg && !window.confirm(msg)) {
        e.preventDefault();
    }
}, true);

// 原生 <dialog> 弹窗开关（兽医开户等）。data-* 委托，无内联 JS：
//   [data-open-dialog="<id>"] 点击 → 打开该弹窗；[data-close-dialog] → 关闭所在弹窗；
//   点击 backdrop（弹窗自身留白区）关闭；<dialog data-autoopen="true"> 载入即打开（服务端校验失败回显场景）。
document.addEventListener('click', function (e) {
    var opener = e.target.closest && e.target.closest('[data-open-dialog]');
    if (opener) {
        var dlg = document.getElementById(opener.getAttribute('data-open-dialog'));
        if (dlg && typeof dlg.showModal === 'function') dlg.showModal();
        return;
    }
    var closer = e.target.closest && e.target.closest('[data-close-dialog]');
    if (closer) {
        var host = closer.closest('dialog');
        if (host) host.close();
        return;
    }
    // 点击 dialog 元素本身（而非其内容）= 点在 backdrop 上 → 关闭。
    if (e.target.tagName === 'DIALOG' && typeof e.target.close === 'function') {
        e.target.close();
    }
});

// 图片灯箱（内容管理等）：点带 data-lightbox 的缩略图 → 原生 <dialog> 全屏看大图（非下载）。
// 惰性建一个通用 dialog，全后台复用；点任意处关闭。HTMX 换行后仍生效（事件委托在 document）。
document.addEventListener('click', function (e) {
    var thumb = e.target.closest && e.target.closest('img[data-lightbox]');
    if (!thumb) return;
    var dlg = document.getElementById('admin-lightbox');
    if (!dlg) {
        dlg = document.createElement('dialog');
        dlg.id = 'admin-lightbox';
        dlg.className = 'lightbox';
        dlg.innerHTML = '<img alt=""/>';
        dlg.addEventListener('click', function () { dlg.close(); });
        document.body.appendChild(dlg);
    }
    dlg.querySelector('img').src = thumb.src;
    if (typeof dlg.showModal === 'function') dlg.showModal();
});

document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('dialog[data-autoopen="true"]').forEach(function (d) {
        if (typeof d.showModal === 'function') d.showModal();
    });
    // Toast 自动消失（bug 346）：3s 淡出、3.4s 移除。
    document.querySelectorAll('.toast').forEach(function (t) {
        setTimeout(function () { t.classList.add('hide'); }, 3000);
        setTimeout(function () { t.remove(); }, 3400);
    });
});

// ===== 工单批量勾选（V1.1.4 Story 3.3）=====
// ⚠️ 本文件是**全后台共享**的，所以这一段全部用 [data-batch-scope] 限定作用域，
//    事件委托在 document 上但先判断是否落在该作用域内——别让它影响到其他页面的表格。
//
// 三件事：① 全选 ② 选中计数 + 上限 ③ 跨类型置灰。
// ⚠️ 这三条**前端只是体验**，服务端各自还有一遍硬校验：勾选框在浏览器里可以被随便改，
//    上限与跨类型是「一次别封掉几百个人」的安全边界，不能只靠前端。
(function () {
    var MAX = 50;

    function scopeOf(el) {
        return el && el.closest ? el.closest('[data-batch-scope]') : null;
    }

    function boxes(scope) {
        return Array.prototype.slice.call(scope.querySelectorAll('input[data-batch-item]'));
    }

    function refresh(scope) {
        var all = boxes(scope);
        var checked = all.filter(function (b) { return b.checked; });
        // 跨类型置灰：选中第一条之后，其余类型一律不可选。
        // 不同类型工单的处置对象含义不同——内容举报处置的是**内容**，账号举报处置的是**人**，
        // 混在一批里执行同一个动作没有意义。
        var lockedType = checked.length ? checked[0].getAttribute('data-type') : null;
        all.forEach(function (b) {
            if (b.checked) { return; }
            var wrongType = lockedType !== null && b.getAttribute('data-type') !== lockedType;
            var atLimit = checked.length >= MAX;
            b.disabled = wrongType || atLimit;
        });
        var counter = scope.querySelector('[data-batch-count]');
        if (counter) {
            counter.textContent = checked.length + ' / ' + MAX;
            counter.classList.toggle('muted', checked.length === 0);
        }
        // 没选任何东西时，批量按钮不可点（免得点了才发现什么都没选）。
        scope.querySelectorAll('[data-batch-action]').forEach(function (btn) {
            btn.disabled = checked.length === 0;
        });
        // 批量封号的二次确认弹窗：把「将被封的账号」逐条列出来。
        // 只给一句「确认封 N 个账号？」等于让运营对着一个数字点确认——手滑全选的后果正是要防的。
        var list = scope.querySelector('[data-batch-suspend-list]');
        if (list) {
            list.innerHTML = '';
            checked.forEach(function (b) {
                var li = document.createElement('li');
                li.textContent = b.getAttribute('data-label') || b.value;
                list.appendChild(li);
            });
        }
    }

    document.addEventListener('change', function (e) {
        var scope = scopeOf(e.target);
        if (!scope) { return; }
        if (e.target.hasAttribute('data-batch-all')) {
            var checked = boxes(scope).filter(function (b) { return b.checked; });
            var lockedType = checked.length ? checked[0].getAttribute('data-type') : null;
            var picked = 0;
            boxes(scope).forEach(function (b) {
                if (!e.target.checked) { b.checked = false; return; }
                // 全选也受两条边界约束：只选同一类型、且最多 50 条。
                var sameType = lockedType === null || b.getAttribute('data-type') === lockedType;
                if (sameType && picked < MAX) { b.checked = true; picked++; }
                if (lockedType === null && b.checked) { lockedType = b.getAttribute('data-type'); }
            });
        }
        refresh(scope);
    });

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('[data-batch-scope]').forEach(refresh);
    });
})();
