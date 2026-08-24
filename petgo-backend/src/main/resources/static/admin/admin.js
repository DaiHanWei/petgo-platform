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

// 筛选栏下拉选完即刷新（bug 20260820：人工复核页选了状态还得再点一次「筛选」，多一步且容易忘）。
// form[data-autosubmit] 内的 <select> 一变就提交所在表单。
//   ⚠️ 只管 <select>，**不碰文本框** —— 文本输入的 change 在失焦时才触发，
//      打字中途点别处就会莫名刷新一次，比多点一下按钮更糟。文本框仍走「筛选」按钮。
//   ⚠️ 用 requestSubmit() 而非 submit()：前者会派发 submit 事件，本文件顶部的
//      data-confirm 二次确认、以及表单上的 hx-get（HTMX 监听 submit）才不会被绕过。
//      老浏览器无此方法时回退 submit()（HTMX 页会退化成整页 GET，结果一样）。
//   「筛选」按钮保留：无 JS 时仍可用，也是文本框的提交入口。
document.addEventListener('change', function (e) {
    var el = e.target;
    if (!el || el.tagName !== 'SELECT') { return; }
    var form = el.closest && el.closest('form[data-autosubmit]');
    if (!form) { return; }
    if (typeof form.requestSubmit === 'function') {
        form.requestSubmit();
    } else {
        form.submit();
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

// ===== 「以运营真实账号发布」的二次确认（V1.1.6 Story 12.1 · AC6）=====
// 顶部那个 data-confirm 是**静态**文案、每次提交都弹；这里要的是**只在选中真实账号时**弹
//   —— 虚拟账号是常用路径，每次都拦会让运营养成"不看就点确定"的习惯，
//      那时真正危险的那一次也会被闭着眼点过去。
//
// 判据是选项上的 data-real="true"（由 admin/fragments/publish-identity-select 渲染）。
// 以运营真实账号误发不可撤回：内容会出现在那个真人的个人主页并推送给他的粉丝。
//
// ⚠️ 与顶部 data-confirm 一样用 capture 阶段：要在 HTMX 的 submit 处理之前拦住。
// 🛡 这只是体验层 —— 服务端还有一道 seed.publish_as_real 硬校验，勾选框在浏览器里改得动。
document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form || !form.getAttribute) { return; }
    var msg = form.getAttribute('data-real-identity-confirm');
    if (!msg) { return; }
    var select = form.querySelector('select[name="virtualUserId"]');
    var opt = select && select.selectedIndex >= 0 ? select.options[select.selectedIndex] : null;
    if (opt && opt.getAttribute('data-real') === 'true' && !window.confirm(msg)) {
        e.preventDefault();
    }
}, true);

