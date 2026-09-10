#!/usr/bin/env bash
# 后台写操作清单生成器（V1.3.0 Story 2.1，决策 D-27：AB-19A「重构不丢一项」的验收底账）
#
# 用途：
#   从 petgo-backend 的 admin 包源码扫出全部写端点（@Post/Put/DeleteMapping）与 GET 页面路由，
#   连同 Controller#方法、@PreAuthorize 原文、所属页面组（泳道）、退役标记，生成一份 markdown 清单。
#   Epic 11 Story 11.1 对重构后代码再跑一次做 diff：允许出现的差异只有本版 PRD 定义的新增端点与明示退役的路由。
#
# 用法：
#   bash scripts/ci/list-admin-write-ops.sh                 # 输出到 _bmad-output/implementation-artifacts/v1.3.0/后台写操作清单-<yyyyMMdd>.md
#   bash scripts/ci/list-admin-write-ops.sh --out <path>    # 覆盖输出位置
#   bash scripts/ci/list-admin-write-ops.sh --baseline <清单.md> [--allowlist <txt>]
#                                                          # Story 11.1 diff 模式：与基线清单比对，只报「未解释的差异」，
#                                                          #   有未解释差异 → ::error:: + exit 1（供 CI 直接接）
#
# ⚠️ 扫描根（Story 11.1 T1 修正，「基线 v2」）：admin 包**之外**也有挂 /admin/** 的 Controller
#    （`namemoderation/web/NameModerationAdminController` 的 POST /admin/name-moderation/{id}/decide，
#    它同样受 AdminBusinessExceptionAdvice 接管）。基线 v1（Story 2.1）漏了它 —— 少一行不会让 grep 自检报错，
#    因为自检也只数同一个目录。修脚本后**必须用同一版脚本重跑基线提交**（见核对报告「基线 v2」一节），
#    不能拿 v1 基线去对 v2 输出。
#
# 幂等：表按 路径+方法 排序；除文件名日期与 baseline commit 外无易变内容，同一提交重复运行逐字节一致。
# 自检：表 1 行数必须等于 grep -rhoE "@(Post|Put|Delete)Mapping\(" 的计数，不等则 ::error:: + exit 1。
# 页面组映射：先用「路径前缀 → 泳道」表过渡（与 UI 稿 8 泳道一一对应）；AdminPageCatalog（Story 1.5）落地后，
#   Story 11.1 可改为从 Java 端导出映射。
# 纯 bash + grep/sed/awk/sort，云端可直接跑；不依赖 Docker / mvn。避免 sed -i ''（macOS 差异）。
set -uo pipefail
export LC_ALL=C   # 排序与 awk 行为跨机器一致（幂等）

# 🔴 **扫全树、按路径过滤**（Story 11.1 复审 C1）：判据是「路由挂不挂在 /admin 下」，不是「文件在哪个包」。
#    先前的写法是「admin 包 + 一份手写的包外目录名单」—— 名单漏掉谁，谁就对守门完全隐形：
#    新建一个 com/tailtopia/<随便什么>/XxxAdminController 加个 POST /admin/users/{id}/nuke，
#    既不进清单、不进 grep 自检（自检只数名单里的目录）、也不触发任何 ::error::，CI 恒绿。
#    整树扫的耗时仍是秒级；`/api/v1` 由下面那道 `^/admin` 过滤挡掉，不会混进来。
SRC="petgo-backend/src/main/java/com/tailtopia"
SRC_ADMIN="petgo-backend/src/main/java/com/tailtopia/admin"
OUT_DIR="_bmad-output/implementation-artifacts/v1.3.0"
OUT_DEFAULT="$OUT_DIR/后台写操作清单-$(date +%Y%m%d).md"
OUT="$OUT_DEFAULT"
BASELINE=""
ALLOWLIST="scripts/ci/admin-write-ops-allowlist.txt"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --baseline) BASELINE="$2"; shift 2 ;;
    --allowlist) ALLOWLIST="$2"; shift 2 ;;
    *) echo "::error::未知参数 $1"; exit 2 ;;
  esac
done

if [[ ! -d "$SRC_ADMIN" ]]; then
  echo "::error::找不到 $SRC_ADMIN（请在仓库根目录运行）"
  exit 1
