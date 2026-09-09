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
#
# 幂等：表按 路径+方法 排序；除文件名日期与 baseline commit 外无易变内容，同一提交重复运行逐字节一致。
# 自检：表 1 行数必须等于 grep -rhoE "@(Post|Put|Delete)Mapping\(" 的计数，不等则 ::error:: + exit 1。
# 页面组映射：先用「路径前缀 → 泳道」表过渡（与 UI 稿 8 泳道一一对应）；AdminPageCatalog（Story 1.5）落地后，
#   Story 11.1 可改为从 Java 端导出映射。
# 纯 bash + grep/sed/awk/sort，云端可直接跑；不依赖 Docker / mvn。避免 sed -i ''（macOS 差异）。
set -uo pipefail
export LC_ALL=C   # 排序与 awk 行为跨机器一致（幂等）

SRC="petgo-backend/src/main/java/com/tailtopia/admin"
OUT_DIR="_bmad-output/implementation-artifacts/v1.3.0"
OUT_DEFAULT="$OUT_DIR/后台写操作清单-$(date +%Y%m%d).md"
OUT="$OUT_DEFAULT"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    *) echo "::error::未知参数 $1"; exit 2 ;;
  esac
done

if [[ ! -d "$SRC" ]]; then
  echo "::error::找不到 $SRC（请在仓库根目录运行）"
  exit 1
fi

fail=0
baseline=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)