// ===== 单条发布的图片上传控件（V1.1.6 Story 12.2 · AC2/AC3）=====
// 此前后台只能填图片 URL：运营为了发一条内容得先去别处传图、拿链接、再粘回来。
//
// 四件事：多图上传 · 拖拽排序（第一张即封面）· 单张删除 · 粘贴上传。
// 提交给服务端的是两个隐藏 textarea：URL 与「宽x高」**同序等长**。
//
// 🔴 一次一张请求，不是一次一批：批量里有一张被拒（HEIC / 超 10MB），
//    要么整批失败（运营重传全部），要么回一个"部分成功"（界面复杂度远超收益）。
//    一张一个请求 ⇒ 失败那张单独标红、其余照常。
//
// 🛡 裁切警告文案由**服务端**给（算法只有一份，见 ImageRatioAdvisor），前端只负责显示。
(function () {
    var MAX = 9;

    function fieldOf(id) { return document.getElementById(id); }

    /** 把当前缩略图顺序写回两个隐藏字段 —— **顺序就是首图顺序**。 */
    function sync(root) {
        var thumbs = [].slice.call(root.querySelectorAll('[data-seed-thumb]'));
        var urls = [], sizes = [];
        thumbs.forEach(function (t) {
            urls.push(t.getAttribute('data-url'));
            // 测不出尺寸的图也要占一行，否则两个字段会错位 —— 服务端对长度不符是**整组作废**。
            sizes.push((t.getAttribute('data-w') || '0') + 'x' + (t.getAttribute('data-h') || '0'));
        });
        // 兜底 URL 追加在上传图之后：它们没有尺寸，写 0x0（服务端会因长度虽等但值不合理而走异步兜底）。
        var fallback = root.parentNode.querySelector('[data-seed-url-fallback]');
        if (fallback && fallback.value.trim()) {
            fallback.value.split(/\r?\n/).forEach(function (line) {
                var u = line.trim();
                if (u) { urls.push(u); sizes.push('0x0'); }
            });
        }
        fieldOf('imageUrlsRaw').value = urls.join('\n');
        fieldOf('imageSizesRaw').value = sizes.join('\n');

        // 第一张打「封面」角标；>1 张时提示首图决定整帖容器高度（AC3 最后一条）。
        thumbs.forEach(function (t, i) {
            var badge = t.querySelector('[data-seed-cover]');
            if (badge) { badge.hidden = i !== 0; }
        });
        var note = root.querySelector('[data-seed-first-note]');
        if (note) { note.hidden = thumbs.length < 2; }
    }

    function addThumb(root, data) {
        var box = root.querySelector('[data-seed-thumbs]');
        var el = document.createElement('div');
        el.className = 'seed-thumb';
        el.setAttribute('data-seed-thumb', '');
        el.setAttribute('draggable', 'true');
        el.setAttribute('data-url', data.url);
        el.setAttribute('data-w', data.w || 0);
        el.setAttribute('data-h', data.h || 0);
        var img = document.createElement('img');
        img.src = data.url;
        img.alt = '';
        el.appendChild(img);
        var cover = document.createElement('span');
        cover.className = 'seed-cover';
        cover.setAttribute('data-seed-cover', '');
        cover.textContent = root.getAttribute('data-msg-cover') || 'cover';
        el.appendChild(cover);
        var del = document.createElement('button');
        del.type = 'button';
        del.className = 'seed-thumb-del';
        del.setAttribute('data-seed-del', '');
        del.textContent = '×';
        del.title = root.getAttribute('data-msg-remove') || 'remove';
        el.appendChild(del);
        if (data.warning) {
            var warn = document.createElement('p');
            warn.className = 'err';
            warn.textContent = data.warning;
            el.appendChild(warn);
        }
        box.appendChild(el);
        sync(root);
    }

    function upload(root, file) {
        var thumbs = root.querySelectorAll('[data-seed-thumb]').length;
        if (thumbs >= MAX) {
            window.alert(root.getAttribute('data-msg-limit') || 'max 9 images');
            return;
        }
        var status = root.querySelector('[data-seed-status]');
        if (status) { status.textContent = root.getAttribute('data-msg-uploading') || '...'; }
        var body = new FormData();
        body.append('file', file);
        // ⚠️ /admin/** 那条过滤链**保留 CSRF** —— 少了这个头是 403，而不是"权限不够"。
        var headers = {};
        var token = document.querySelector('meta[name="_csrf"]');
        var header = document.querySelector('meta[name="_csrf_header"]');
        if (token && header) { headers[header.content] = token.content; }
        fetch(root.getAttribute('data-upload-url'), {
            method: 'POST', body: body, headers: headers, credentials: 'same-origin'
        }).then(function (r) {
            return r.json().then(function (j) { return { ok: r.ok, body: j }; });
        }).then(function (res) {
            if (status) { status.textContent = ''; }
            if (!res.ok) {
                // 被拒的那张单独报错，不影响其余（HEIC / 超 10MB 都是**预期内**的输入）。
                var p = document.createElement('p');
                p.className = 'err';
                p.textContent = (file.name || '') + '：' + (res.body.error || 'upload failed');
                root.querySelector('[data-seed-thumbs]').appendChild(p);
                return;
            }
            addThumb(root, res.body);
        }).catch(function () {
            if (status) { status.textContent = ''; }
        });
    }

    function eachRoot(fn) {
        [].slice.call(document.querySelectorAll('[data-seed-uploader]')).forEach(fn);
    }

    document.addEventListener('change', function (e) {
        if (!e.target.hasAttribute || !e.target.hasAttribute('data-seed-file')) { return; }
        var root = e.target.closest('[data-seed-uploader]');
        [].slice.call(e.target.files).forEach(function (f) { upload(root, f); });
        e.target.value = ''; // 清空以便重选同一张
    });

    document.addEventListener('click', function (e) {
        var del = e.target.closest && e.target.closest('[data-seed-del]');
        if (!del) { return; }
        var root = del.closest('[data-seed-uploader]');
        del.closest('[data-seed-thumb]').remove();
        sync(root);
    });

    // 粘贴上传：剪贴板里有图就直接传（AC2）。
    document.addEventListener('paste', function (e) {
        eachRoot(function (root) {
            var items = (e.clipboardData && e.clipboardData.items) || [];
            [].slice.call(items).forEach(function (it) {
                if (it.kind === 'file' && it.type.indexOf('image/') === 0) {
                    upload(root, it.getAsFile());
                }
            });
        });
    });

    // 拖拽排序。用最朴素的做法：拖起来记住是谁，落在谁身上就插到它前面。
    var dragging = null;
    document.addEventListener('dragstart', function (e) {
        var t = e.target.closest && e.target.closest('[data-seed-thumb]');
        if (t) { dragging = t; e.dataTransfer.effectAllowed = 'move'; }
    });
    document.addEventListener('dragover', function (e) {
        var over = e.target.closest && e.target.closest('[data-seed-thumb]');
        if (dragging && over) { e.preventDefault(); }
    });
    document.addEventListener('drop', function (e) {
        var over = e.target.closest && e.target.closest('[data-seed-thumb]');
        if (!dragging || !over || over === dragging) { return; }
        e.preventDefault();
        over.parentNode.insertBefore(dragging, over);
        sync(dragging.closest('[data-seed-uploader]'));
        dragging = null;
    });

    // 兜底 URL 框改动也要同步进隐藏字段。
    document.addEventListener('input', function (e) {
        if (!e.target.hasAttribute || !e.target.hasAttribute('data-seed-url-fallback')) { return; }
        var form = e.target.closest('[data-seed-form]');
        var root = form && form.querySelector('[data-seed-uploader]');
        if (root) { sync(root); }
    });

    document.addEventListener('DOMContentLoaded', function () { eachRoot(sync); });
})();

