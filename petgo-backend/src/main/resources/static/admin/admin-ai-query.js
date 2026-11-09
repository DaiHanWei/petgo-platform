/*
 * ops AI 查数页交互（主题 ops-ai-query）。事件委托：右栏与输入框会被 htmx 整块替换。
 *  · 示例问题：点击只填入输入框，不发送（UX-DR6）
 *  · Enter 发送、Shift+Enter 换行；输入法选词（isComposing / keyCode 229）时 Enter 不发送（UX-DR4）
 *  · 提交后禁用发送按钮，防重复提交
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
