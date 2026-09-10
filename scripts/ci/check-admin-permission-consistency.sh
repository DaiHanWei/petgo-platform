#!/usr/bin/env bash
# 权限矩阵一致性守门（V1.3.0 Story 11.4 · AC2 / AC3，PRD FR-21A）：
#
#   ① 门控码 ⊆ 权限册：所有 /admin 端点 @PreAuthorize 里出现的每个 hasAuthority('x')，
#      x 必须是 AdminPermissions 的常量值 —— 手写一个拼错的码，Spring 不会报错，
#      只会让那个端点**对所有人 403**（或者更糟：码名恰好没人有，等于永久锁死）。
#   ② 目录 ↔ 路由双向：AdminPageCatalog 里每条 route 必须是活着的 GET 路由；
#      每个**页面级** GET 路由必须在目录里有一条 —— 目录是导航与权限矩阵的唯一数据源，
#      少一条 = 页面进不了菜单也进不了矩阵，多一条 = 菜单里一个点不开的死项。
#   ③ 已撤销的码（content.stats_view / content.stats_export，整页 2026-08-28 撤销）不许再出现在**门控**里。
#      ⚠️ 「不许出现在矩阵里」这一半由 L0 的 AdminPermissionMatrixStaticTest 判
#      （码在不在册要问 AdminPermissions，不能 grep 目录源码 —— 目录里权限码字面量数量为 0，
#      全部走 `import static AdminPermissions.*` 的常量，grep 它是个**死分支**，复审 C9）。
#
# 用法：bash scripts/ci/check-admin-permission-consistency.sh
# 纯 bash + awk/grep，云端可跑；路由清单复用 Story 11.1 的生成器（唯一事实源，不另写一套解析）。
#
# ⚠️ ② 是**文本层**核对：两边都是字符串字面量，够用但不权威。权威版是 L1 的
#    AdminPageCatalogCoverageTest（用 RequestMappingHandlerMapping 枚举真实注册的路由）。
#    这里做文本层是为了在**没有 Docker 的环境**也能挡住绝大多数漏配。
set -uo pipefail
export LC_ALL=C.UTF-8

SRC="petgo-backend/src/main/java/com/tailtopia"
PERMS="$SRC/admin/account/domain/AdminPermissions.java"
CATALOG="$SRC/admin/shared/AdminPageCatalog.java"
EXCEPTIONS="scripts/ci/admin-page-catalog-exceptions.txt"
if [[ ! -f "$EXCEPTIONS" ]]; then
  echo "::error::找不到例外清单 $EXCEPTIONS"
  exit 1
fi
if [[ ! -f "$PERMS" || ! -f "$CATALOG" ]]; then
  echo "::error::找不到 AdminPermissions / AdminPageCatalog（请在仓库根目录运行）"
  exit 1
fi

fail=0
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# ---- 权限册 ----
grep -oE 'String [A-Z_0-9]+ *= *"[a-z_.]+"' "$PERMS" | sed 's/.*"\(.*\)"/\1/' | sort -u > "$tmp/perms.txt"
perm_count=$(grep -c . "$tmp/perms.txt")

# ---- 路由清单（复用 11.1 生成器）----
# 🔴 **必须判退出码，不能只判「文件非空」**（复审 C2）：
#    生成器自己会喊「某文件解析出 0 行 < 映射注解 1 条 —— 有端点被静默丢掉」，
#    但那句被 `> ledger.log` 吞了，而清单照样生成、照样非空。
#    于是一个包级私有的处理器方法（生成器的 awk 只认 public/protected/private 开头的签名）
#    就能同时废掉下面两条检查：拼错的码不报、没进目录的页面不报，脚本还是 OK exit 0（实测）。
#    清单的完整性是本脚本**全部结论的前提**。
if ! bash scripts/ci/list-admin-write-ops.sh --out "$tmp/ledger.md" > "$tmp/ledger.log" 2>&1; then
  echo "::error::清单生成失败或自检不通过 —— 本脚本的结论建立在清单完整的前提上，先修它："
  cat "$tmp/ledger.log"
  exit 1
fi
if [[ ! -s "$tmp/ledger.md" ]]; then
  echo "::error::清单为空"
  cat "$tmp/ledger.log"
  exit 1
fi

# ---- ① 门控码 ⊆ 权限册 ----
# 🔴 `hasAnyAuthority('a','b')` 与 `hasAuthority('a') or hasAuthority('b')` 在 SpEL 里完全等价，
#    随时会有人写；而 `hasAnyAuthority` 里**不含子串 `hasAuthority`**（hasAny**A**uthority），
#    只匹配后者的话，前者里的码一个都提不出来 —— 拼错了也不报（复审 C1 实测）。
grep -oE "has(Any)?Authority\([^)]*\)" "$tmp/ledger.md" \
  | grep -oE "'[^']+'" | tr -d "'" | sort -u > "$tmp/used.txt"