// ===== 批次素材上传：选择时即拦截 + 实时计数（V1.1.6 Story 13.2 · AC2/AC3）=====
//
// 🔴 **不能等全部传完才报错**：运营已经等了几分钟，而且"部分成功部分失败"的中间状态
//    很难处置（哪几张进去了？重传要跳过哪几张？）。所以在**发请求之前**就把超出的挡掉。
//
// 三条判据全在客户端先过一遍：累计张数 / 累计字节 / 同批文件名重复。
// 🛡 这一层**只是省时间**，不是安全边界 —— 服务端各自还有一遍权威校验
//    （勾选框和 JS 在浏览器里都改得动）。
(function () {
    function state(root) {
        return {
            maxCount: parseInt(root.getAttribute('data-max-count'), 10),
            maxBytes: parseInt(root.getAttribute('data-max-bytes'), 10),
            usedCount: parseInt(root.getAttribute('data-used-count'), 10) || 0,
            usedBytes: parseInt(root.getAttribute('data-used-bytes'), 10) || 0
        };
    }

    function paint(root) {
        var s = state(root);
        var live = root.querySelector('[data-batch-live]');
        if (!live) { return; }
        var mb = function (b) { return Math.round(b / 1024 / 1024); };
        live.textContent = s.usedCount + ' / ' + s.maxCount + '，'
                + mb(s.usedBytes) + ' / ' + mb(s.maxBytes) + ' MB';
    }

    function reject(root, name, msg) {
        var box = root.querySelector('[data-batch-errors]');
        if (!box) { return; }
        var p = document.createElement('p');
        p.className = 'err';
        p.textContent = name + '：' + msg;
        box.appendChild(p);
    }

    /** 墙上已有的文件名 —— 分次追加时最容易撞的就是这个（"先拖猫的、再拖狗的"）。 */
    function existingNames() {
        var wall = document.getElementById('seedAssetWall');
        if (!wall) { return []; }
        return [].slice.call(wall.querySelectorAll('.seed-thumb .hint'))
                .map(function (el) { return el.textContent.trim(); });
    }

    function refreshWall(root) {
        var url = root.getAttribute('data-wall-url');
        if (!url || typeof htmx === 'undefined') { return; }
        htmx.ajax('GET', url, { target: '#seedAssetWall', swap: 'outerHTML' });
    }

    function send(root, file, onDone) {
        var body = new FormData();
        body.append('file', file);
        var headers = {};
        var token = document.querySelector('meta[name="_csrf"]');
        var header = document.querySelector('meta[name="_csrf_header"]');
        if (token && header) { headers[header.content] = token.content; }
        fetch(root.getAttribute('data-upload-url'), {
            method: 'POST', body: body, headers: headers, credentials: 'same-origin'
        }).then(function (r) {
            return r.json().then(function (j) { return { ok: r.ok, body: j }; });
        }).then(function (res) {
            if (!res.ok) {
                reject(root, file.name, res.body.error || 'upload failed');
            } else {
                // 用服务端回的权威用量校准本地计数（别自己累加 —— 会和真相慢慢分叉）。
                root.setAttribute('data-used-count', res.body.usedCount);
                root.setAttribute('data-used-bytes', res.body.usedBytes);
                paint(root);
            }
            onDone();
        }).catch(function () {
            reject(root, file.name, 'upload failed');
            onDone();
        });
    }

    document.addEventListener('change', function (e) {
        if (!e.target.hasAttribute || !e.target.hasAttribute('data-batch-file')) { return; }
        var root = e.target.closest('[data-batch-uploader]');
        var files = [].slice.call(e.target.files);
        e.target.value = '';
        root.querySelector('[data-batch-errors]').innerHTML = '';

        var s = state(root);
        var names = existingNames();
        var accepted = [];
        var plannedCount = s.usedCount;
        var plannedBytes = s.usedBytes;
        files.forEach(function (f) {
            // ① 同名（含与墙上已有的、以及本次选中里自己重复的）
            if (names.indexOf(f.name) >= 0) {
                reject(root, f.name, root.getAttribute('data-msg-dup'));
                return;
            }
            // ② 累计张数 —— 🛡 按累计算，否则分三次拖就能绕过限制
            if (plannedCount + 1 > s.maxCount) {
                reject(root, f.name, root.getAttribute('data-msg-over-count'));
                return;
            }
            // ③ 累计字节
            if (plannedBytes + f.size > s.maxBytes) {
                reject(root, f.name, root.getAttribute('data-msg-over-bytes'));
                return;
            }
            names.push(f.name);
            plannedCount++;
            plannedBytes += f.size;
            accepted.push(f);
        });

        var left = accepted.length;
        if (left === 0) { return; }
        accepted.forEach(function (f) {
            send(root, f, function () {
                left--;
                // 全部回来了再刷墙一次 —— 每张都刷会让缩略图墙闪十几下。
                if (left === 0) { refreshWall(root); }
            });
        });
    });

    document.addEventListener('DOMContentLoaded', function () {
        [].slice.call(document.querySelectorAll('[data-batch-uploader]')).forEach(paint);
    });
})();

