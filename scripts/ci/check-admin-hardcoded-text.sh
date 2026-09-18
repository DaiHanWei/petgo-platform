#!/usr/bin/env bash
# 后台裸文案守门（V1.3.0 Story 11.2 · AC2，NFR-3 / UX-DR12）：
# 模板与前端脚本里**不许有写死的单语文案**——所有用户可见文字必须走 `#{key}`，三语同批。
#
# 用法：bash scripts/ci/check-admin-hardcoded-text.sh
# 纯 bash + python3（GitHub runner 自带），不需要 JDK / Docker / 浏览器。
#
# ============================ 判定规则 ============================
# 扫 `templates/admin/**/*.html` 与 `static/admin/**/*.js`。
#
# ## 一、模板正文里的 CJK
# 允许的位置只有三类：
#   ① **注释**：HTML `<!-- -->`（含 Thymeleaf 的 `<!--/* */-->`）、JS 的 `//` 与 `/* */`。
#      —— 本项目注释里写的全是「为什么这么做」，必须能写中文。
#   ② **占位默认文本**：标签自身带 `th:text` / `th:utext` / `th:errors`，**且其值里含 `#{`**。
#      🔴 「带 th:text 就放行」是不够的（复审 C1）：`th:text="'系统故障待跟进'"` 和
#      `th:text="|共 ${n} 条|"` 都带 th:text，渲染出来却是写死的中文。AC2 的原话是
#      「同一标签带 `th:text="#{…}"`」——**`#{` 才是判据**。
#      带这类属性的标签，其**整棵子树**都算占位（`th:utext` 里常嵌 `<b>`）。
#   ③ **`th:replace` / `th:insert` 标签的直接文字**：这一块运行时被整体换掉
#      （`<div th:replace="${content}">页面内容占位</div>`）。
#      ⚠️ 只放行**直接**文字，不放行整棵子树：本项目每页的正文都挂在
#      `<div th:replace="~{admin/layout :: page(~{::content})}">` 的**子**标签
#      `th:fragment="content"` 里，整棵放行等于整站不查。
#
# ## 二、模板属性里的 CJK：**一律违规**
# 🔴 不再维护「可见属性白名单」（复审 C1：白名单漏了 `value` / `data-confirm` / `th:attr`，
#    而 `th:attr="data-confirm=#{...}"` 恰恰是本仓库确认弹层的标准写法 —— 守门对这条通道完全不设防）。
#    判据反过来：**属性值里出现中文，就是写死了文案**，没有正当用途。
#    单引号 / 双引号都吃（原来只认双引号，`placeholder='中文'` 直接溜过去）。
#    唯一例外：同标签存在同名的 `th:xxx`（`th:placeholder="#{a}" placeholder="占位提示"`），
#    那是设计态占位，与 `<h1 th:text="#{x}">标题</h1>` 同理（复审 P1）。
#
# ## 三、脚本里的用户可见字符串
#   · 含 CJK 的字面量 → 违规；
#   · **来源约束**（AC2 原话：toast / confirm 文案须来自 `data-*` 属性或 `#{}` 注入）：
#     `alert(` / `confirm(` / `.textContent =` / `.title =` / `.innerHTML =` 后面直接跟
#     字母字符串字面量 → 违规，**哪怕它是英文或印尼文**（复审 C6：只查中文的话，
#     `toast('Saved successfully')` 一路绿灯）。已知的历史欠账登记在
#     `scripts/ci/admin-js-text-allowlist.txt`，逐条带 story 号。
#
# ## 四、枚举下拉框直接渲染常量名
#   `th:each="x : ${...}"` + `th:text="${x}"`（不含 `#{`）的 `<option>` —— 切 ID 后
#   直接显示 `ACTIVE` / `ILLEGAL` / `GROWTH_MOMENT`（复审 C8）。
#   「四包 key 数量相等」这类核对**看不见这一类**：它查的是已有 key 的枚举，
#   压根没 key 的枚举不在它的射程里。已知欠账同样登记在上面那份白名单。
#
# 🔴 为什么不用 `grep -P '\p{Han}'`：GNU grep 未必带 PCRE（本仓库 CI 的 3.11 就不带），
#    `-P` 会静默什么都不匹配 —— 守门看起来绿，其实一个字都没查。
# ⚠️ 不防**编码规避**：`'保存'`（JS Unicode 转义）与 `&#x64CD;`（HTML 实体）扫不出来。
#    这条守的是「顺手写死」，不是「刻意绕」。
set -uo pipefail
export LC_ALL=C.UTF-8

