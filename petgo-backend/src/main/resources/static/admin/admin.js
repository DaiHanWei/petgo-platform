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

// 后台账号页 · 岗位角色（V165）。建号表单里做三件显隐：
//   1) 只显示当前选中角色的职责说明；
//   2) 只显示当前角色的权限预览（模板已把每个角色的预览都渲染好了）；
//   3) 权限勾选区仅「自定义」角色显示 —— 其余角色的权限由角色定义决定，服务端会忽略勾选，
//      留着它只会让人以为「我勾了就生效」。
// 纯体验层：授权在服务端按角色解析，禁用 JS 也授不出多余权限（页面只是全部展开而已）。
document.addEventListener('DOMContentLoaded', function () {
    var roleSelect = document.getElementById('create-role');
    if (!roleSelect) return;
    var permGroups = document.getElementById('create-perm-groups');
    var permNote = permGroups && permGroups.previousElementSibling;

    function sync() {
        var role = roleSelect.value;
        document.querySelectorAll('.role-desc').forEach(function (p) {
            p.hidden = p.getAttribute('data-role') !== role;
        });
        document.querySelectorAll('.role-perm-preview').forEach(function (d) {
            d.hidden = d.getAttribute('data-role') !== role;
        });
        var custom = role === 'CUSTOM';
        if (permGroups) permGroups.hidden = !custom;
        if (permNote) permNote.hidden = !custom;
    }
    roleSelect.addEventListener('change', sync);
    sync();
});
