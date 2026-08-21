#!/usr/bin/env bash
# Flyway 迁移号守门（决策 E6，见 _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md）
#
# 规则：
#   1. 树内同一版本号只能出现一次（同号不同文件 = Flyway 启动即崩）。
#   2. 已合入 main 的迁移文件冻结：不得修改、不得删除（改表另起新迁移）。
#   3. 新增迁移一律时间戳版本号 V<yyyyMMdd_HHmm>__<snake_case>.sql，禁止序列号。
#   4. 新增迁移的版本号不得与 main 上任何已有版本号相同。
#
# 用法：
#   check-flyway-versions.sh                # 只做树内查重（push 到 main 时）
#   check-flyway-versions.sh origin/xxx     # PR 场景：以该 ref 为基线做全部检查
set -uo pipefail

MIG_DIR="petgo-backend/src/main/resources/db/migration"
BASE_REF="${1:-}"
# 时间戳格式：V20260821_1435__init_shop_orders.sql
TS_REGEX='^V20[0-9]{2}(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])_([01][0-9]|2[0-3])[0-5][0-9]__[a-z0-9_]+\.sql$'
fail=0

# 文件名 → Flyway 版本号（Flyway 把版本段里的 _ 规范化为 .，V101 与 V101 同号，V20260821_1435 → 20260821.1435）
extract_version() {
  sed -nE 's/^V([0-9_.]+)__.*\.sql$/\1/p' <<<"$1" | tr '_' '.'
}

# ---- 检查 1：树内同号查重 ----
dups=$(ls -1 "$MIG_DIR" | while read -r f; do extract_version "$f"; done | sort | uniq -d)
if [[ -n "$dups" ]]; then
  echo "::error::迁移版本号在当前树内重复（Flyway 将启动失败）: $(echo "$dups" | tr '\n' ' ')"
  ls -1 "$MIG_DIR" | while read -r f; do
    v=$(extract_version "$f")
    grep -qx "$v" <<<"$dups" && echo "  重复号 $v: $MIG_DIR/$f"
  done
  fail=1
fi

# ---- 基线相关检查（PR 场景） ----
if [[ -n "$BASE_REF" ]]; then
  git fetch origin main --quiet 2>/dev/null || true
  if ! git rev-parse --verify --quiet origin/main >/dev/null; then
    echo "::error::取不到 origin/main，无法做冻结/撞号检查"
    exit 1
  fi
  base=$(git merge-base HEAD "$BASE_REF") || { echo "::error::merge-base HEAD $BASE_REF 失败"; exit 1; }

  main_versions=$(git ls-tree -r --name-only origin/main -- "$MIG_DIR" | sed 's|.*/||' \
    | while read -r f; do extract_version "$f"; done)

  # ---- 检查 2：冻结——main 上已有的迁移文件被修改/删除/改名 ----
  while read -r f; do
    [[ -z "$f" ]] && continue
    fname=$(basename "$f")
    if git cat-file -e "origin/main:$MIG_DIR/$fname" 2>/dev/null; then
      echo "::error file=$f::已合入 main 的迁移文件被修改或删除。已提交迁移冻结，改表请另起新迁移（决策 E6/E2）"
      fail=1
    fi
  done < <(git diff --name-only --diff-filter=MDR "$base" HEAD -- "$MIG_DIR")

  # ---- 检查 3 + 4：新增迁移的命名格式与撞号 ----
  while read -r f; do
    [[ -z "$f" ]] && continue
    fname=$(basename "$f")
    if ! grep -qE "$TS_REGEX" <<<"$fname"; then
      echo "::error file=$f::新迁移必须用时间戳版本号 V<yyyyMMdd_HHmm>__<snake_case>.sql（如 V20260821_1435__init_shop_orders.sql），禁止序列号（决策 E6）"
      fail=1
      continue
    fi
    v=$(extract_version "$fname")
    if grep -qx "$v" <<<"$main_versions"; then
      echo "::error file=$f::版本号 $v 与 main 上已有迁移撞号，请换一个时间（决策 E6）"
      fail=1
    fi
  done < <(git diff --name-only --diff-filter=A "$base" HEAD -- "$MIG_DIR")
fi

if [[ $fail -eq 0 ]]; then
  echo "flyway-guard: OK（树内 $(ls -1 "$MIG_DIR" | grep -c '\.sql$') 个迁移，无重号${BASE_REF:+，基线 $BASE_REF 冻结/命名/撞号检查通过}）"
fi
exit $fail