TPL="petgo-backend/src/main/resources/templates/admin"
JS="petgo-backend/src/main/resources/static/admin"
ALLOW="scripts/ci/admin-js-text-allowlist.txt"
if [[ ! -d "$TPL" ]]; then
  echo "::error::找不到 $TPL（请在仓库根目录运行）"
  exit 1
fi

python3 - "$TPL" "$JS" "$ALLOW" <<'PY'
import glob
import os
import re
import sys

TPL, JSDIR, ALLOW = sys.argv[1], sys.argv[2], sys.argv[3]
HAN = re.compile(r'[㐀-䶿一-鿿豈-﫿぀-ヿ]')
PLACEHOLDER_ATTRS = ('th:text', 'th:utext', 'th:errors')
DEAD_ATTRS = ('th:replace', 'th:insert')
VOID = {'br', 'hr', 'img', 'input', 'meta', 'link', 'source', 'area',
        'base', 'col', 'embed', 'param', 'track', 'wbr'}
# HTML5 允许省略闭合标签的元素：不做隐式闭合的话，一个没写 </p> 的占位标签会永远留在栈里，
# in_placeholder 恒为 True → **该文件后半段全部失守**，而且没有任何提示（复审 C3）。
OPTIONAL_CLOSE = {'p': {'p', 'div', 'section', 'ul', 'ol', 'table', 'form', 'h1', 'h2', 'h3'},
                  'li': {'li', 'ul', 'ol'},
                  'option': {'option', 'optgroup', 'select'},
                  'td': {'td', 'th', 'tr', 'tbody', 'thead', 'table'},
                  'th': {'td', 'th', 'tr', 'tbody', 'thead', 'table'},
                  'tr': {'tr', 'tbody', 'thead', 'tfoot', 'table'},
                  'dd': {'dd', 'dt', 'dl'},
                  'dt': {'dd', 'dt', 'dl'}}
TOKEN = re.compile(r'<(/?)([a-zA-Z][\w:.-]*)((?:"[^"]*"|\'[^\']*\'|[^>])*?)(/?)>', re.S)
ATTR = re.compile(r'([a-zA-Z_:][\w:.-]*)\s*=\s*("([^"]*)"|\'([^\']*)\')')
# 来源约束：这些接收器后面直接跟字面量字符串就是硬编码文案（不论什么语言）。
JS_SINK = re.compile(r'(?:\b(?:alert|confirm|prompt|toast)\s*\(|'
                     r'\.(?:textContent|innerText|innerHTML|title|placeholder)\s*=)\s*'
                     r'([\'"`])((?:(?!\1)[^\\]|\\.)*)\1')


def blank(m):
    """整块置空但保留换行 —— 行号不能变。"""
    return re.sub(r'[^\n]', ' ', m.group(0))


def strip_js_comments(src, path='<inline>'):
    """把 JS 注释换成等长空格。字符串 / 模板串 / 正则字面量里的 // 不是注释。"""
    out = []
    i, n, state = 0, len(src), None
    prev = ''          # 上一个有意义的非空白字符，用来判断 `/` 起的是正则还是除号
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if state is None:
            if c == '/' and nxt == '/':
                state = '//'
                out.append('  ')
                i += 2
                continue
            if c == '/' and nxt == '*':
                state = '/*'
                out.append('  ')
                i += 2
                continue
            # 🔴 正则字面量：`/it's/` 里的引号不是字符串开始（复审 C5）。
            #    不认它的话，从这行起状态机就错位，后面所有中文注释都会被当成「裸文案」报出来 ——
            #    而 admin-core.js 开头十几行全是中文注释，CI 会红成一片且完全无从理解。
            if c == '/' and prev in '(,=:[!&|?{;+-*%~^<>' + '\n' or (c == '/' and prev == ''):
                j, esc, cls = i + 1, False, False
                while j < n:
                    d = src[j]
                    if esc:
                        esc = False
                    elif d == '\\':
                        esc = True
                    elif d == '[':
                        cls = True
                    elif d == ']':
                        cls = False
                    elif d == '/' and not cls:
                        break
                    elif d == '\n':
                        j = -1
                        break
                    j += 1
                if j > 0 and j < n:
                    out.append(src[i:j + 1])
                    prev = '/'
                    i = j + 1
                    continue
            if c in '\'"`':
                state = c
            out.append(c)
            if not c.isspace():
                prev = c
            i += 1
            continue
        if state == '//':
            if c == '\n':
                state = None
                out.append('\n')
            else:
                out.append(' ')
            i += 1
            continue
        if state == '/*':
            if c == '*' and nxt == '/':
                state = None
                out.append('  ')
                i += 2
                continue
            out.append('\n' if c == '\n' else ' ')
            i += 1
            continue
        if c == '\\':
            out.append(c)
            out.append(nxt)
            i += 2
            continue
        if c == state:
            state = None
            prev = c
        out.append(c)
        i += 1
    if state is not None:
        # 🛡 静默错切 → 显式失败：结束态非 None 说明引号 / 注释不配对，
        #    这时的扫描结果（无论报了几处还是零处）都不可信。
        print(f'::error file={path}::JS 注释剥离结束时状态为 {state!r}（引号或注释不配对），'
              f'本文件的扫描结果不可信，先修文件或修状态机')
        return None
    return ''.join(out)


