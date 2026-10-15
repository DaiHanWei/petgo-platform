// ===== 运营弹窗抽屉（V1.3.4 AB-30 · 后台返工清单 §3 · UI 稿 H3–H7）=====
// 只在 ops-popups.html 引入（在 admin-core.js / admin-drawer.js 之后）。四件事：
//   ① 抽屉标题：表单片段换进抽屉后，把 form[data-drawer-title]（新建 / 编辑运营弹窗）写进 #ops-popup-drawer-title；
//   ② 上传位 [data-opsp-upload]（H3 / H5）：选图即传，回填隐藏字段 objectKey；超 1MB 不发请求，上传位变红 +
//      提示行「图片超过 1MB，请压缩后再上传（文件名 · x.xMB）」（文案来自 data-msg-too-large，{0} 文件名 / {1} 大小）；
//   ③ 422 标红（H7）：汇总条 [data-invalid] 落进 #ops-popup-form-err 后，按名单给输入 / 容器加 .is-invalid；
//   ④ 预览（H6）：点「预览」按表单当前值渲染 EN / ID 两台手机（回落规则同 App：EN 空则用 ID），点「返回编辑」回表单。
// 🛡 全是体验层：校验、大小上限、图片可用性的判定点都在服务端（AdminOpsPopupService / 上传端点）。
// 用户可见文字一律来自模板 data-* / #{}（check-admin-hardcoded-text.sh）。
(function () {
    function fmt(tpl, args) {
        return (tpl || '').replace(/\{(\d+)\}/g, function (m, i) { return args[+i] == null ? m : args[+i]; });
    }

    function csrfHeaders() {
        var headers = {};
        var token = document.querySelector('meta[name="_csrf"]');
        var header = document.querySelector('meta[name="_csrf_header"]');
        if (token && header) { headers[header.content] = token.content; }
        return headers;
    }

    // ---------- ② 上传位 ----------
    function tileErr(tile) {
        var form = tile.closest('form');
        return form && form.querySelector('[data-opsp-upload-err]');
    }

    function setTile(tile, key, url) {
        var keyEl = tile.querySelector('[data-opsp-key]');
        var img = tile.querySelector('[data-opsp-img]');
        keyEl.value = key || '';
        keyEl.dispatchEvent(new Event('input', { bubbles: true }));
        if (url) { img.src = url; img.hidden = false; } else { img.removeAttribute('src'); img.hidden = true; }
        tile.classList.toggle('has', !!url);
        tile.classList.remove('bad', 'busy');
    }

    function showUploadError(tile, text, oversize) {
        tile.classList.remove('busy');
        tile.classList.toggle('bad', !!oversize);
        var box = tileErr(tile);
        if (!box) { return; }
        var p = document.createElement('p');
        p.textContent = text;
        box.innerHTML = '';
        box.appendChild(p);
    }

    function clearUploadError(tile) {
        tile.classList.remove('bad');
        var box = tileErr(tile);
        if (box) { box.innerHTML = ''; }
    }

    function upload(tile, file) {
        clearUploadError(tile);
        var max = parseInt(tile.getAttribute('data-max-bytes') || '0', 10);
        if (max > 0 && file.size > max) {
            // 🔴 超限的图根本不发出去：Tomcat 在 multipart 解析阶段拒，连接被重置，fetch 会一直挂着（2026-09-03 回归 P1）
            var mb = (file.size / 1024 / 1024).toFixed(1) + 'MB';
            showUploadError(tile, fmt(tile.getAttribute('data-msg-too-large'), [file.name || '', mb]), true);
            return;
        }
        var body = new FormData();
        body.append('file', file);
        tile.classList.add('busy');
        fetch(tile.getAttribute('data-upload-url'), {
            method: 'POST', body: body, headers: csrfHeaders(), credentials: 'same-origin'
        }).then(function (r) {
            // 会话过期：POST 被重定向到登录页，fetch 跟随后拿到 200 + 登录页 HTML —— 单独认出来
            if (r.redirected && r.url && r.url.indexOf('/admin/login') >= 0) {
                return { ok: false, expired: true };
            }
            return r.text().then(function (t) {
                var parsed = null;
                try { parsed = t ? JSON.parse(t) : null; } catch (e) { parsed = null; }
                return { ok: r.ok, status: r.status, body: parsed };
            });
        }).then(function (res) {
            if (res.expired) { showUploadError(tile, tile.getAttribute('data-msg-expired'), false); return; }
            if (!res.ok || !res.body || !res.body.url) {
                var text = (res.body && (res.body.error || res.body.detail))
                        || tile.getAttribute('data-msg-failed') + '（HTTP ' + res.status + '）';
                showUploadError(tile, (file.name ? file.name + ' · ' : '') + text, false);
                return;
            }
            setTile(tile, res.body.objectKey || res.body.url, res.body.url);
        }).catch(function () {
            showUploadError(tile, tile.getAttribute('data-msg-failed'), false);
        });
    }

    document.addEventListener('change', function (e) {
        var input = e.target;
        if (!input || !input.hasAttribute || !input.hasAttribute('data-opsp-file')) { return; }
        var tile = input.closest('[data-opsp-upload]');
        if (tile && input.files && input.files[0]) { upload(tile, input.files[0]); }
        input.value = ''; // 清空以便重选同一张
    });

    document.addEventListener('click', function (e) {
        var del = e.target.closest && e.target.closest('[data-opsp-del]');
        if (!del) { return; }
        e.preventDefault();
        e.stopPropagation();
        var tile = del.closest('[data-opsp-upload]');
        if (tile) { setTile(tile, '', null); clearUploadError(tile); }
    });

    // ---------- ③ 422 标红 ----------
    function markInvalid(slot) {
        var form = slot.closest('form');
        if (!form) { return; }
        form.querySelectorAll('.is-invalid').forEach(function (el) { el.classList.remove('is-invalid'); });
        var summary = slot.querySelector('[data-invalid]');
        if (!summary) { return; }
        var first = null;
        (summary.getAttribute('data-invalid') || '').split(',').forEach(function (name) {
            if (!name) { return; }
            form.querySelectorAll('[name="' + name + '"]:not([disabled]):not([type="hidden"]):not([type="radio"]), [data-error-name="' + name + '"]')
                .forEach(function (el) { el.classList.add('is-invalid'); first = first || el; });
        });
        if (first && first.scrollIntoView) { first.scrollIntoView({ block: 'center' }); }
    }

    // 改了就去红边（单个输入；容器类——上传位 / 单选组——任一子项变化即清）
    function clearOne(e) {
        var t = e.target;
        if (!t || !t.closest || !t.closest('.opsp-form')) { return; }
        if (t.classList && t.classList.contains('is-invalid')) { t.classList.remove('is-invalid'); }
        var box = t.closest('[data-error-name].is-invalid');
        if (box) { box.classList.remove('is-invalid'); }
    }
    document.addEventListener('input', clearOne);
    document.addEventListener('change', clearOne);

    // ---------- ④ 预览 ----------
    function val(form, name) {
        var el = form.querySelector('[name="' + name + '"]');
        return el ? (el.value || '').trim() : '';
    }

    function tileUrl(form, field) {
        var key = form.querySelector('[data-opsp-key][name="' + field + '"]');
        var tile = key && key.closest('[data-opsp-upload]');
        var img = tile && tile.querySelector('[data-opsp-img]');
        return img && !img.hidden && img.getAttribute('src') ? img.getAttribute('src') : '';
    }

    function fillPhone(phone, data) {
        var img = phone.querySelector('[data-pv-img]');
        if (data.image) { img.src = data.image; img.hidden = false; } else { img.removeAttribute('src'); img.hidden = true; }
        var title = phone.querySelector('[data-pv-title]');
        var body = phone.querySelector('[data-pv-body]');
        title.textContent = data.title;
        title.hidden = !data.title;
        body.textContent = data.body;
        body.hidden = !data.body;
        phone.querySelector('.opsp-card-tx').hidden = !data.title && !data.body;
        phone.querySelector('[data-pv-button]').textContent = data.button;
    }

    function drawerTitle() { return document.getElementById('ops-popup-drawer-title'); }

    function showPreview(form, on) {
        form.querySelector('[data-opsp-edit]').hidden = on;
        form.querySelector('[data-opsp-preview]').hidden = !on;
        form.querySelectorAll('[data-opsp-edit-only], [data-opsp-preview-open]').forEach(function (b) { b.hidden = on; });
        form.querySelector('[data-opsp-preview-close]').hidden = !on;
        var t = drawerTitle();
        if (t) {
            t.textContent = on ? fmt(form.getAttribute('data-preview-title'), [val(form, 'name')])
                    : form.getAttribute('data-drawer-title');
        }
        var body = form.closest('.drawer-body');
        if (body) { body.scrollTop = 0; }
    }

    document.addEventListener('click', function (e) {
        var open = e.target.closest && e.target.closest('[data-opsp-preview-open]');
        var back = e.target.closest && e.target.closest('[data-opsp-preview-close]');
        if (!open && !back) { return; }
        var form = (open || back).closest('form');
        if (!form) { return; }
        if (back) { showPreview(form, false); return; }
        // App 回落：EN 用户 EN 字段为空时用 ID（OpsPopupCandidateService）；只传一张图时两语共用
        var idImg = tileUrl(form, 'imageUrlId');
        var enImg = tileUrl(form, 'imageUrlEn');
        var id = {
            image: idImg || enImg,
            title: val(form, 'titleId'),
            body: val(form, 'bodyId'),
            button: val(form, 'buttonTextId')
        };
        var en = {
            image: enImg || idImg,
            title: val(form, 'titleEn') || id.title,
            body: val(form, 'bodyEn') || id.body,
            button: val(form, 'buttonTextEn') || id.button
        };
        fillPhone(form.querySelector('[data-opsp-phone="EN"]'), en);
        fillPhone(form.querySelector('[data-opsp-phone="ID"]'), id);
        showPreview(form, true);
    });

    // ---------- ① 抽屉标题 + ③ 标红挂钩 ----------
    document.addEventListener('DOMContentLoaded', function () {
        document.body.addEventListener('htmx:afterSwap', function (e) {
            var t = e.detail && e.detail.target;
            if (!t) { return; }
            if (t.id === 'ops-popup-form-err') { markInvalid(t); return; }
            var form = t.querySelector && t.querySelector('form.opsp-form[data-drawer-title]');
            var title = drawerTitle();
            if (form && title) { title.textContent = form.getAttribute('data-drawer-title'); }
        });
    });
})();