fi

fail=0
baseline=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)
script_version=$(git hash-object scripts/ci/list-admin-write-ops.sh 2>/dev/null | cut -c1-12 || echo unknown)

# ---- 路径前缀 → 泳道（UI 稿 8 泳道；/admin/shop/shipping 归商城，D1） ----
group_of() {
  local p="$1"
  case "$p" in
    /admin|/admin/dashboard*|/admin/charts*) echo "概览" ;;
    /admin/manual-review*|/admin/avatar-review*|/admin/settings/manual-review*|/admin/reports*|/admin/tickets*|/admin/anomalies*|/admin/support-tickets*|/admin/refunds*|/admin/warm-replies*|/admin/name-moderation*) echo "待办中心" ;;
    /admin/seed-post*|/admin/seed-batch*|/admin/seed-assist*|/admin/content*|/admin/comments*|/admin/throttles*|/admin/places*) echo "内容" ;;
    /admin/users*|/admin/user-tags*|/admin/virtual-accounts*|/admin/publish-identities*) echo "用户" ;;
    /admin/consult-orders*|/admin/ai-orders*|/admin/payments*|/admin/settlements*|/admin/red-overage*) echo "订单与资金" ;;
    /admin/shop/*) echo "商城" ;;
    /admin/vets*|/admin/failed-requests*|/admin/ratings*|/admin/consult-sessions*) echo "兽医与问诊" ;;
    /admin/config*|/admin/algo-params*|/admin/audit-logs*|/admin/accounts*|/admin/roles*) echo "配置与安全" ;;
    /admin/login*|/admin/oauth*|/admin/logout*|/admin/denied*|/admin/lang*|/admin/nav/*) echo "登录与壳" ;;
    *) echo "⚠️ 未归组" ;;
  esac
}

# ---- 本版退役路由（PRD AB-19A；备注列写 ⛔ 本版退役（Story x.y）） ----
# 🔴 **退役标记只贴 GET**（Story 11.1 修）：退役的是「整页」，同路径的 POST 处置端点一律仍在服役。
#    `/admin/vets/{id}/qualification` 就是活例 —— GET 退役、POST 保留（11.3 AC1 的「勿误删」名单里写着）；
#    只按路径匹配的话，那条 POST 会带着「已退役」备注进底账，读的人拿到一个自相矛盾的口径。
#    （脚本原来只为 `vets/{id}/edit` 单独绕过这个坑，绕的是个例，不是这一类。）
retired_note() {
  local method="$1" path="$2"
  [[ "$method" == "GET" ]] || { echo ""; return; }
  case "$path" in
    /admin/content/\{postId\}) echo "⛔ 本版退役（7.1，详情抽屉化）" ;;
    /admin/users/\{userId\}) echo "⛔ 本版退役（8.1，五页签抽屉）" ;;
    /admin/consult-orders/\{orderToken\}|/admin/ai-orders/\{orderToken\}) echo "⛔ 本版退役（8.4，抽屉吸收详情页）" ;;
    # ⚠️ 只有 GET /admin/vets/{id}/edit 退役；**POST /admin/vets/{id} 仍在服役**
    #    （AC6 要求它逐字不变）。两个一起匹配的话，清单里那条写端点会带着「已退役」备注，
    #    Story 11.1 做 diff 时读到的是一个自相矛盾的口径。
    /admin/vets/\{id\}/edit) echo "⛔ 本版退役（9.1a，资料并入抽屉资料页签）" ;;
    /admin/vets/online) echo "⛔ 本版退役（9.1a，在线态并入列表与抽屉）" ;;
    # 🔄 这两条**整页退役但路径保留**：9.1b 把它们改成抽屉页签的懒加载片段（htmx 才返片段，
    #    直达一律 302 回列表并开抽屉）。写成 ⛔ 会让 11.3 的人以为该删而去删 —— 删了抽屉页签就空了。
    /admin/vets/\{id\}/qualification) echo "🔄 整页退役、路径保留（9.1b，改为抽屉资质页签片段；同路径 POST 仍在服役）" ;;
    /admin/vets/\{id\}/ratings) echo "🔄 整页退役、路径保留（9.1b，改为抽屉评分页签片段）" ;;
    /admin/ratings) echo "⛔ 本版退役（9.1b，评分并入兽医列表筛选栏）" ;;
    /admin/reports) echo "⛔ 本版退役（2.4，统一复核工作台）" ;;
    /admin/content-schedules) echo "⛔ 本版退役（7.5，并入批量内容页签；注意改期/取消两个 POST 的重定向落点）" ;;
    /admin/tickets/detail) echo "⛔ 本版退役（2.5，被举报用户抽屉）" ;;
    /admin/anomalies/\{id\}) echo "⛔ 本版退役（2.6，问诊异常抽屉）" ;;
    /admin/refunds/\{refundToken\}) echo "⛔ 本版退役（2.8，退款三段流）" ;;
    /admin/support-tickets/\{ticketToken\}) echo "⛔ 本版退役（2.7，客服工单抽屉）" ;;
    /admin/shop/orders/\{token\}) echo "⛔ 本版退役（10.2，订单履约抽屉）" ;;
    *) echo "" ;;
  esac
}

# ---- 全局常量表（复审 P3）：`@PreAuthorize(OtherController.MANAGE_AUTH)` 这种**跨类**引用，
#      只查本文件的常量是展不开的，权限列里留下的是 Java 表达式而不是 SpEL 原文 ——
#      那一行的「原文逐字一致」就名不副实：只被跨类引用的常量，改了值不会产生任何 diff。
#      这里先把全树的 `static final String` 收成 `类名.常量名 -> 值`，交给 awk 兜底。 ----
gconst=$(mktemp)
trap 'rm -f "$tmp" "$gconst"' EXIT
while IFS= read -r f; do
  awk -v CLS="$(basename "$f" .java)" '
    function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t]+$/, "", s); return s }
    { t = trim($0)
      if (collecting) { acc = acc " " t; if (t ~ /;[ \t]*$/) { v = trim(acc); sub(/;[ \t]*$/, "", v); gsub(/"[ \t]*\+[ \t]*"/, "", v); gsub(/[ \t]+/, " ", v); print CLS "." nm "\t" v; collecting = 0 } next }
      if (match(t, /static final String [A-Za-z_0-9]+[ \t]*=/)) {
        nm = t; sub(/.*static final String /, "", nm); sub(/[ \t]*=.*/, "", nm)
        rest = t; sub(/.*static final String [A-Za-z_0-9]+[ \t]*=[ \t]*/, "", rest)
        if (rest ~ /;[ \t]*$/) { v = rest; sub(/;[ \t]*$/, "", v); gsub(/"[ \t]*\+[ \t]*"/, "", v); gsub(/[ \t]+/, " ", v); print CLS "." nm "\t" v }
        else { acc = rest; collecting = 1 }
      } }' "$f"