# ---- 路径前缀 → 泳道（UI 稿 8 泳道；/admin/shop/shipping 归商城，D1） ----
group_of() {
  local p="$1"
  case "$p" in
    /admin|/admin/dashboard*) echo "概览" ;;
    /admin/manual-review*|/admin/avatar-review*|/admin/settings/manual-review*|/admin/reports*|/admin/tickets*|/admin/anomalies*|/admin/support-tickets*|/admin/refunds*|/admin/warm-replies*) echo "待办中心" ;;
    /admin/seed-post*|/admin/seed-batch*|/admin/seed-assist*|/admin/content*|/admin/comments*|/admin/throttles*|/admin/places*) echo "内容" ;;
    /admin/users*|/admin/user-tags*|/admin/virtual-accounts*|/admin/publish-identities*) echo "用户" ;;
    /admin/consult-orders*|/admin/ai-orders*|/admin/payments*|/admin/settlements*|/admin/red-overage*) echo "订单与资金" ;;
    /admin/shop/*) echo "商城" ;;
    /admin/vets*|/admin/failed-requests*|/admin/ratings*|/admin/consult-sessions*) echo "兽医与问诊" ;;
    /admin/config*|/admin/algo-params*|/admin/audit-logs*|/admin/accounts*|/admin/roles*) echo "配置与安全" ;;
    /admin/login*|/admin/oauth*|/admin/logout*|/admin/denied*|/admin/lang*) echo "登录与壳" ;;
    *) echo "⚠️ 未归组" ;;
  esac
}

# ---- 本版退役路由（PRD AB-19A；备注列写 ⛔ 本版退役（Story x.y）） ----
retired_note() {
  case "$1" in
    /admin/content/\{postId\}) echo "⛔ 本版退役（7.1，详情抽屉化）" ;;
    /admin/users/\{userId\}) echo "⛔ 本版退役（8.1，五页签抽屉）" ;;
    /admin/consult-orders/\{orderToken\}|/admin/ai-orders/\{orderToken\}) echo "⛔ 本版退役（8.4，抽屉吸收详情页）" ;;
    /admin/vets/\{id\}|/admin/vets/\{id\}/edit) echo "⛔ 本版退役（9.1a，资料/账号页签）" ;;
    /admin/vets/\{id\}/qualification) echo "⛔ 本版退役（9.1b，资质页签）" ;;
    /admin/vets/online) echo "⛔ 本版退役（9.1a，并入兽医列表）" ;;
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

# ---- 扫描：每个 Java 文件 → 行：METHOD<TAB>PATH<TAB>Class#method<TAB>preauth ----
# awk 状态机：
#   · 类级 @RequestMapping("...")（出现在 class 关键字之前）作为路径前缀；
#   · static final String X = ... ; 常量收集（跨行拼接，供 @PreAuthorize(X) 展开为原文）；
#   · 注解行按括号配平跨行拼接；@XMapping 与 @PreAuthorize 挂到随后的方法签名上；
#   · 方法级 @RequestMapping 无方法限定 → 记为 GET 并备注 (@RequestMapping)。
scan() {
  local file="$1"
  local cls
  cls=$(basename "$file" .java)
  awk -v CLS="$cls" '
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
      n = 0
      while (match(body, /"[^"]*"/)) {
        seg = substr(body, RSTART + 1, RLENGTH - 2)
        body = substr(body, RSTART + RLENGTH)
        p = PREFIX seg
        if (p == "") p = "/"
        PATHS[++n] = p
      }
      if (n == 0) PATHS[++n] = (PREFIX == "" ? "/" : PREFIX)
      return n
    }
    BEGIN { PREFIX = ""; inclass = 0; pend_map = ""; pend_auth = ""; acc = ""; cacc = ""; cname = ""; collecting = 0 }
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
      else if (t ~ /^@(Get|Post|Put|Delete|Request)Mapping/ || t ~ /^@PreAuthorize/) { if (balanced(t)) ann = t; else { acc = t; next } }
      else ann = ""
      if (ann != "") {
        gsub(/[ \t]+/, " ", ann)
        if (ann ~ /^@RequestMapping/ && !inclass) { PREFIX = ann; sub(/^@RequestMapping\(/, "", PREFIX); sub(/\).*$/, "", PREFIX); gsub(/"/, "", PREFIX); sub(/^(value|path) ?= ?/, "", PREFIX); next }
        if (ann ~ /^@PreAuthorize/) { e = ann; sub(/^@PreAuthorize\(/, "", e); sub(/\)[ \t]*$/, "", e); gsub(/"[ \t]*\+[ \t]*"/, "", e); pend_auth = resolve(e, 0); next }
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
        else if (pend_map ~ /^@RequestMapping/) { method = "GET"; note = "(@RequestMapping 无方法限定)" }
        n = emit_paths(pend_map)
        auth = (pend_auth == "" ? "（无 @PreAuthorize，仅 /admin/** 链级 ROLE_ADMIN）" : pend_auth)
        gsub(/\|/, "\\|", auth)
        for (i = 1; i <= n; i++) printf "%s\t%s\t%s#%s\t%s\t%s\n", method, PATHS[i], CLS, m, auth, note
        pend_map = ""; pend_auth = ""
        delete PATHS
      }
    }
  ' "$file"
}

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
while IFS= read -r f; do
  scan "$f" >> "$tmp"
done < <(find "$SRC" -name '*.java' | sort)

# ---- 表 1：写端点 ----
write_rows=$(awk -F'\t' '$1 != "GET"' "$tmp" | sort -t$'\t' -k2,2 -k1,1 -k3,3)
write_count=$(printf '%s\n' "$write_rows" | grep -c . || true)
expected=$(grep -rhoE '@(Post|Put|Delete)Mapping\(' "$SRC" | wc -l | tr -d ' ')

# ---- 表 2：GET 页面路由 ----
get_rows=$(awk -F'\t' '$1 == "GET"' "$tmp" | sort -t$'\t' -k2,2 -k3,3)
get_count=$(printf '%s\n' "$get_rows" | grep -c . || true)
get_expected=$(grep -rhoE '@GetMapping\(' "$SRC" | wc -l | tr -d ' ')
# 页面路由 = 去掉带路径参数 / export / .csv|.xlsx / drawer / /detail 的 GET
page_count=$(printf '%s\n' "$get_rows" | awk -F'\t' '$2 !~ /\{|export|\.csv|\.xlsx|drawer|\/detail|\/fragment|\/preview|\/search|\/lookup|\/suggest|\/json|\/api\//' | grep -c . || true)

mkdir -p "$OUT_DIR"
{
  echo "# 后台写操作清单（D-27 验收底账）"
  echo
  echo "- baseline_commit: \`$baseline\`"
  echo "- 生成命令: \`bash scripts/ci/list-admin-write-ops.sh\`（Story 2.1；Story 11.1 对重构后代码再跑一次做 diff）"
  echo "- 计数: 写端点 **$write_count**（grep 自检 $expected）· GET 映射 **$get_count**（grep $get_expected）· 页面路由（去参数 / 导出 / 抽屉 / 详情）≈ **$page_count**"
  echo "- 页面组按「路径前缀 → 泳道」表（脚本内置，UI 稿 8 泳道）；\`AdminPageCatalog\`（Story 1.5）落地后 Story 11.1 可改读它。"
  echo
  echo "## 表 1 · 写端点（@Post/Put/DeleteMapping）"
  echo
  echo "| 方法 | 路径 | Controller#方法 | @PreAuthorize（原文） | 页面组 | 备注 |"
  echo "|---|---|---|---|---|---|"
  printf '%s\n' "$write_rows" | while IFS=$'\t' read -r m p cm auth note; do
    [[ -z "$m" ]] && continue
    g=$(group_of "$p")
    r=$(retired_note "$p")
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
    r=$(retired_note "$p")
    echo "| $m | \`$p\` | \`$cm\` | \`$auth\` | $g | ${note}${r} |"
  done
} > "$OUT"

echo "list-admin-write-ops: 写端点 $write_count（grep $expected）· GET 映射 $get_count（grep $get_expected）· 页面路由 ≈ $page_count → $OUT"
ungrouped=$(grep -c '⚠️ 未归组' "$OUT" || true)
if [[ "$ungrouped" -gt 0 ]]; then
  echo "::warning::有 $ungrouped 行未归组，请在脚本 group_of 补前缀映射后人工归类"
fi
if [[ "$write_count" -ne "$expected" ]]; then
  echo "::error::写端点表行数 $write_count ≠ 源码 @Post/Put/DeleteMapping 计数 $expected（解析漏抓或多路径注解，需修脚本）"
  fail=1
fi
exit $fail
