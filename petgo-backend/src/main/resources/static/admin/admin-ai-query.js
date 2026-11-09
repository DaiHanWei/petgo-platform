/*
 * ops AI 查数页交互（主题 ops-ai-query）。事件委托：右栏与输入框会被轮询整块替换。
 *  · 示例问题：点击只填入输入框，不发送（UX-DR6）
 *  · Enter 发送、Shift+Enter 换行；输入法选词（isComposing / keyCode 229）时 Enter 不发送（UX-DR4）
 *  · 提交后禁用发送按钮，防重复提交
 *  · 计时器：[data-aiq-elapsed] 每秒走表（B3 / B4 / B5）
 *  · 轮询（UX-DR13）：仅在有未完成任务时每 3 秒请求一次，同时更新左栏行与右栏轮次，全部结束后停止
 *  · 离开期间完成：标签页标题显示「(N) 已完成」，回到页面后恢复（UX-DR14）
 *  · 轮询失败：显示连接中断提示，恢复后自动继续（UX-DR15 / E2）
 */
(function () {
    'use strict';

    function questionBox() {
        return document.querySelector('[data-aiq-question]');
    }

    document.addEventListener('click', function (e) {
        var btn = e.target && e.target.closest ? e.target.closest('[data-aiq-sample]') : null;
        if (!btn) { return; }
        var box = questionBox();
        if (!box || box.disabled) { return; }
        box.value = btn.getAttribute('data-aiq-sample') || '';
        box.focus();
        box.setSelectionRange(box.value.length, box.value.length);
    });

    document.addEventListener('keydown', function (e) {
        var box = e.target;
        if (!box || !box.matches || !box.matches('[data-aiq-question]')) { return; }
        if (e.key !== 'Enter' || e.shiftKey || e.altKey || e.ctrlKey || e.metaKey) { return; }
        // 输入法组合中（中文 / 日文选词）：Enter 用于确认候选词，不发送
        if (e.isComposing || e.keyCode === 229) { return; }
        e.preventDefault();
        var form = box.closest('form');
        if (!form || box.disabled) { return; }
        if (typeof form.requestSubmit === 'function') { form.requestSubmit(); } else { form.submit(); }
    });

    document.addEventListener('submit', function (e) {
        var form = e.target;
        if (!form || !form.matches || !form.matches('[data-aiq-form]')) { return; }
        if (form.getAttribute('data-aiq-submitting') === '1') { e.preventDefault(); return; }
        form.setAttribute('data-aiq-submitting', '1');
        var btn = form.querySelector('[data-aiq-send]');
        if (btn) { btn.disabled = true; }
    });
    // ---------- 计时器 ----------
    function pad(n) { return n < 10 ? '0' + n : String(n); }
    function clock(secs) {
        var h = Math.floor(secs / 3600), m = Math.floor((secs % 3600) / 60), s = secs % 60;
        return h > 0 ? h + ':' + pad(m) + ':' + pad(s) : pad(m) + ':' + pad(s);
    }
    function tick() {
        var now = Date.now();
        document.querySelectorAll('[data-aiq-elapsed]').forEach(function (el) {
            if (!el._aiqT0) { el._aiqT0 = now - (parseInt(el.getAttribute('data-aiq-elapsed'), 10) || 0) * 1000; }
            el.textContent = clock(Math.max(0, Math.floor((now - el._aiqT0) / 1000)));
        });
    }

    // ---------- 轮询 ----------
    var POLL_MS = 3000;
    var MAX_W = 50, MAX_M = 10; // 与服务端 POLL_MAX_* 一致
    var failures = 0;
    var stopped = false;
    var pollTimer = null;
    var polling = false;
    var awayDone = 0;
    var baseTitle = document.title;

    function page() { return document.querySelector('[data-aiq-page]'); }

    function watched() {
        var w = [], m = [];
        document.querySelectorAll('#aiq-rows > li[data-aiq-unfinished]').forEach(function (li) {
            var t = li.getAttribute('data-token');
            if (t && w.indexOf(t) < 0) { w.push(t); }
        });
        document.querySelectorAll('.aiq-turn[data-aiq-unfinished]').forEach(function (el) {
            m.push(el.id.replace(/^aiq-msg-/, ''));
        });
        // 只发送服务端会处理的数量；「查不到即停止关注」也只针对真正发出去的 token
        return { w: w.slice(0, MAX_W), m: m.slice(0, MAX_M) };
    }

    function setConn(lost) {
        var el = document.getElementById('aiq-conn');
        if (el) { el.hidden = !lost; }
    }

    function schedule() {
        if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
        if (stopped) { return; }
        var x = watched();
        if (x.w.length === 0 && x.m.length === 0) { setConn(false); return; }
        // 连续失败时退避（3s → 6s → 12s … 最长 30s），恢复后回到 3 秒
        var delay = failures === 0 ? POLL_MS : Math.min(30000, POLL_MS * Math.pow(2, failures));
        pollTimer = setTimeout(pollOnce, delay);
    }

    function pollOnce() {
        pollTimer = null;
        var root = page();
        if (!root || polling) { return; }
        var x = watched();
        if (x.w.length === 0 && x.m.length === 0) { return; }
        var params = new URLSearchParams();
        x.w.forEach(function (t) { params.append('w', t); });
        x.m.forEach(function (t) { params.append('m', t); });
        var c = root.getAttribute('data-aiq-conversation');
        if (c) { params.append('c', c); }
        polling = true;
        fetch(root.getAttribute('data-aiq-poll-url') + '?' + params.toString(), {
            credentials: 'same-origin', headers: { 'Accept': 'text/html' }, cache: 'no-store'
        }).then(function (res) {
            // 无权限 / 会话过期（被带去登录页）：不是连接问题，停止轮询，刷新页面后再看
            if (res.status === 401 || res.status === 403) { stopped = true; throw new Error('auth'); }
            if (!res.ok) { throw new Error('HTTP ' + res.status); }
            return res.text();
        }).then(function (html) {
            var doc = new DOMParser().parseFromString('<!DOCTYPE html><body>' + html + '</body>', 'text/html');
            var result = doc.querySelector('[data-aiq-poll]');
            if (!result) {
                if (/<html/i.test(html)) { stopped = true; }
                throw new Error('unexpected response');
            }
            apply(result, x);
            failures = 0;
            setConn(false);
        }).catch(function () {
            failures++;
            setConn(!stopped);
        }).then(function () {
            polling = false;
            schedule();
        });
    }

    function apply(result, before) {
        var finishedConvs = {};
        var seen = {};
        var current = page() && page().getAttribute('data-aiq-conversation');
        Array.prototype.forEach.call(result.children, function (fresh) {
            if (!fresh.id) { return; }
            seen[fresh.id] = true;
            var old = document.getElementById(fresh.id);
            if (!old) { return; }
            if (old.hasAttribute('data-aiq-unfinished') && !fresh.hasAttribute('data-aiq-unfinished')) {
                if (fresh.id.indexOf('aiq-row-') === 0) { finishedConvs[fresh.id.substring(8)] = true; }
                if (fresh.id.indexOf('aiq-msg-') === 0 && current) { finishedConvs[current] = true; }
            }
            var node = document.importNode(fresh, true);
            if (fresh.id === 'aiq-composer-inner') { keepDraft(old, node); }
            old.replaceWith(node);
        });
        var finished = Object.keys(finishedConvs).length;
        // 查不到（他人 / 过期 / 已清除）的不再关注
        before.w.forEach(function (t) {
            if (!seen['aiq-row-' + t]) {
                var li = document.getElementById('aiq-row-' + t);
                if (li) { li.removeAttribute('data-aiq-unfinished'); }
            }
        });
        before.m.forEach(function (t) {
            if (!seen['aiq-msg-' + t]) {
                var el = document.getElementById('aiq-msg-' + t);
                if (el) { el.removeAttribute('data-aiq-unfinished'); }
            }
        });
        tick();
        if (finished > 0 && document.hidden) {
            awayDone += finished;
            var label = (page() && page().getAttribute('data-aiq-done-label')) || 'done';
            document.title = '(' + awayDone + ') ' + label + ' · ' + baseTitle;
        }
        document.dispatchEvent(new CustomEvent('aiq:updated'));
    }

    /** 替换输入框时保留用户已输入的内容（例如追问被拒后继续编辑的草稿）。 */
    function keepDraft(oldEl, newEl) {
        var oldBox = oldEl.querySelector('[data-aiq-question]');
        var newBox = newEl.querySelector('[data-aiq-question]');
        if (oldBox && newBox && !oldBox.disabled && oldBox.value && !newBox.disabled) {
            newBox.value = oldBox.value;
        }
    }

    document.addEventListener('visibilitychange', function () {
        if (!document.hidden && awayDone > 0) {
            awayDone = 0;
            document.title = baseTitle;
        }
    });

    function start() {
        if (!page()) { return; }
        tick();
        // 只在页面上有计时器时走表
        setInterval(function () { if (document.querySelector('[data-aiq-elapsed]')) { tick(); } }, 1000);
        schedule();
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
    // 左栏滚动加载进来的新行也可能有未完成任务
    document.body && document.body.addEventListener('htmx:afterSwap', function () { tick(); if (!pollTimer && !polling) { schedule(); } });

    // 浏览器后退从 bfcache 恢复页面时，清掉「提交中」状态，否则发送按钮一直禁用
    window.addEventListener('pageshow', function (e) {
        if (!e.persisted) { return; }
        document.querySelectorAll('form[data-aiq-form][data-aiq-submitting]').forEach(function (form) {
            form.removeAttribute('data-aiq-submitting');
            var btn = form.querySelector('[data-aiq-send]');
            var box = form.querySelector('[data-aiq-question]');
            if (btn && box && !box.disabled) { btn.disabled = false; }
        });
    });
})();