done < <(find "$SRC" -name '*.java' | sort) > "$gconst.raw"
# 常量可以引用**同类的另一个常量**（`static final String MANAGE_AUTH = AUTH;`）。
# 不把这层展开，跨类引用拿到的就是一个光秃秃的 `AUTH`（实测踩过）。这里迭代展开几轮到不动点。
awk -F'\t' '
  { V[$1] = $2 }
  END {
    for (r = 0; r < 5; r++) {
      for (k in V) {
        cls = k; sub(/\.[^.]*$/, "", cls)
        e = V[k]; out = ""
        while (match(e, /[A-Za-z_][A-Za-z_0-9]*/)) {
          tok = substr(e, RSTART, RLENGTH)
          full = cls "." tok
          out = out substr(e, 1, RSTART - 1) ((tok ~ /^[A-Z][A-Z_0-9]*$/ && (full in V)) ? V[full] : tok)
          e = substr(e, RSTART + RLENGTH)
        }
        out = out e
        gsub(/"[ \t]*\+[ \t]*"/, "", out)
        V[k] = out
      }
    }
    for (k in V) print k "\t" V[k]
  }' "$gconst.raw" > "$gconst"
rm -f "$gconst.raw"

# ---- 扫描：每个 Java 文件 → 行：METHOD<TAB>PATH<TAB>Class#method<TAB>preauth ----
# awk 状态机：
#   · 类级 @RequestMapping("...")（出现在 class 关键字之前）作为路径前缀；
#   · static final String X = ... ; 常量收集（跨行拼接，供 @PreAuthorize(X) 展开为原文）；
#   · 注解行按括号配平跨行拼接；@XMapping 与 @PreAuthorize 挂到随后的方法签名上；
#   · 方法级 @RequestMapping 无方法限定 → 记为 GET 并备注 (@RequestMapping)。
scan() {
  local file="$1"
  local cls stagonly
  cls=$(basename "$file" .java)
  # 🧪 类上带 @StagOnly（= @Profile("stag")）的 Controller：生产**不注册 bean、路由不存在**。
  #    它们仍然进清单（Story 11.1 做 diff 时不能凭空少几行），但必须**单列标记**，
  #    否则会被当成本版新增的生产写端点（V1.3.0 Story 8.5 / 决策 D-41 的模拟回调三钮就是这一类）。
  #    判据是注解本身，不是路径前缀 —— 按前缀认只对 kitchen-sink 那一种命名有效。
  stagonly=""
  if grep -qE '^[ \t]*@StagOnly[ \t]*$' "$file"; then stagonly="🧪 stag-only（@StagOnly，生产不注册）"; fi
  awk -v CLS="$cls" -v STAGONLY="$stagonly" -v GCONST="$gconst" '
    function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t]+$/, "", s); return s }
    function balanced(s,   i, c, d) { d = 0; for (i = 1; i <= length(s); i++) { c = substr(s, i, 1); if (c == "(") d++; else if (c == ")") d-- } return d <= 0 }
    function resolve(expr, depth,   out, i, c, tok, isid) {
      # 常量引用（含 A + B 拼接、常量引用常量）按标识符逐 token 展开为原文；字面量原样（mawk 无 \< 词界，手工切 token）
      if (depth > 5) return expr
      out = ""; tok = ""
      for (i = 1; i <= length(expr) + 1; i++) {
        c = (i <= length(expr)) ? substr(expr, i, 1) : ""
        isid = (c ~ /[A-Za-z_0-9]/)
        if (isid) { tok = tok c; continue }
        if (tok != "") { out = out ((tok in CONST) ? resolve(CONST[tok], depth + 1) : tok); tok = "" }
        out = out c
      }
      gsub(/"[ \t]*\+[ \t]*"/, "", out)
      return out
    }
    function emit_paths(ann,   body, s, n, i, seg, p) {
      body = ann
      sub(/^@[A-Za-z]+Mapping\(/, "", body); sub(/\)[ \t]*$/, "", body)
      gsub(/(produces|consumes|params|headers)[ \t]*=[ \t]*("[^"]*"|\{[^}]*\})/, "", body)
      # 🔴 路径也要展开常量（Story 11.1）：`@PostMapping(ROUTE + "/{id}/edit")` 这种写法
      #    （AdminPlaceController / AdminWarmReplyController）不展开就只抓到后半截 "/{id}/edit"，
      #    整条端点会因为不以 /admin 开头而被丢掉 —— 静默少 10 行，而自检只数 grep，未必报得出来。
      body = resolve(body, 0)
      n = 0
      while (match(body, /"[^"]*"/)) {
        seg = substr(body, RSTART + 1, RLENGTH - 2)
        body = substr(body, RSTART + RLENGTH)
        p = PREFIX seg
        if (p == "") p = "/"
        gsub(/\\\\/, "\\", p)   # Java 字面量里的 \\d+ 还原成正则本身的 \d+
        PATHS[++n] = p
      }
      if (n == 0) PATHS[++n] = (PREFIX == "" ? "/" : PREFIX)
      return n
    }
    function xclass(e,   out, key) {
      # 跨类常量：只认 `类名.大写常量名` 这一种形态，逐个整体替换。
      # ⚠️ 不能拿常量名当正则去 sub：`.` 会匹配任意字符，`AdminXController.MANAGE_AUTH`
      #    这种键会把别的文本吃掉（第一版就把整条权限表达式吃成了 `AUTH`）。
      out = ""
      while (match(e, /[A-Za-z_][A-Za-z_0-9]*\.[A-Z][A-Z_0-9]*/)) {
        key = substr(e, RSTART, RLENGTH)
        out = out substr(e, 1, RSTART - 1) ((key in GC) ? GC[key] : key)
        e = substr(e, RSTART + RLENGTH)
      }
      out = out e
      gsub(/"[ \t]*\+[ \t]*"/, "", out)
      return out
    }
    BEGIN {
      PREFIX = ""; inclass = 0; pend_map = ""; pend_auth = ""; acc = ""; cacc = ""; cname = ""; collecting = 0
      if (GCONST != "") { while ((getline ln < GCONST) > 0) { i = index(ln, "\t"); if (i > 1) { GC[substr(ln, 1, i - 1)] = substr(ln, i + 1) } } close(GCONST) }
    }
    {
      line = $0
      t = trim(line)
      # ---- 常量收集 ----
      if (collecting) { cacc = cacc " " t; if (t ~ /;[ \t]*$/) { v = trim(cacc); sub(/;[ \t]*$/, "", v); gsub(/"[ \t]*\+[ \t]*"/, "", v); gsub(/[ \t]+/, " ", v); CONST[cname] = v; cacc = ""; cname = ""; collecting = 0 } next }
      if (match(t, /static final String [A-Za-z_0-9]+[ \t]*=/)) {
        cname = t; sub(/.*static final String /, "", cname); sub(/[ \t]*=.*/, "", cname)
        rest = t; sub(/.*static final String [A-Za-z_0-9]+[ \t]*=[ \t]*/, "", rest)
        if (rest ~ /;[ \t]*$/) { v = rest; sub(/;[ \t]*$/, "", v); gsub(/"[ \t]*\+[ \t]*"/, "", v); gsub(/[ \t]+/, " ", v); CONST[cname] = v; cname = "" } else { cacc = rest; collecting = 1 }
        next
      }
      # ---- 注解跨行拼接 ----
      if (acc != "") { acc = acc " " t; if (balanced(acc)) { ann = acc; acc = "" } else next }
      else if (t ~ /^@(Get|Post|Put|Delete|Patch|Request)Mapping/ || t ~ /^@PreAuthorize/) { if (balanced(t)) ann = t; else { acc = t; next } }
      else ann = ""
      if (ann != "") {
        gsub(/[ \t]+/, " ", ann)
        if (ann ~ /^@RequestMapping/ && !inclass) { PREFIX = ann; sub(/^@RequestMapping\(/, "", PREFIX); sub(/\).*$/, "", PREFIX); gsub(/"/, "", PREFIX); sub(/^(value|path) ?= ?/, "", PREFIX); next }
        if (ann ~ /^@PreAuthorize/) { e = ann; sub(/^@PreAuthorize\(/, "", e); sub(/\)[ \t]*$/, "", e); gsub(/"[ \t]*\+[ \t]*"/, "", e); pend_auth = xclass(resolve(e, 0)); next }
        pend_map = ann; next
      }
      if (!inclass && t ~ /(^|[ \t])(class|record|interface)[ \t]/) inclass = 1
      # ---- 方法签名 ----
      if (pend_map != "" && t ~ /^(public|protected|private)[ \t]/ && t ~ /[A-Za-z_][A-Za-z_0-9]*[ \t]*\(/) {
        m = t; sub(/\(.*$/, "", m); sub(/[ \t]+$/, "", m); sub(/.*[ \t]/, "", m)
        method = "GET"; note = ""
        if (pend_map ~ /^@PostMapping/) method = "POST"
        else if (pend_map ~ /^@PutMapping/) method = "PUT"
        else if (pend_map ~ /^@DeleteMapping/) method = "DELETE"
        else if (pend_map ~ /^@PatchMapping/) method = "PATCH"   # 团队在 /api/v1 侧已在用，admin 侧迟早会有（复审 P1）
        else if (pend_map ~ /^@RequestMapping/) { method = "GET"; note = "(@RequestMapping 无方法限定)" }
        n = emit_paths(pend_map)
        auth = (pend_auth == "" ? "（无 @PreAuthorize，仅 /admin/** 链级 ROLE_ADMIN）" : pend_auth)
        # 🔴 竖线必须换成 HTML 实体，不能只加反斜杠（Story 11.1 复审 C2）：
        #    表格是给人看的，但 --baseline 回读时按 | 切列，`\|` 照样被当分隔符 ——
        #    权限表达式会在第一个 | 处被**截断**，后半截永远不参与比对。
        #    于是把 `hasRole('SUPER_ADMIN') || hasAuthority('content.takedown')` 放宽成
        #    `|| hasAuthority('ANYTHING_GOES')`，守门全绿（实测）。SpEL 的 || 与 or 等价，随时会有人写。
        gsub(/\|/, "\&#124;", auth)
        if (STAGONLY != "") note = STAGONLY (note == "" ? "" : " " note)
        for (i = 1; i <= n; i++) printf "%s\t%s\t%s#%s\t%s\t%s\n", method, PATHS[i], CLS, m, auth, note
        pend_map = ""; pend_auth = ""
        delete PATHS
      }
    }
  ' "$file"
}

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT

# ---- 解析 + **逐文件**漏抓自检 ----
# 🔴 自检的判据是「解析出的行数不少于该文件里的映射注解条数」，逐文件比（Story 11.1 复审 P2 / C1）。
#    为什么不比全局总数：解析行数天然会**多于**注解条数（多路径注解 `@XMapping({"/a","/b"})` 一条出 n 行），
#    全局一个总数一比，「这里多两行、那里漏两行」互相抵消，漏抓就看不见了。
#    逐文件用 `<` 判定，漏抓一定为真、且直接指出是哪个文件；多路径带来的富余不会误报。
#    ⚠️ 方法级 `@RequestMapping`（无方法限定）解析器记为 GET，所以也要算进 need。
parsed_rows=0
annot_rows=0
while IFS= read -r f; do
  before=$(wc -l < "$tmp")
  scan "$f" >> "$tmp"
  produced=$(( $(wc -l < "$tmp") - before ))
  parsed_rows=$((parsed_rows + produced))
  case "$f" in */AdminKitchenSinkController.java) continue ;; esac   # stag-only 演示页，不进底账也不自检
  need=$(grep -cE '@(Get|Post|Put|Delete|Patch)Mapping\(' "$f")
  need=$((need + $(grep -cE '^[ \t]+@RequestMapping\(' "$f") ))
  annot_rows=$((annot_rows + need))
  if [[ "$produced" -lt "$need" ]]; then
    echo "::error file=$f::解析出 $produced 行 < 映射注解 $need 条 —— 有端点被静默丢掉，先修解析器再谈 diff"
    fail=1
  fi
done < <(find "$SRC" -name '*.java' | sort)

# ---- /admin 过滤 ----
# /admin/_* 为 stag-only 演示路由（Story 2.3b kitchen-sink，@StagOnly 生产不注册），不进底账。
# 全树扫下来 /api/v1 等对客路由也在 $tmp 里，这里一并滤掉：**判据是路径，不是包名**。
awk -F'\t' '$2 ~ /^\/admin(\/|$)/ && $2 !~ /^\/admin\/_/' "$tmp" > "$tmp.f" && mv "$tmp.f" "$tmp"
write_rows=$(awk -F'\t' '$1 != "GET"' "$tmp" | sort -t$'\t' -k2,2 -k1,1 -k3,3)
write_count=$(printf '%s\n' "$write_rows" | grep -c . || true)

# ---- 表 2：GET 页面路由 ----
get_rows=$(awk -F'\t' '$1 == "GET"' "$tmp" | sort -t$'\t' -k2,2 -k3,3)
get_count=$(printf '%s\n' "$get_rows" | grep -c . || true)
# 页面路由 = 去掉带路径参数 / export / .csv|.xlsx / drawer / /detail 的 GET
page_count=$(printf '%s\n' "$get_rows" | awk -F'\t' '$2 !~ /\{|export|\.csv|\.xlsx|drawer|\/detail|\/fragment|\/preview|\/search|\/lookup|\/suggest|\/json|\/api\//' | grep -c . || true)

mkdir -p "$OUT_DIR"
{
  echo "# 后台写操作清单（D-27 验收底账）"
  echo
  echo "- baseline_commit: \`$baseline\`"
  # 🔴 脚本自身的内容哈希（复审 P5）：清单是审计证据，「在哪个 commit 上跑的」不够 ——
  #    脚本改了没提交时，拿那个 commit 原样 checkout 是复现不出这份清单的。记下脚本版本才闭环。
  echo "- script_version: \`$script_version\`（\`git hash-object scripts/ci/list-admin-write-ops.sh\`）"
  echo "- 生成命令: \`bash scripts/ci/list-admin-write-ops.sh\`（Story 2.1；Story 11.1 对重构后代码再跑一次做 diff）"
  echo "- 计数: 写端点 **$write_count** · GET 映射 **$get_count** · 页面路由（去参数 / 导出 / 抽屉 / 详情）≈ **$page_count**"
  echo "- 自检: 全树解析 $parsed_rows 行 ≥ 映射注解 $annot_rows 条（逐文件比对，无漏抓）；本表只收路径以 \`/admin\` 开头的那些。"
  echo "- 页面组按「路径前缀 → 泳道」表（脚本内置，UI 稿 8 泳道）；\`AdminPageCatalog\`（Story 1.5）落地后 Story 11.1 可改读它。"
  echo "- 🧪 标记 = \`@StagOnly\` 端点：**生产不注册 bean、路由不存在**（不是 403）。做 diff 时按 stag-only 单列，不计入生产写端点。"
  echo
  echo "## 表 1 · 写端点（@Post/Put/DeleteMapping）"
  echo
  echo "| 方法 | 路径 | Controller#方法 | @PreAuthorize（原文） | 页面组 | 备注 |"
  echo "|---|---|---|---|---|---|"
  printf '%s\n' "$write_rows" | while IFS=$'\t' read -r m p cm auth note; do
    [[ -z "$m" ]] && continue
    g=$(group_of "$p")
    r=$(retired_note "$m" "$p")
    echo "| $m | \`$p\` | \`$cm\` | \`$auth\` | $g | ${note}${r} |"
  done
  echo
  echo "## 表 2 · GET 页面路由"
  echo
  echo "> 页面分母以本表 GET 路由为准，UI 稿 49 帧是形态比对口径（D-8）。⛔ 标记 = 本版退役（PRD AB-19A），Story 11.3 确认清理。"
  echo
  echo "| 方法 | 路径 | Controller#方法 | @PreAuthorize（原文） | 页面组 | 备注 |"
  echo "|---|---|---|---|---|---|"
  printf '%s\n' "$get_rows" | while IFS=$'\t' read -r m p cm auth note; do
    [[ -z "$m" ]] && continue
    g=$(group_of "$p")
    r=$(retired_note "$m" "$p")
    echo "| $m | \`$p\` | \`$cm\` | \`$auth\` | $g | ${note}${r} |"
  done
} > "$OUT"

echo "list-admin-write-ops: 写端点 $write_count · GET 映射 $get_count · 页面路由 ≈ $page_count（全树解析 $parsed_rows 行 / 映射注解 $annot_rows 条）→ $OUT"
ungrouped=$(grep -c '⚠️ 未归组' "$OUT" || true)
if [[ "$ungrouped" -gt 0 ]]; then
  echo "::warning::有 $ungrouped 行未归组，请在脚本 group_of 补前缀映射后人工归类"
fi

# ---- 自检 ③ 写端点必须有 @PreAuthorize（复审 P4：新增端点没有基线可比，漂移检测看不到它们） ----
naked=$(printf '%s\n' "$write_rows" | awk -F'\t' '$4 ~ /无 @PreAuthorize/ { print "  " $1 " " $2 " (" $3 ")" }')
if [[ -n "$naked" ]]; then
  echo "::error::下列 /admin 写端点没有 @PreAuthorize，只有链级 ROLE_ADMIN 兜底："
  printf '%s\n' "$naked"
  fail=1
fi

# ===================== Story 11.1 · --baseline diff 模式 =====================
# 比对键 = **方法 + 路径 + @PreAuthorize 原文**（AC2）。
#   · Controller#方法**不进键**：Controller 搬家是本版预期变化（Dev Notes 三类误判之一）；
#   · 参数级变化（warn +reason 等）清单本来就不列，天然不产生差异，只在核对报告里人工登记；
#   · 白名单每行 `<+|-> <METHOD> <PATH>  # 依据`，`+` = 允许新增、`-` = 允许删除。
if [[ -n "$BASELINE" ]]; then
  if [[ ! -f "$BASELINE" ]]; then
    echo "::error::基线清单不存在：$BASELINE"
    exit 1
  fi
  bt=$(mktemp); at=$(mktemp); al=$(mktemp)
  trap 'rm -f "$tmp" "$bt" "$at" "$al"' EXIT

  # 从 markdown 表格行提取 `方法|路径|权限`（列 1/2/4；去掉反引号与首尾空格）。
  md_rows() {
    # PATCH 也要认（复审 P1）：方法白名单漏了它的话，整行被丢掉 = 又一处静默失守。
    awk -F'|' 'NF >= 7 && $2 ~ /^ *(GET|POST|PUT|PATCH|DELETE) *$/ {
      m=$2; p=$3; a=$5
      gsub(/`/, "", m); gsub(/`/, "", p); gsub(/`/, "", a)
      gsub(/^ +| +$/, "", m); gsub(/^ +| +$/, "", p); gsub(/^ +| +$/, "", a)
      gsub(/&#124;/, "|", a)   # 还原生成时为保护 markdown 表格转成实体的竖线
      print m "\t" p "\t" a
    }' "$1" | sort -u
  }
  md_rows "$BASELINE" > "$bt"
  md_rows "$OUT" > "$at"

  if [[ -f "$ALLOWLIST" ]]; then
    sed -e 's/#.*$//' -e 's/[ \t]*$//' "$ALLOWLIST" | grep -E '^[+-] ' > "$al" || true
  else
    : > "$al"
    echo "::warning::白名单文件不存在：$ALLOWLIST（所有差异都会被判为未解释）"
  fi

  # 允许列表命中判定：sign(+/-) + 方法 + 路径
  # ⚠️ 必须用 -e：删除项的模式以 `-` 开头，直接当位置参数会被 grep 当成选项（invalid option -- ' '）。
  allowed() { grep -qxF -e "$1 $2 $3" "$al"; }

  unexplained=0
  echo
  echo "—— 与基线 $BASELINE 的 diff（键 = 方法 + 路径 + @PreAuthorize）——"
  # 新增（after 有、基线无该「方法+路径」）
  while IFS=$'\t' read -r m p a; do
    [[ -z "$m" ]] && continue
    if ! awk -F'\t' -v M="$m" -v P="$p" '$1 == M && $2 == P { found = 1 } END { exit !found }' "$bt"; then
      if allowed "+" "$m" "$p"; then
        echo "  ✅ 新增（白名单）：$m $p"
      else
        echo "::error::未解释的新增端点：$m $p（$a）"
        unexplained=$((unexplained + 1))
      fi
    fi
  done < "$at"
  # 删除（基线有、after 无该「方法+路径」）
  while IFS=$'\t' read -r m p a; do
    [[ -z "$m" ]] && continue
    if ! awk -F'\t' -v M="$m" -v P="$p" '$1 == M && $2 == P { found = 1 } END { exit !found }' "$at"; then
      if allowed "-" "$m" "$p"; then
        echo "  ✅ 删除（白名单）：$m $p"
      else
        echo "::error::未解释的删除端点：$m $p（$a）"
        unexplained=$((unexplained + 1))
      fi
    fi
  done < "$bt"
  # 权限漂移（方法+路径两边都在，@PreAuthorize 原文不一致）——白名单不豁免，AC2 要求逐字一致
  while IFS=$'\t' read -r m p a; do
    [[ -z "$m" ]] && continue
    b=$(awk -F'\t' -v M="$m" -v P="$p" '$1 == M && $2 == P { print $3; exit }' "$bt")
    [[ -z "$b" ]] && continue
    if [[ "$b" != "$a" ]]; then
      echo "::error::@PreAuthorize 漂移：$m $p"
      echo "        基线: $b"
      echo "        现在: $a"
      unexplained=$((unexplained + 1))
    fi
  done < "$at"

  echo "—— 基线 $(grep -c . "$bt") 行 · 现在 $(grep -c . "$at") 行 · 未解释差异 $unexplained ——"
  if [[ "$unexplained" -gt 0 ]]; then
    echo "::error::写操作清单与基线存在 $unexplained 处未解释差异（AB-19A：重构不丢一项）"
    fail=1
  else
    echo "write-ops-guard: OK（与基线零未解释差异）"
  fi
fi
exit $fail