unknown=$(comm -23 "$tmp/used.txt" "$tmp/perms.txt")
if [[ -n "$unknown" ]]; then
  echo "::error::下列 @PreAuthorize 码不在 AdminPermissions 里（拼错的码 = 该端点对所有人 403）："
  printf '  %s\n' $unknown
  fail=1
fi

# ---- ③ 已撤销的码不许再出现 ----
for revoked in content.stats_view content.stats_export; do
  if grep -qx "$revoked" "$tmp/used.txt"; then
    echo "::error::已撤销的权限码 $revoked 又出现在门控里（整页 2026-08-28 撤销）"
    fail=1
  fi
  # ⚠️ 不 grep 目录源码：那里权限码全是 `import static` 的常量，字面量数量为 0，grep 恒不命中。
  #    「码是否还在册」由 L0 的 AdminPermissionMatrixStaticTest 用 AdminPermissions.isValid 判。
done

# ---- ② 目录 ↔ 路由 ----
# 目录里的 route（第三个字符串参数，形如 "/admin/xxx"）
grep -oE '"/admin[a-zA-Z0-9/_{}.-]*"' "$CATALOG" | tr -d '"' | sort -u > "$tmp/catalog-routes.txt"
# 清单里的 GET 路由，去掉片段 / 导出 / 带参数的详情 —— 与生成器的「页面路由」口径一致
awk -F'|' 'NF >= 7 && $2 ~ /^ *GET *$/ { p=$3; gsub(/`/, "", p); gsub(/^ +| +$/, "", p); print p }' "$tmp/ledger.md" \
  `# 🔴 后缀锚定 + 路径边界（复审 C3）：原来是子串匹配，于是 /admin/export-center、` \
  `# /admin/detail-templates、/admin/navigation-settings 这些**真页面**被静默吞掉（实测三条全漏），` \
  `# 而 L1 的 AdminPageCatalogCoverageTest.looksLikeAPage 用的是后缀锚定 —— 两套正则、口径不同，` \
  `# 只是今天结论恰好都是 42 才没暴露。两边现在逐字一致。` \
  | grep -vE '\{' \
  | grep -vE '(export|\.csv|\.xlsx|drawer|/detail|/queue|/fragment|/preview|/search|/lookup|/suggest|/json)$' \
  | grep -vE '^/admin/(login|logout|denied|lang|oauth|nav)(/|$)' \
  | sort -u > "$tmp/page-routes.raw"
# 例外清单：长得像页面路由、但其实是页签 / 片段 / 选择器 / 表单页的 GET。
# 🔴 显式列表而不是脚本里一条 grep -v 正则：正则一改，真正漏配的页面会跟着被吞掉，且没人看得出来。
sed -e 's/#.*$//' -e 's/[ \t]*$//' "$EXCEPTIONS" | grep -E '^/admin' | sort -u > "$tmp/exceptions.txt"
comm -23 "$tmp/page-routes.raw" "$tmp/exceptions.txt" > "$tmp/page-routes.txt"
# 例外清单里的死条目也要报：留着一条早已不存在的路径，等于给未来的漏配留了个后门。
stale_exc=$(comm -13 "$tmp/page-routes.raw" "$tmp/exceptions.txt")
if [[ -n "$stale_exc" ]]; then
  echo "::warning::例外清单里下列路径已不是存活的 GET，请清理 $EXCEPTIONS："
  printf '  %s\n' $stale_exc
fi

missing_in_catalog=$(comm -23 "$tmp/page-routes.txt" "$tmp/catalog-routes.txt")
if [[ -n "$missing_in_catalog" ]]; then
  echo "::error::下列页面级 GET 路由不在 AdminPageCatalog 里（进不了侧导航，也进不了权限矩阵）："
  printf '  %s\n' $missing_in_catalog
  fail=1
fi
dead_in_catalog=$(comm -13 "$tmp/page-routes.txt" "$tmp/catalog-routes.txt")
if [[ -n "$dead_in_catalog" ]]; then
  echo "::error::AdminPageCatalog 里下列 route 已无对应的存活页面级 GET（菜单里一个点不开的死项）："
  printf '  %s\n' $dead_in_catalog
  fail=1
fi

# 在册但没有任何端点门控用到的码：不算错（有些码只管前端显隐 / 只在矩阵里给），但要看得见。
unused=$(comm -13 "$tmp/used.txt" "$tmp/perms.txt")
if [[ -n "$unused" ]]; then
  echo "::warning::下列权限码在册但没有任何 @PreAuthorize 用到（矩阵里勾了也不改变任何端点的可达性）："
  printf '  %s\n' $unused
fi

used_count=$(grep -c . "$tmp/used.txt")
echo "check-admin-permission-consistency: 权限册 $perm_count 个码 · 门控实际用到 $used_count 个 · 页面级 GET $(grep -c . "$tmp/page-routes.txt") 条 · 目录 route $(grep -c . "$tmp/catalog-routes.txt") 条"
if [[ "$fail" -eq 0 ]]; then
  echo "check-admin-permission-consistency: OK（零差异）"
fi
exit $fail
