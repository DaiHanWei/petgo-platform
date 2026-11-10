/*
 * ops AI 查数答案渲染（主题 ops-ai-query，spec §5.4，Story 1.7）。
 *
 * 安全（NFR13，测试见 src/test/js/ai-query-render.test.mjs）：
 *  · marked 解析时禁用原始 HTML（按文本转义输出）与图片（只留替代文字）；
 *  · 再经 DOMPurify 净化后才写入页面：禁止 img / script / style / iframe / form / svg 等，禁止事件属性与 style，
 *    链接只允许 http(s) 与 mailto，其余链接去掉 href；保留下来的链接加 rel="noopener noreferrer"、新窗口打开。
 * 版式（UX-DR7 / DR8）：第一段为结论（aiq-lead）；表格包进圆角容器 aiq-table（过宽时横向滚动）、数字列右对齐等宽；
 *  「统计口径」小节（标题按语言：统计口径 / Scope / Cakupan data）收进 aiq-scope 并移到答案末尾。
 *
 * 浏览器里依赖全局 marked、DOMPurify（/admin/vendor/）；Node 测试通过 create({ window, marked, DOMPurify }) 注入。
 */
(function (root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory;
    } else {
        root.AiqRender = factory({ window: root, marked: root.marked, DOMPurify: root.DOMPurify });
    }
})(typeof self !== 'undefined' ? self : this, function (deps) {
    'use strict';

    var win = deps.window;
    var doc = win.document;
    var SCOPE_LABEL = /^\s*(统计口径|Scope|Cakupan data)\s*[:：]?\s*$/i;
    var NUMERIC = /^\s*(?:[-+−]?\s*(?:Rp\s?|IDR\s?|\$|US\$)?[\d][\d.,\s]*(?:%|万|亿|[kKmM])?|—|-)\s*$/;

    function escapeHtml(s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    var md = new deps.marked.Marked({ gfm: true, breaks: false, async: false });
    md.use({
        renderer: {
            // 原始 HTML（块级与行内）一律按文本输出，不进 DOM
            html: function (token) { return escapeHtml(token.text || token.raw || ''); },
            // 图片：不加载任何外部地址（防止经图片 URL 外带数据），只显示替代文字
            image: function (token) { return escapeHtml(token.text || ''); }
        }
    });

    var purify = deps.DOMPurify(win);
    var PURIFY_CONFIG = {
        ALLOWED_TAGS: ['p', 'br', 'hr', 'strong', 'b', 'em', 'i', 'del', 's', 'code', 'pre', 'blockquote',
            'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
            'table', 'thead', 'tbody', 'tr', 'th', 'td', 'a', 'input'],
        ALLOWED_ATTR: ['href', 'title', 'align', 'colspan', 'rowspan', 'type', 'checked', 'disabled'],
        // 只放行 http(s) / mailto 协议；不带协议的值（如 type="checkbox"、align、相对地址）照常保留。
        // javascript: / data: / vbscript: 等一律不匹配 → 属性被移除（DOMPurify 默认正则的收窄版）
        ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i,
        ALLOW_DATA_ATTR: false,
        ALLOW_ARIA_ATTR: false,
        FORBID_TAGS: ['img', 'image', 'svg', 'math', 'script', 'style', 'iframe', 'frame', 'object', 'embed',
            'form', 'button', 'select', 'textarea', 'video', 'audio', 'source', 'picture', 'link', 'meta', 'base'],
        FORBID_ATTR: ['style', 'src', 'srcset', 'action', 'formaction', 'background', 'poster'],
        RETURN_TRUSTED_TYPE: false
    };
    purify.addHook('afterSanitizeAttributes', function (node) {
        if (node.nodeName === 'A') {
            if (node.hasAttribute('href')) {
                node.setAttribute('rel', 'noopener noreferrer');
                node.setAttribute('target', '_blank');
            }
        }
        // GFM 任务列表的复选框只读
        if (node.nodeName === 'INPUT') {
            if ((node.getAttribute('type') || '').toLowerCase() !== 'checkbox') {
                node.parentNode && node.parentNode.removeChild(node);
            } else {
                node.setAttribute('disabled', '');
            }
        }
    });

    /** Markdown → 净化后的 HTML 字符串。 */
    function toSafeHtml(source) {
        var html = md.parse(String(source || ''));
        return purify.sanitize(html, PURIFY_CONFIG);
    }

    function isScopeHeading(el) {
        return /^H[1-6]$/.test(el.nodeName) && SCOPE_LABEL.test(el.textContent);
    }

    /** 「**统计口径**：…」式的段落 → { label, rest } */
    function inlineScope(el) {
        if (el.nodeName !== 'P') { return null; }
        var first = el.firstElementChild;
        if (!first || el.firstChild !== first || !/^(STRONG|B)$/.test(first.nodeName)) { return null; }
        if (!SCOPE_LABEL.test(first.textContent)) { return null; }
        return first;
    }

    function layout(container) {
        // 1. 表格：圆角容器 + 数字列右对齐
        Array.prototype.forEach.call(container.querySelectorAll('table'), function (table) {
            var wrap = doc.createElement('div');
            wrap.className = 'aiq-table';
            table.parentNode.insertBefore(wrap, table);
            wrap.appendChild(table);
            var rows = table.querySelectorAll('tbody tr');
            var cols = 0;
            Array.prototype.forEach.call(table.rows, function (r) { cols = Math.max(cols, r.cells.length); });
            for (var c = 0; c < cols; c++) {
                var numeric = rows.length > 0;
                Array.prototype.forEach.call(rows, function (r) {
                    var cell = r.cells[c];
                    if (cell && cell.textContent.trim() !== '' && !NUMERIC.test(cell.textContent)) { numeric = false; }
                });
                if (!numeric) { continue; }
                Array.prototype.forEach.call(table.rows, function (r) {
                    if (r.cells[c]) { r.cells[c].classList.add('amt'); }
                });
            }
            // 末行以「合计 / Total」开头时加粗
            var last = rows[rows.length - 1];
            if (last && last.cells[0] && /^\s*(合计|总计|Total|Jumlah|Total keseluruhan)\s*$/i.test(last.cells[0].textContent)) {
                last.classList.add('aiq-sum');
            }
        });

        // 2. 统计口径：收进 aiq-scope，移到末尾
        var scopes = [];
        var children = Array.prototype.slice.call(container.children);
        for (var i = 0; i < children.length; i++) {
            var el = children[i];
            if (isScopeHeading(el)) {
                var box = doc.createElement('div');
                box.className = 'aiq-scope';
                var label = doc.createElement('b');
                label.textContent = el.textContent.replace(/[:：]\s*$/, '').trim();
                box.appendChild(label);
                container.removeChild(el);
                // 收下紧随其后的段落与列表（遇到标题、表格等其他内容即停）
                while (i + 1 < children.length && /^(P|UL|OL|BLOCKQUOTE)$/.test(children[i + 1].nodeName)) {
                    i++;
                    box.appendChild(children[i]);
                }
                scopes.push(box);
                continue;
            }
            var strong = inlineScope(el);
            if (strong) {
                var box2 = doc.createElement('div');
                box2.className = 'aiq-scope';
                var label2 = doc.createElement('b');
                label2.textContent = strong.textContent.replace(/[:：]\s*$/, '').trim();
                el.removeChild(strong);
                if (el.firstChild && el.firstChild.nodeType === 3) {
                    el.firstChild.textContent = el.firstChild.textContent.replace(/^\s*[:：]\s*/, '');
                }
                box2.appendChild(label2);
                box2.appendChild(el);
                scopes.push(box2);
            }
        }
        scopes.forEach(function (s) { container.appendChild(s); });

        // 3. 第一段为结论（统计口径归位之后再找，口径段落不会被当成结论）
        var firstP = container.querySelector(':scope > p');
        if (firstP) { firstP.classList.add('aiq-lead'); }
    }

    /** 渲染进容器：先净化再写入，然后整理版式。 */
    function renderInto(container, source) {
        container.innerHTML = toSafeHtml(source);
        layout(container);
        return container;
    }

    return { toSafeHtml: toSafeHtml, renderInto: renderInto, config: PURIFY_CONFIG };
});
