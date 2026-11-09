/*
 * ops AI 查数页交互（主题 ops-ai-query）。事件委托：右栏与输入框会被 htmx 整块替换。
 *  · 示例问题：点击只填入输入框，不发送（UX-DR6）
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
})();
