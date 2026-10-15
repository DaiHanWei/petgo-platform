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
            $(form, '.opsn-submit').hidden = n !== 4;
            window.scrollTo(0, 0);
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
                var blank = ['titleEn', 'titleId', 'bodyEn', 'bodyId'].filter(function (k) { return !val(k); });
                if (blank.length) {
                    ok = showErr('text', msg('text'), blank.map(function (k) { return form.querySelector('[name="' + k + '"]'); }));
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
                if (!c || parseInt(c.getAttribute('data-count'), 10) <= 0) { return showErr('audience', msg('empty')); }
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
            if (t.closest('[data-opsn-upload]')) { e.preventDefault(); $(form, '[data-opsn-file]').click(); return; }
            if (t.closest('[data-opsn-export]')) { e.preventDefault(); exportSkipped(); }
        });

        // ---------- 文案字数 ----------
        function count(el) {
            var c = form.querySelector('[data-count-of="' + el.name + '"]');
            if (c) { c.textContent = (el.value || '').length + ' / ' + el.getAttribute('data-max'); }
        }
        $$(form, '[data-max]').forEach(count);

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
            if (t.hasAttribute && t.hasAttribute('data-max')) { count(t); }
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
