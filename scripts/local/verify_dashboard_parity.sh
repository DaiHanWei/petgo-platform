#!/usr/bin/env bash
# =====================================================================================================
# 看板验收对照（V1.3.0 Story 3.5 AC3 / D-26）——对 stag 做「三方对照」：
#   页面 JSON 值（GET /admin/charts?range=30）  =  ops_daily_metrics 长表值  =  口径 SQL 直跑值
# 3 日 × 18 项（含 REAL 口径 9 项 → 27 个 (key, scope)），全部相等才通过；不等项标红，退出码 1。
#
# 🔒 只读、不改库：
#   - 数据库一律用只读账号 ops_readonly（memory：运营只读库账号），脚本内没有任何 INSERT/UPDATE/DELETE，psql 加 --set=ON_ERROR_STOP=1
#     且连接级 PGOPTIONS='-c default_transaction_read_only=on'（连接建立即生效，每个事务都是只读；`-c` 里多语句同事务，
#     事务内再 SET default_transaction_read_only 对当前事务无效，所以不用那种写法）；
#   - HTTP 只有 GET；物化请另走 stag 页面上的「立即物化」按钮（Story 3.3），本脚本不触发。
#   - 只能指向 staging（petgo_stag / api-stag）。不要把 STAG_PG_URL 指到生产库。
#
# 用法：
#   scripts/local/verify_dashboard_parity.sh <date1> <date2> <date3>        # 日期 = WIB 自然日，形如 2026-09-01，须在近 30 天内（页面范围）
# 环境变量：
#   STAG_PG_URL    psql 连接串，默认 postgresql://ops_readonly@127.0.0.1:5433/petgo_stag （密码走 PGPASSWORD 或 ~/.pgpass）
#   ADMIN_BASE     后台根地址，默认 https://api-stag.tailtopia.id
#   ADMIN_COOKIE   已登录后台会话的 Cookie 头原文（浏览器 devtools 复制，如 "JSESSIONID=xxxx"）；登录账号须有 payment.view 或为超管，
#                  否则页面不下发付费卡（Story 3.5 AC1），付费四项会标 n/a
#   BACKEND_DIR    petgo-backend 目录，默认脚本所在仓库的 petgo-backend
# 前置：psql、curl、python3、JDK 21（导出口径 SQL 用 ./mvnw 跑一条 L0 测试 MetricSqlDumpTest，不连库）
# =====================================================================================================
set -euo pipefail

if [ $# -ne 3 ]; then
  echo "用法: $0 <date1> <date2> <date3>   (YYYY-MM-DD，WIB 自然日)" >&2
  exit 2
fi
for d in "$@"; do
  [[ "$d" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || { echo "日期格式错误: $d" >&2; exit 2; }
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND_DIR="${BACKEND_DIR:-$ROOT/petgo-backend}"
STAG_PG_URL="${STAG_PG_URL:-postgresql://ops_readonly@127.0.0.1:5433/petgo_stag}"
ADMIN_BASE="${ADMIN_BASE:-https://api-stag.tailtopia.id}"
ADMIN_COOKIE="${ADMIN_COOKIE:-}"

case "$STAG_PG_URL" in
  *petgo_stag*) ;;
  *) echo "⛔ STAG_PG_URL 必须指向 petgo_stag（staging 只读库）: $STAG_PG_URL" >&2; exit 2 ;;
esac
case "$ADMIN_BASE" in
  *api-stag*|*localhost*|*127.0.0.1*) ;;
  *) echo "⛔ ADMIN_BASE 必须是 staging 后台: $ADMIN_BASE" >&2; exit 2 ;;