def liner(s):
    starts = [0]
    for line in s.split('\n'):
        starts.append(starts[-1] + len(line) + 1)

    def lineno(pos):
        lo, hi = 0, len(starts) - 1
        while lo < hi - 1:
            mid = (lo + hi) // 2
            if starts[mid] <= pos:
                lo = mid
            else:
                hi = mid
        return lo + 1
    return lineno


def attrs_of(raw):
    """标签属性 → {名: 值}（单双引号都吃）。"""
    out = {}
    for m in ATTR.finditer(raw):
        out[m.group(1).lower()] = m.group(3) if m.group(3) is not None else m.group(4)
    return out


def is_placeholder(a):
    """占位默认文本：标签带 th:text / th:utext / th:errors —— 正文运行时被整块替换。

    ⚠️ 这里**不要**再要求值里含 `#{`：`th:text="${notice}"` 同样会替换正文，
    标签里那句「已保存」就是给人看设计稿的占位（全站几十处）。
    复审 C1 c/d 指的 `th:text="'系统故障待跟进'"` / `th:text="|共 ${n} 条|"` 是
    **值里写死了中文**，那由下面的「属性值含中文即违规」那条抓 —— 判在值上，不是判在正文上。
    """
    return any(k in a for k in PLACEHOLDER_ATTRS)


def preprocess(raw, path):
    """把注释与 <script>/<style> 一次**顺序**扫过去，各自置空 —— 长度与行号原样保留。

    🔴 必须是**一遍顺序扫**，不能「先去注释再切 script」也不能反过来（复审 C4 + 实测）：
      · 先去注释 → JS 字符串里一个 `<!--` 会让注释正则一路吞到下一个 `-->`，中间的真文案被整块吃掉；
      · 先切 script → **注释里写的 `<script ...>` 示例**（`dashboard-charts.html` 顶部注释里就有一个）
        会跟几十行之后真正的 `</script>` 配成一对，把中间连同注释的 `-->` 一起抹掉，
        整份文件从此错位（实测：该文件报出 3 处假阳性 + 1 处状态机失败）。
      顺序扫的语义与浏览器一致：谁先出现谁生效。
    """
    out = []
    i, n = 0, len(raw)
    low = raw.lower()
    while i < n:
        c1 = low.find('<!--', i)
        c2 = low.find('<script', i)
        c3 = low.find('<style', i)
        cands = [x for x in (c1, c2, c3) if x >= 0]
        if not cands:
            out.append(raw[i:])
            break
        nxt = min(cands)
        out.append(raw[i:nxt])
        if nxt == c1:
            end = low.find('-->', nxt + 4)
            end = n if end < 0 else end + 3
            out.append(blank_keep(raw[nxt:end]))
            i = end
            continue
        tag = 'script' if nxt == c2 else 'style'
        gt = low.find('>', nxt)
        if gt < 0:
            out.append(raw[nxt:])
            break
        close = low.find('</' + tag, gt)
        if close < 0:
            out.append(raw[nxt:gt + 1] + blank_keep(raw[gt + 1:]))
            break
        body = raw[gt + 1:close]
        if tag == 'script':
            cleaned = strip_js_comments(body, path)
            body = cleaned if cleaned is not None else blank_keep(body)
        else:
            body = blank_keep(body)          # <style> 里没有用户可见文案
        out.append(raw[nxt:gt + 1] + body)
        i = close
    return ''.join(out)