// ===== 「关联物种」跟随所选发布账号（V1.1.6 Story 14.1 · AC4）=====
//
// 默认跟随该账号的「账号物种定位」；🛡 **运营手动改过之后就不再自动跟随** ——
// 切个账号把他刚选的值冲掉，是最容易让人发错的那种"贴心"。
//
// 🔴 选的是运营真实账号时默认**留空**（它没有账号物种定位，物种由作者宠物档案推导）。
document.addEventListener('change', function (e) {
    var el = e.target;
    if (!el || el.tagName !== 'SELECT') { return; }

    // ① 运营自己动了物种下拉 ⇒ 打上"已手动"标记，此后不再被跟随覆盖。
    if (el.hasAttribute('data-species-follow')) {
        el.setAttribute('data-touched', 'true');
        return;
    }

    // ② 换了发布账号 ⇒ 若物种没被手动改过，跟随更新。
    if (el.name !== 'authorUserId') { return; }
    var form = el.closest('form');
    var species = form && form.querySelector('[data-species-follow]');
    if (!species || species.getAttribute('data-touched') === 'true') { return; }
    var opt = el.selectedIndex >= 0 ? el.options[el.selectedIndex] : null;
    // data-species 为空（运营真实账号）⇒ 留空。
    species.value = (opt && opt.getAttribute('data-species')) || '';
});