esac
[ -n "$ADMIN_COOKIE" ] || { echo "⛔ 需要 ADMIN_COOKIE（已登录后台会话的 Cookie 头）" >&2; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---- ① 口径 SQL：从 Java 指标类原样导出（与线上物化同一份，D-26） ------------------------------------
echo "▶ 导出口径 SQL（MetricSqlDumpTest，L0 不连库）…"
( cd "$BACKEND_DIR" && LC_ALL=C.UTF-8 ./mvnw -B -q -o test -Dtest=MetricSqlDumpTest -Dsurefire.failIfNoSpecifiedTests=false >/dev/null )
SQL_DIR="$BACKEND_DIR/target/metric-sql"
[ "$(ls "$SQL_DIR"/*.sql 2>/dev/null | wc -l)" -eq 27 ] || { echo "⛔ 口径 SQL 导出不完整: $SQL_DIR" >&2; exit 1; }

# ---- ② 页面 JSON：近 30 天图表 fragment（htmx 头），解析内嵌 <script type="application/json" data-chart> --------
echo "▶ 拉取页面 JSON（$ADMIN_BASE/admin/charts?range=30）…"
curl -sS --fail -H "HX-Request: true" -H "Cookie: $ADMIN_COOKIE" "$ADMIN_BASE/admin/charts?range=30" -o "$WORK/charts.html"
grep -q 'data-chart=' "$WORK/charts.html" || { echo "⛔ 响应里没有图表数据（会话失效？）" >&2; exit 1; }

# ---- ③ 长表 + 口径 SQL 直跑（只读事务）-------------------------------------------------------------------
# 连接级只读：所有事务（含 -c 的隐式事务）都 READ ONLY；任何写语句直接报错
export PGOPTIONS="${PGOPTIONS:-} -c default_transaction_read_only=on"
PSQL=(psql "$STAG_PG_URL" -X -q -A -t -F $'\t' --set=ON_ERROR_STOP=1)
for d in "$@"; do
  echo "▶ $d：读 ops_daily_metrics …"
  "${PSQL[@]}" -c "SELECT metric_key, scope, value FROM ops_daily_metrics WHERE report_date = DATE '$d' ORDER BY 1, 2;" \
    > "$WORK/table_$d.tsv"
  echo "▶ $d：直跑 27 份口径 SQL …"
  : > "$WORK/sql_$d.tsv"
  for f in "$SQL_DIR"/*.sql; do
    name="$(basename "$f" .sql)"; key="${name%%__*}"; scope="${name##*__}"
    # 命名参数 :d → 日期字面量（避开 ::cast）
    sql="$(perl -pe "s/(?<!:):d\b/DATE '$d'/g" "$f")"
    v="$("${PSQL[@]}" -c "SET TIME ZONE 'UTC'; $sql" | head -1)"
    printf '%s\t%s\t%s\n' "$key" "$scope" "${v:-NULL}" >> "$WORK/sql_$d.tsv"
  done
done

# ---- ④ 三列对照 ---------------------------------------------------------------------------------------------
python3 - "$WORK" "$@" <<'PY'
import html, json, re, sys
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
work, dates = sys.argv[1], sys.argv[2:]
RED, GREEN, END = "\033[31m", "\033[32m", "\033[0m"

def norm(v):
    if v is None or v == "" or v == "NULL":
        return None
    try:
        # 长表 NUMERIC(18,4) 由 PG 四舍五入（half away from zero）落库；平均类直跑值 16 位小数要按同一规则截，不能用 Python 默认的银行家舍入
        return Decimal(str(v)).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)
    except InvalidOperation:
        return v

# 页面 JSON：{(key, scope): {date: value}}
page = {}
src = open(f"{work}/charts.html", encoding="utf-8").read()
for m in re.finditer(r'<script type="application/json" data-chart="([^"]+)">(.*?)</script>', src, re.S):
    data = json.loads(html.unescape(m.group(2)))
    labels = data["labels"]
    for s in data["series"]:
        page[(s["key"], s["scope"])] = dict(zip(labels, s["values"]))

def tsv(path):
    out = {}
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line:
            continue
        k, sc, v = line.split("\t")
        out[(k, sc)] = v
    return out

bad = 0
for d in dates:
    table, direct = tsv(f"{work}/table_{d}.tsv"), tsv(f"{work}/sql_{d}.tsv")
    print(f"\n=== {d} ===")
    print(f"{'metric':32} {'scope':5} {'page':>14} {'ops_daily':>14} {'sql':>14}  ok")
    for (k, sc) in sorted(direct):
        p = norm(page.get((k, sc), {}).get(d)) if (k, sc) in page else "n/a"
        t = norm(table.get((k, sc)))
        q = norm(direct.get((k, sc)))
        # 平均类空样本：SQL 直跑 NULL，长表存 0（3-3「无样本」约定），页面照传 0
        if q is None and t == Decimal("0.0000"):
            q = Decimal("0.0000")
        vals = [x for x in (p, t, q) if x != "n/a"]
        ok = len(set(map(str, vals))) == 1 and t is not None
        bad += 0 if ok else 1
        color = GREEN if ok else RED
        print(f"{color}{k:32} {sc:5} {str(p):>14} {str(t):>14} {str(q):>14}  {'✓' if ok else '✗'}{END}")
print()
if bad:
    print(f"{RED}不一致 {bad} 项{END}")
    sys.exit(1)
print(f"{GREEN}全部一致（{len(dates)} 日 × 27 项）{END}")
PY