def scan_html(path):
    s = preprocess(open(path, encoding='utf-8').read(), path)
    lineno = liner(s)
    bad, stack, pos = [], [], 0
    for m in TOKEN.finditer(s):
        text = s[pos:m.start()]
        hit = HAN.search(text)
        in_placeholder = any(ph for _t, ph, _d in stack)
        parent_dead = bool(stack) and stack[-1][2]
        if hit and not in_placeholder and not parent_dead:
            bad.append((lineno(pos + hit.start()), '正文', text.strip()[:80]))
        pos = m.end()
        closing, tag, raw_attrs, selfclose = m.group(1), m.group(2).lower(), m.group(3), m.group(4)
        if closing:
            while stack:
                if stack.pop()[0] == tag:
                    break
            continue
        # HTML5 隐式闭合：<p>…<p> / <li>…<li> / <option>…<option>（复审 C3）
        while stack and stack[-1][0] in OPTIONAL_CLOSE and tag in OPTIONAL_CLOSE[stack[-1][0]]:
            stack.pop()
        a = attrs_of(raw_attrs)
        if not any(ph for _t, ph, _d in stack):
            for name, val in a.items():
                if not HAN.search(val):
                    continue
                # 设计态占位：同标签有同名的 th:xxx，静态值运行时会被覆盖（复审 P1）
                if not name.startswith('th:') and ('th:' + name) in a:
                    continue
                bad.append((lineno(m.start()), '属性 ' + name, val[:80]))
            # 枚举下拉框直接渲染常量名（复审 C8）
            if tag == 'option' and 'th:each' in a:
                v = a.get('th:text', '')
                if v and '#{' not in v and re.fullmatch(r'\$\{[A-Za-z_][\w.]*\}', v.strip()):
                    bad.append((lineno(m.start()), '枚举未走 key', 'th:text=' + v))
        if selfclose or tag in VOID:
            continue
        stack.append((tag, is_placeholder(a), any(k in a for k in DEAD_ATTRS)))
    tail = s[pos:]
    hit = HAN.search(tail)
    if hit and not any(ph for _t, ph, _d in stack):
        bad.append((lineno(pos + hit.start()), '正文', tail.strip()[:80]))
    return bad


def blank_keep(body):
    return re.sub(r'[^\n]', ' ', body)


def scan_js(path):
    s = strip_js_comments(open(path, encoding='utf-8').read(), path)
    if s is None:
        return [(1, '注释剥离失败', '见上一行 ::error::')]
    bad = []
    for i, line in enumerate(s.split('\n'), 1):
        if HAN.search(line):
            bad.append((i, 'JS 中文字面量', line.strip()[:100]))
    lineno = liner(s)
    for m in JS_SINK.finditer(s):
        lit = m.group(2)
        if not re.search(r'[A-Za-z一-鿿]', lit):
            continue        # '' / '×' / ' ' 这类纯符号不是文案
        if lit.lstrip().startswith('<'):
            continue        # `.innerHTML = '<img alt=""/>'` 是标记不是文案
        ln = lineno(m.start())
        if any(b[0] == ln for b in bad):
            continue        # 同一行已按中文报过，不重复
        bad.append((ln, 'JS 硬编码文案（应来自 data-* / #{}）', m.group(0)[:100]))
    return bad


def allowlist():
    """已知欠账 / 长期例外：每行 `<相对路径>|<类别>   # 依据`。

    ⚠️ 用「文件 + 类别」而不是「文件 + 行号」：行号一改就失效，会让**无关的改动**把 CI 弄红，
    于是所有人学会的第一件事就是「随手更新白名单行号」—— 那时它已经不是护栏了。
    """
    out = set()
    if os.path.exists(ALLOW):
        for line in open(ALLOW, encoding='utf-8'):
            s = line.split('#', 1)[0].strip()
            if s:
                out.add(s)
    return out


allowed = allowlist()
total = 0
skipped = 0
files = sorted(glob.glob(os.path.join(TPL, '**', '*.html'), recursive=True))
# `_kitchen-sink.html` 等下划线开头的模板是 @StagOnly 的样式演示页（Story 2.3b），
# 生产不注册路由、也不给运营看，里面故意堆的是假数据中文。
files = [f for f in files if not os.path.basename(f).startswith('_')]
# 递归找脚本并显式排除 vendor：目录今天是扁平的，但「排除第三方库」这个意图要写成代码，
# 不能靠目录形状碰巧（复审 P3）。
js_files = [f for f in sorted(glob.glob(os.path.join(JSDIR, '**', '*.js'), recursive=True))
            if '/vendor/' not in f and not f.endswith('.min.js')]

for f, scan in [(x, scan_html) for x in files] + [(x, scan_js) for x in js_files]:
    for ln, kind, txt in scan(f):
        rel = f
        if f'{rel}|{kind}' in allowed or f'{rel}:{ln}' in allowed:
            skipped += 1
            continue
        print(f'::error file={f},line={ln}::裸文案（{kind}）：{txt}')
        total += 1

print(f'check-admin-hardcoded-text: 扫描模板 {len(files)} 份 + 脚本 {len(js_files)} 份，'
      f'裸文案 {total} 处'
      + (f'（白名单豁免 {skipped} 处）' if skipped else '')
      + ('' if total == 0 else ' ← 必须改成 #{key} / data-* 注入并三语同批'))
sys.exit(1 if total else 0)
PY
