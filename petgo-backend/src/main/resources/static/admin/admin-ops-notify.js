// 新建系统通知 · 四步页（V1.3.4 AB-31；按后台返工清单 §4 对齐 UI 稿 I3–I8，模板 ops-notification-new.html）。
//
// · 分步只在浏览器里：步骤条 + 吸底「取消 / ← 上一步 / 下一步 → / 确认发送」；已完成的步骤可点回。
// · 每步「下一步」前校验本步（体验护栏，不是安全边界）：
//     ① 四项文案必填、跳转类型必选、非「无跳转」须填目标、外链须 https://；
//     ② 必须选人群；全部 / 按条件须等预计人数算完且 > 0；指定用户须有有效 ID（校验结果 > 0）；
//     ③ 定时须填且晚于现在（按 WIB = UTC+7 比较）。
// · ③ → ④：触发 #opsn-review-trigger（整表 hx-post …/review，服务端全验）；成功由服务端发 opsn:reviewed 切到 ④；
//   422 落 #opsn-err，按 form[data-error-steps] 把错误码映射回步骤并跳过去（字段标红由 admin-core.js 的通用逻辑做）。
// · 预计触达：人群块一变 → 对 #opsn-estimate 触发 opsn-refresh（元素上 hx-trigger 带 delay:300ms 防抖）。
// · 指定用户：textarea 输入即 300ms 防抖校验；「上传 CSV」用 FileReader 读进 textarea（idsSource=CSV），再触发同一校验；
//   手动改动 textarea → idsSource 回到 PASTE。「导出跳过清单」临时建一个普通表单 POST 下载（带 CSRF）。
// · V1.4.2 AB-37（Story 7.2 · UI 稿 I1–I6）：① 的四个文案框是 contenteditable 编辑区，变量是不可编辑的整体标签
//   span.opsn-var[data-var]；每次 input 把编辑区序列化成「纯文本 + {{nickname}} / {{petName}} 令牌」写进同名隐藏字段
//   （htmx hx-include 只收表单控件）。字数每个变量按 20 计；纯文本里手打的 {{ / 变量显示名在 ① 就拦（服务端兜底）。
//   ② 净 0 但扣前 > 0（只因宠物变量）放行到 ④，由确认页 I6 拦：面板 data-blocked="true" →「确认发送」置灰。
//   🔒 XSS：编辑区只经 DOM API 建节点，示例效果一律 textContent，粘贴 / 拖放只收 text/plain，绝不 innerHTML 拼用户输入。
// 文案一律来自 form 上的 data-msg-*（三语由服务端渲染），本文件不写死任何用户可见文字。
(function () {
    'use strict';

    function $(form, sel) { return form.querySelector(sel); }
    function $$(form, sel) { return Array.prototype.slice.call(form.querySelectorAll(sel)); }

    function init(form) {
        if (form.__opsnInit) { return; }
        form.__opsnInit = true;
        var current = 1;
        var estimate = document.getElementById('opsn-estimate');
        var idsResult = document.getElementById('opsn-ids-result');
        var ids = $(form, 'textarea[name="userIds"]');
        var idsSource = $(form, 'input[name="idsSource"]');
        var reviewTrigger = document.getElementById('opsn-review-trigger');
        var err = document.getElementById('opsn-err');
        var steps = {};
        try { steps = JSON.parse(form.getAttribute('data-error-steps') || '{}'); } catch (e) { steps = {}; }
        var state = { estimate: 'idle', ids: 'idle' };
        var submitBtn = $(form, '.opsn-submit');

        function msg(k) { return form.getAttribute('data-msg-' + k) || ''; }
        function val(name) {
            var el = form.querySelector('[name="' + name + '"]:not([disabled])');
            if (!el) { return ''; }
            if (el.type === 'radio') {
                var on = form.querySelector('[name="' + name + '"]:checked');
                return on ? on.value : '';
            }
            return (el.value || '').trim();
        }
        function mode() { var on = form.querySelector('input[name="audienceMode"]:checked'); return on ? on.value : ''; }
        function sendMode() { var on = form.querySelector('input[name="sendMode"]:checked'); return on ? on.value : ''; }

        // ---------- 步骤 ----------
        function go(n) {
            current = n;
            $$(form, '.opsn-panel').forEach(function (p) { p.hidden = String(n) !== p.getAttribute('data-step'); });
            $$(form, '[data-step-tab]').forEach(function (li) {
                var k = parseInt(li.getAttribute('data-step-tab'), 10);
                li.classList.toggle('step--on', k === n);
                li.classList.toggle('step--done', k < n);
                li.classList.toggle('is-link', k < n);
            });
            $(form, '[data-opsn-cancel]').hidden = n !== 1;
            $(form, '[data-opsn-prev]').hidden = n === 1;
            $(form, '[data-opsn-next]').hidden = n === 4;
            submitBtn.hidden = n !== 4;
            // I6：确认面板 data-blocked="true"（扣除没有宠物的人后净 0）→「确认发送」置灰；离开 ④ 即解除
            submitBtn.disabled = n === 4 && reviewBlocked();
            window.scrollTo(0, 0);
        }

        function reviewBlocked() {
            var panel = form.querySelector('#opsn-review [data-blocked]');
            return !!panel && panel.getAttribute('data-blocked') === 'true';
        }

        function showErr(key, text, inputs) {
            var p = $(form, '[data-ferr="' + key + '"]');
            if (p) { p.textContent = text; p.hidden = false; }
            (inputs || []).forEach(function (el) { if (el) { el.classList.add('is-invalid'); } });
            return false;
        }
        function clearErrs() {
            $$(form, '[data-ferr]').forEach(function (p) { p.hidden = true; p.textContent = ''; });
            $$(form, '.is-invalid').forEach(function (el) { el.classList.remove('is-invalid'); });
            if (err) { err.innerHTML = ''; }
        }

        // 验收 2026-10-15：改了任一字段即收起「下一步」留下的步骤提示与该字段红边（原先选了人群红字还在）
        ['input', 'change'].forEach(function (evt) {
            form.addEventListener(evt, function (e) {
                $$(form, '[data-ferr]').forEach(function (p) { p.hidden = true; p.textContent = ''; });
                if (e.target && e.target.classList) { e.target.classList.remove('is-invalid'); }
            });
        });

        function wibNow() {
            // WIB 固定 UTC+7（无夏令时）：与服务端 ScheduleWindow 同口径
            return new Date(Date.now() + 7 * 3600 * 1000).toISOString().slice(0, 16);
        }

        function validate(n) {
            clearErrs();
            if (n === 1) {
                var ok = true;
                syncAll();
                // 与服务端同序：空 → 手打变量 → 超长；标红落在编辑区（隐藏字段不标）
                var blank = TEXT_FIELDS.filter(function (k) { return !val(k); });
                var typed = TEXT_FIELDS.filter(function (k) { return typedIn(edOf(k)); });
                var over = TEXT_FIELDS.filter(function (k) { return isOver(edOf(k)); });
                if (blank.length) {
                    ok = showErr('text', msg('text'), blank.map(edOf));
                } else if (typed.length) {
                    ok = showErr('text', msg('var-typed'), typed.map(edOf));
                } else if (over.length) {
                    ok = showErr('text', msg('too-long'), over.map(edOf));
                }
                var type = val('targetType');
                var tv = val('targetValue');
                var typeEl = form.querySelector('[name="targetType"]');
                var valEl = form.querySelector('[name="targetValue"]:not([disabled])');
                if (!type) {
                    ok = showErr('target', msg('type'), [typeEl]);
                } else if (type !== 'NONE' && !tv) {
                    ok = showErr('target', msg('value'), [valEl]);
                } else if (type === 'EXTERNAL' && tv.toLowerCase().indexOf('https://') !== 0) {
                    ok = showErr('target', msg('https'), [valEl]);
                }
                return ok;
            }
            if (n === 2) {
                var m = mode();
                if (!m) { return showErr('audience', msg('mode')); }
                if (m === 'IDS') {
                    if (!ids.value.trim()) { return showErr('audience', msg('ids'), [ids]); }
                    if (state.ids === 'pending') { return showErr('audience', msg('pending')); }
                    var banner = idsResult && idsResult.querySelector('[data-valid]');
                    if (!banner) { return showErr('audience', msg('pending')); }
                    if (parseInt(banner.getAttribute('data-valid'), 10) <= 0) { return showErr('audience', msg('empty'), [ids]); }
                    return true;
                }
                if (state.estimate === 'pending' || state.estimate === 'idle') { return showErr('audience', msg('pending')); }
                var c = estimate && estimate.querySelector('[data-count]');
                if (!c) { return showErr('audience', msg('empty')); }
                // AB-37 拍板 ①：净 0 但扣前 > 0（只因用了宠物变量）→ 放行，由 ④ 确认页 I6 拦；扣前也是 0 → 当前人群 0 人
                var net = parseInt(c.getAttribute('data-count'), 10);
                var total = parseInt(c.getAttribute('data-total'), 10);
                if (!(net > 0) && !(total > 0)) { return showErr('audience', msg('empty')); }
                return true;
            }
            if (n === 3) {
                if (sendMode() === 'SCHEDULED') {
                    var at = form.querySelector('[name="scheduledAt"]');
                    if (!at.value) { return showErr('schedule', msg('at'), [at]); }
                    if (at.value <= wibNow()) { return showErr('schedule', msg('past'), [at]); }
                }
                return true;
            }
            return true;
        }

        function next() {
            if (!validate(current)) { return; }
            if (current === 3) {
                // 服务端全验 + 渲染确认面板；成功由 opsn:reviewed 切到 ④
                if (window.htmx && reviewTrigger) { window.htmx.trigger(reviewTrigger, 'opsn-review'); }
                return;
            }
            go(current + 1);
        }

        form.addEventListener('click', function (e) {
            var t = e.target;
            if (t.closest('[data-opsn-next]')) { e.preventDefault(); next(); return; }
            if (t.closest('[data-opsn-prev]')) { e.preventDefault(); clearErrs(); go(Math.max(1, current - 1)); return; }
            var tab = t.closest('[data-step-tab]');
            if (tab) {
                var k = parseInt(tab.getAttribute('data-step-tab'), 10);
                if (k < current) { clearErrs(); go(k); }
                return;
            }
            var ins = t.closest('[data-ins-var]');
            if (ins) {
                e.preventDefault();
                var fld = ins.closest('.opsn-fld');
                var target = fld && fld.querySelector('[data-ed]');
                if (target) { insertVar(target, ins.getAttribute('data-ins-var')); }
                return;
            }
            if (t.closest('[data-opsn-upload]')) { e.preventDefault(); $(form, '[data-opsn-file]').click(); return; }
            if (t.closest('[data-opsn-export]')) { e.preventDefault(); exportSkipped(); }
        });

        // ---------- 文案编辑区（V1.4.2 AB-37 · Story 7.2：contenteditable + 整体变量标签）----------
        var TEXT_FIELDS = ['titleEn', 'titleId', 'bodyEn', 'bodyId'];
        var TOKENS = { nickname: '{{nickname}}', petName: '{{petName}}' };
        var TOKEN_RE = /\{\{(nickname|petName)\}\}/g;
        var VAR_WEIGHT = 20; // = OpsTextVariables.VAR_WEIGHT
        var ZW = '\u200B';   // 光标锚点：标签后放一个，计数 / 序列化 / 手打检测 / 示例都去掉
        var ZW_RE = /\u200B/g;
        var saved = {};      // 每个编辑区最近一次的选区（变量按钮插在这里）
        var typedRe = buildTypedRe();
        var lastUsesPet = null;

        function edOf(name) { return form.querySelector('[data-ed="' + name + '"]'); }
        function hiddenOf(ed) { return form.querySelector('input[type="hidden"][name="' + ed.getAttribute('data-ed') + '"]'); }
        function single(ed) { return ed.hasAttribute('data-single-line'); }
        function isVar(n) { return !!n && n.nodeType === 1 && n.hasAttribute('data-var'); }
        function noZw(s) { return String(s || '').replace(ZW_RE, ''); }
        function clean(s) { return noZw(s).replace(/\u00A0/g, ' '); }
        function zwOnly(n) { return !!n && n.nodeType === 3 && noZw(n.nodeValue) === ''; }
        function varAncestor(n, ed) {
            for (; n && n !== ed; n = n.parentNode) { if (isVar(n)) { return n; } }
            return null;
        }
        function escRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }
        // 与 OpsTextVariables.TYPED_DISPLAY 同口径：显示名带半角 / 全角花括号，忽略大小写、括号内首尾空白，词间任意空白
        function buildTypedRe() {
            var names = [];
            try { names = JSON.parse(form.getAttribute('data-var-names') || '[]'); } catch (e) { names = []; }
            var alt = names.filter(Boolean).map(function (n) {
                return n.split(' ').map(escRe).join('[\\s\\u3000]+');
            });
            return alt.length ? new RegExp('[{\\uFF5B][\\s\\u3000]*(?:' + alt.join('|') + ')[\\s\\u3000]*[}\\uFF5D]', 'i') : null;
        }

        // 编辑区 → 片段序列：{text} / {v: 变量名}。<br> 与块元素（DIV / P 前置）在正文里是换行、在标题里忽略；
        // 块元素里作为最后一个子节点的 <br> 是浏览器的空行占位（<div><br></div>），跳过，免得空行多算一行。
        // 其它元素（粘贴 / 浏览器包出来的 span 等）一律递归——里面的变量标签照样识别为令牌，不会被压成显示名文字。
        function walk(node, line, out) {
            var block = node.nodeType === 1 && (node.tagName === 'DIV' || node.tagName === 'P') && !node.hasAttribute('data-ed');
            for (var c = node.firstChild; c; c = c.nextSibling) {
                if (c.nodeType === 3) { out.push({ text: clean(c.nodeValue) }); continue; }
                if (c.nodeType !== 1) { continue; }
                if (isVar(c)) { out.push({ v: c.getAttribute('data-var') }); continue; }
                if (c.tagName === 'BR') {
                    if (!line && !(block && !c.nextSibling)) { out.push({ text: '\n' }); }
                    continue;
                }
                if ((c.tagName === 'DIV' || c.tagName === 'P') && !line) { out.push({ text: '\n' }); }
                walk(c, line, out);
            }
            return out;
        }
        function parts(ed) { return walk(ed, single(ed), []); }
        function serialize(ed) {
            return parts(ed).map(function (p) { return p.v ? (TOKENS[p.v] || '') : p.text; }).join('').trim();
        }
        // 字数：与服务端 OpsTextVariables.countedLength 同口径（对 trim 后的令牌文本计，每个令牌按 20）
        function counted(text) {
            var n = text.length;
            text.replace(TOKEN_RE, function (m) { n += VAR_WEIGHT - m.length; return m; });
            return n;
        }
        function isOver(ed) { return !!ed && counted(serialize(ed)) > parseInt(ed.getAttribute('data-max'), 10); }
        // 手打检测只看纯文本节点（标签不算）；标签处断开，避免「{」+ 标签 +「…}」被拼成一处
        function typedIn(ed) {
            if (!ed) { return false; }
            var text = parts(ed).map(function (p) { return p.v ? '\n' : p.text; }).join('');
            return text.indexOf('{{') >= 0 || (!!typedRe && typedRe.test(text));
        }

        function makeTag(name) {
            var s = document.createElement('span');
            s.className = 'opsn-var';
            s.setAttribute('contenteditable', 'false');
            s.setAttribute('data-var', name);
            s.textContent = msg('tag-' + name);
            return s;
        }
        // 隐藏字段（令牌文本）→ 编辑区（页面加载 / 浏览器回填时）
        function fill(ed, text) {
            while (ed.firstChild) { ed.removeChild(ed.firstChild); }
            function addText(seg) {
                seg.split('\n').forEach(function (line, i) {
                    if (i > 0) { ed.appendChild(single(ed) ? document.createTextNode(' ') : document.createElement('br')); }
                    if (line) { ed.appendChild(document.createTextNode(line)); }
                });
            }
            var last = 0;
            var m;
            TOKEN_RE.lastIndex = 0;
            while ((m = TOKEN_RE.exec(text)) !== null) {
                addText(text.slice(last, m.index));
                ed.appendChild(makeTag(m[1]));
                ed.appendChild(document.createTextNode(ZW));
                last = TOKEN_RE.lastIndex;
            }
            addText(text.slice(last));
        }

        // 序列化写隐藏字段 + 计数 + 超长红字 + 示例效果；宠物变量用没用发生变化 → 重算预计触达（AC4.3）
        function sync(ed) {
            var text = serialize(ed);
            var hidden = hiddenOf(ed);
            if (hidden) { hidden.value = text; }
            var name = ed.getAttribute('data-ed');
            var max = parseInt(ed.getAttribute('data-max'), 10);
            var n = counted(text);
            var c = form.querySelector('[data-count-of="' + name + '"]');
            if (c) {
                c.textContent = n + ' / ' + max;
                c.classList.toggle('is-over', n > max);
            }
            ed.classList.toggle('is-over', n > max);
            var ex = form.querySelector('[data-example-of="' + name + '"]');
            if (ex) {
                var sampleNick = form.getAttribute('data-sample-nickname') || '';
                var samplePet = form.getAttribute('data-sample-pet') || '';
                var out = parts(ed).map(function (p) {
                    return p.v ? (p.v === 'petName' ? samplePet : sampleNick) : p.text;
                }).join('').trim();
                var box = ex.querySelector('[data-example-text]');
                if (box) { box.textContent = out; } // 🔒 textContent，不用 innerHTML
                ex.hidden = !text;
            }
            var pet = TEXT_FIELDS.some(function (k) {
                var h = form.querySelector('input[type="hidden"][name="' + k + '"]');
                return !!h && h.value.indexOf(TOKENS.petName) >= 0;
            });
            if (lastUsesPet !== null && pet !== lastUsesPet && (mode() === 'ALL' || mode() === 'FILTER')) { refreshEstimate(); }
            lastUsesPet = pet;
        }
        function syncAll() { $$(form, '[data-ed]').forEach(sync); }

        // 光标记录：选区落在某编辑区内就存一份（变量按钮 mousedown 不抢焦点，靠它插回原处）。
        // 选区离开该编辑区（blur / 选到别处）后，记下的选区折叠到末尾：用户已看不到的选中文字不能被插入动作删掉。
        function collapseSaved(name) { if (saved[name]) { saved[name].collapse(false); } }
        function rememberSelection() {
            var sel = window.getSelection();
            var ed = null;
            if (sel && sel.rangeCount) {
                var n = sel.anchorNode;
                var el = n && (n.nodeType === 1 ? n : n.parentNode);
                ed = el && el.closest ? el.closest('[data-ed]') : null;
                if (ed && !form.contains(ed)) { ed = null; }
            }
            var cur = ed ? ed.getAttribute('data-ed') : null;
            Object.keys(saved).forEach(function (k) { if (k !== cur) { collapseSaved(k); } });
            if (cur) { saved[cur] = sel.getRangeAt(0).cloneRange(); }
        }
        document.addEventListener('selectionchange', rememberSelection);

        function placeCaret(r) {
            var sel = window.getSelection();
            sel.removeAllRanges();
            sel.addRange(r);
        }
        function insertVar(ed, name) {
            if (!TOKENS[name]) { return; }
            ed.focus();
            var r = saved[ed.getAttribute('data-ed')];
            if (!r || !ed.contains(r.startContainer) || !ed.contains(r.endContainer)) {
                r = document.createRange();
                r.selectNodeContents(ed);
                r.collapse(false); // 没有光标记录 → 插到末尾
            }
            var inside = varAncestor(r.startContainer, ed) || varAncestor(r.endContainer, ed);
            if (inside) { r = document.createRange(); r.setStartAfter(inside); r.collapse(true); }
            r.deleteContents();
            var zw = document.createTextNode(ZW);
            r.insertNode(zw);
            r.insertNode(makeTag(name)); // insertNode 插在区间起点：标签在前、锚点在后
            var after = document.createRange();
            after.setStart(zw, 1);
            after.collapse(true);
            placeCaret(after);
            saved[ed.getAttribute('data-ed')] = after.cloneRange();
            ed.dispatchEvent(new Event('input', { bubbles: true })); // 计数 / 示例 / 序列化 / 清错误统一走 input
        }

        // Backspace / Delete 整体删标签（跳过只含锚点的文本）；删后派发 input
        function adjacentVar(ed, r, back) {
            var c = r.startContainer;
            var o = r.startOffset;
            var n;
            if (c.nodeType === 3) {
                var rest = back ? c.nodeValue.slice(0, o) : c.nodeValue.slice(o);
                if (noZw(rest) !== '') { return null; }
                n = back ? c.previousSibling : c.nextSibling;
            } else {
                n = back ? c.childNodes[o - 1] : c.childNodes[o];
            }
            while (zwOnly(n)) { n = back ? n.previousSibling : n.nextSibling; }
            return isVar(n) && ed.contains(n) ? n : null;
        }
        function onKeydown(e) {
            if (e.isComposing || e.keyCode === 229) { return; } // 输入法组字中（印尼 / 中文 IME）：交给浏览器
            var ed = e.currentTarget;
            if (e.key === 'Enter' && single(ed)) { e.preventDefault(); return; }
            if (e.key !== 'Backspace' && e.key !== 'Delete') { return; }
            var sel = window.getSelection();
            if (!sel || !sel.rangeCount || !sel.isCollapsed) { return; } // 跨标签选区交给浏览器整块删
            var r = sel.getRangeAt(0);
            var tag = adjacentVar(ed, r, e.key === 'Backspace');
            if (!tag) { return; }
            e.preventDefault();
            var next = tag.nextSibling;
            if (zwOnly(next)) { next.parentNode.removeChild(next); } // 连同其后的锚点
            var at = document.createRange();
            at.setStartBefore(tag);
            at.collapse(true);
            tag.parentNode.removeChild(tag);
            placeCaret(at);
            ed.dispatchEvent(new Event('input', { bubbles: true }));
        }
        // 粘贴 / 拖放只收纯文本（粘进来的「{用户昵称}」永远是文字，会被手打检测拦住——预期）。
        // 自己按 \n 建文本节点与 <br> 插入，不走 insertText 的分段逻辑（多段带空行会被包成 <div><br></div>）。
        function plainNodes(ed, t) {
            var nodes = [];
            t.split('\n').forEach(function (line, i) {
                if (i > 0) { nodes.push(document.createElement('br')); }
                if (line) { nodes.push(document.createTextNode(line)); }
            });
            return nodes;
        }
        function insertPlain(ed, text) {
            var t = String(text || '').replace(/\r\n?/g, '\n');
            if (single(ed)) { t = t.replace(/\n/g, ' '); }
            if (!t) { return; }
            var sel = window.getSelection();
            var r = sel && sel.rangeCount ? sel.getRangeAt(0) : null;
            if (!r || !ed.contains(r.startContainer) || !ed.contains(r.endContainer)) {
                r = document.createRange();
                r.selectNodeContents(ed);
                r.collapse(false);
            }
            var inside = varAncestor(r.startContainer, ed) || varAncestor(r.endContainer, ed);
            if (inside) { r = document.createRange(); r.setStartAfter(inside); r.collapse(true); }
            r.deleteContents();
            var nodes = plainNodes(ed, t);
            var frag = document.createDocumentFragment();
            nodes.forEach(function (n) { frag.appendChild(n); });
            var last = nodes[nodes.length - 1];
            r.insertNode(frag);
            // 以 <br> 结尾且后面没东西：补一个占位 <br>，光标才落得到新行（序列化时首尾 trim 掉）
            if (last.nodeName === 'BR' && !last.nextSibling) { last.parentNode.appendChild(document.createElement('br')); }
            var after = document.createRange();
            after.setStartAfter(last);
            after.collapse(true);
            placeCaret(after);
            saved[ed.getAttribute('data-ed')] = after.cloneRange();
            ed.dispatchEvent(new Event('input', { bubbles: true }));
        }
        function onPaste(e) {
            e.preventDefault();
            var cd = e.clipboardData || window.clipboardData;
            insertPlain(e.currentTarget, cd ? cd.getData('text/plain') || cd.getData('Text') : '');
        }
        function onDrop(e) {
            e.preventDefault();
            var ed = e.currentTarget;
            var text = e.dataTransfer ? e.dataTransfer.getData('text/plain') : '';
            if (!text) { return; }
            var r = document.caretRangeFromPoint ? document.caretRangeFromPoint(e.clientX, e.clientY) : null;
            ed.focus();
            if (r && ed.contains(r.startContainer) && !varAncestor(r.startContainer, ed)) { placeCaret(r); }
            insertPlain(ed, text);
        }
        // 禁富文本格式化（Ctrl+B / I / U）；标题禁回车；正文回车统一产出 <br>（序列化为 \n）
        function onBeforeInput(e) {
            var ed = e.currentTarget;
            var it = e.inputType || '';
            if (it.indexOf('format') === 0) { e.preventDefault(); return; }
            // 撤销 / 重做禁用：标签插入、整体删除、粘贴都是脚本改 DOM，不在浏览器撤销栈里，撤销会把编辑区还原成错乱状态
            if (it === 'historyUndo' || it === 'historyRedo') { e.preventDefault(); return; }
            if (it === 'insertParagraph' || it === 'insertLineBreak') {
                if (single(ed)) { e.preventDefault(); return; }
                if (it === 'insertParagraph') { e.preventDefault(); document.execCommand('insertLineBreak'); }
            }
        }
        $$(form, '[data-ed]').forEach(function (ed) {
            var hidden = hiddenOf(ed);
            if (hidden && hidden.value) { fill(ed, hidden.value); }
            ed.addEventListener('keydown', onKeydown);
            ed.addEventListener('paste', onPaste);
            ed.addEventListener('drop', onDrop);
            ed.addEventListener('beforeinput', onBeforeInput);
            ed.addEventListener('blur', function () { collapseSaved(ed.getAttribute('data-ed')); });
            ed.addEventListener('dragstart', function (e) { if (varAncestor(e.target, ed)) { e.preventDefault(); } });
        });
        // 变量按钮：mousedown 不抢焦点、不丢选区（超长时仍可点：允许插入、提示超长、不能下一步，I3）
        $$(form, '[data-ins-var]').forEach(function (b) {
            b.addEventListener('mousedown', function (e) { e.preventDefault(); });
        });
        syncAll();

        // ---------- 人群 ----------
        function setPanel(box, show) {
            box.hidden = !show;
            box.querySelectorAll('input, select, textarea').forEach(function (inp) { inp.disabled = !show; });
        }
        function refreshEstimate() {
            if (!estimate || !window.htmx) { return; }
            state.estimate = 'pending';
            window.htmx.trigger(estimate, 'opsn-refresh');
        }
        function refreshIds() {
            if (!ids || !window.htmx) { return; }
            if (!ids.value.trim()) { state.ids = 'idle'; if (idsResult) { idsResult.innerHTML = ''; } return; }
            state.ids = 'pending';
            window.htmx.trigger(ids, 'opsn-refresh');
        }
        function applyMode() {
            var m = mode();
            $$(form, '[data-mode-panel]').forEach(function (box) { setPanel(box, box.getAttribute('data-mode-panel') === m); });
            var card = $(form, '[data-estimate-card]');
            if (card) { card.hidden = !(m === 'ALL' || m === 'FILTER'); }
            if (m === 'ALL' || m === 'FILTER') { refreshEstimate(); } else if (m === 'IDS') { refreshIds(); }
        }
        function applySend() {
            var m = sendMode();
            $$(form, '[data-send-panel]').forEach(function (box) { setPanel(box, box.getAttribute('data-send-panel') === m); });
        }

        form.addEventListener('change', function (e) {
            var t = e.target;
            if (t.name === 'audienceMode') { applyMode(); return; }
            if (t.name === 'sendMode') { applySend(); return; }
            if (t.closest && t.closest('[data-mode-panel="FILTER"]')) { refreshEstimate(); return; }
            if (t.hasAttribute && t.hasAttribute('data-opsn-file')) { readCsv(t); }
        });
        form.addEventListener('input', function (e) {
            var t = e.target;
            var ed = t.closest && t.closest('[data-ed]');
            if (ed) { sync(ed); }
            if (t === ids) {
                if (e.isTrusted && idsSource) { idsSource.value = 'PASTE'; }
                state.ids = ids.value.trim() ? 'pending' : 'idle';
                if (!ids.value.trim() && idsResult) { idsResult.innerHTML = ''; }
            }
            if (t.classList) { t.classList.remove('is-invalid'); }
        });

        // ---------- CSV ----------
        function readCsv(input) {
            var f = input.files && input.files[0];
            input.value = '';
            if (!f) { return; }
            var max = parseInt(form.getAttribute('data-max-csv'), 10) || 0;
            if (max && f.size > max) { showErr('audience', msg('csv'), [ids]); return; }
            var reader = new FileReader();
            reader.onload = function () {
                var text = String(reader.result || '');
                // 按 UTF-8 解出替换符 U+FFFD = 不是 UTF-8（Excel 默认另存的 CSV 多是 GBK / ANSI）：提示另存，不填入、不提交
                if (text.indexOf('\uFFFD') >= 0) { showErr('audience', msg('csv-enc'), [ids]); return; }
                ids.value = text.replace(/^﻿/, '');
                if (idsSource) { idsSource.value = 'CSV'; }
                clearErrs();
                state.ids = 'pending';
                ids.dispatchEvent(new Event('input', { bubbles: true })); // 合成事件：不会把来源改回 PASTE
            };
            reader.readAsText(f, 'UTF-8');
        }

        function exportSkipped() {
            var span = document.querySelector('[data-csrf]');
            var f = document.createElement('form');
            f.method = 'post';
            f.action = form.getAttribute('data-export-url');
            f.hidden = true;
            function add(n, v) { var i = document.createElement('input'); i.type = 'hidden'; i.name = n; i.value = v; f.appendChild(i); }
            add('userIds', ids.value);
            if (span && span.getAttribute('data-csrf-token')) { add('_csrf', span.getAttribute('data-csrf-token')); }
            document.body.appendChild(f);
            f.submit();
            document.body.removeChild(f);
        }

        // ---------- 服务端回包 ----------
        document.body.addEventListener('htmx:afterSwap', function (e) {
            var t = e.detail && e.detail.target;
            if (!t) { return; }
            var ok = e.detail.xhr && e.detail.xhr.status < 300;
            if (t === estimate) { state.estimate = ok ? 'ready' : 'error'; return; }
            if (t === idsResult) { state.ids = ok ? 'ready' : 'error'; return; }
            if (t === err) {
                var code = err.querySelector('.inline-error[data-code]');
                var step = code && steps[code.getAttribute('data-code')];
                if (step && step !== current) { go(step); }
            }
        });
        document.body.addEventListener('htmx:responseError', function (e) {
            var elt = e.detail && e.detail.elt;
            if (elt === estimate) { state.estimate = 'error'; }
            if (elt === ids) { state.ids = 'error'; }
        });
        document.body.addEventListener('opsn:reviewed', function () { go(4); });
        // I6 兜底：确认面板被拦（净 0）时不发「确认发送」请求（按钮已置灰；防其它途径触发表单提交）
        document.body.addEventListener('htmx:beforeRequest', function (e) {
            if (e.detail && e.detail.elt === form && current === 4 && reviewBlocked()) { e.preventDefault(); }
        });

        // ---------- 「现在是 WIB …」每 30 秒走一次 ----------
        var nowEl = $(form, '[data-now-wib]');
        if (nowEl && msg('now')) {
            setInterval(function () { nowEl.textContent = msg('now').replace('{0}', wibNow().replace('T', ' ')); }, 30000);
        }

        applyMode();
        applySend();
        go(1);
    }

    document.addEventListener('DOMContentLoaded', function () {
        var form = document.querySelector('form[data-opsn-wizard]');
        if (form) { init(form); }
    });
})();
