#!/usr/bin/env bash
# Flyway 迁移号守门（决策 E6，见 _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md）
#
# 规则：
#   1. 树内同一版本号只能出现一次（同号不同文件 = Flyway 启动即崩）。
#   2. 已合入 main 的迁移文件冻结：不得修改、不得删除（改表另起新迁移）。
#      —— 真实约束是「已被任何环境应用过的迁移不能再改」，改了 checksum 对不上，
#         Flyway 启动即拒。「在 main 上」只是 CI 能查到的最佳代理判据。
#   3. 新增迁移一律时间戳版本号 V<yyyyMMdd_HHmm>__<snake_case>.sql，禁止序列号。
#      —— 例外：scripts/ci/flyway-legacy-versions.txt 列出的存量序号迁移保留原号，
#         它们早于时间戳制、且多已应用到 prod/petgo_stag，改名会让 Flyway 起不来。
#   4. 新增迁移的版本号不得与 main 上任何已有版本号相同。
#
# 两个迁移目录（analytics-pipeline Story 1.1 起）：
#   - 业务库  petgo-backend/src/main/resources/db/migration           （legacy 名单只对它生效）
#   - 埋点库  petgo-backend/src/main/resources/db/analytics-migration （一律时间戳，无 legacy 例外）
#   两者是不同数据库、各自独立的 flyway_schema_history，检查 1–4 每个目录各跑一遍、main 版本号各取各的；
#   **跨目录同号不算冲突**。目录不存在时跳过。
#
# 用法：
#   check-flyway-versions.sh                # 只做树内查重（push 到 main 时）
#   check-flyway-versions.sh origin/xxx     # PR 场景：以该 ref 为基线做全部检查
set -uo pipefail

BIZ_DIR="petgo-backend/src/main/resources/db/migration"
ANALYTICS_DIR="petgo-backend/src/main/resources/db/analytics-migration"
MIG_DIRS=("$BIZ_DIR" "$ANALYTICS_DIR")
BASE_REF="${1:-}"
# 时间戳格式：V20260821_1435__init_shop_orders.sql
TS_REGEX='^V20[0-9]{2}(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])_([01][0-9]|2[0-3])[0-5][0-9]__[a-z0-9_]+\.sql$'
# 存量序号迁移基线：时间戳制确立前已存在的序号，保留原号、免于格式检查。
LEGACY_FILE="$(dirname "${BASH_SOURCE[0]}")/flyway-legacy-versions.txt"
legacy_versions=""
if [[ -f "$LEGACY_FILE" ]]; then
  legacy_versions=$(grep -E '^[0-9]+$' "$LEGACY_FILE" || true)
fi
is_legacy() { [[ -n "$legacy_versions" ]] && grep -qx "$1" <<<"$legacy_versions"; }
fail=0

# 文件名 → Flyway 版本号（Flyway 把版本段里的 _ 规范化为 .，V101 与 V101 同号，V20260821_1435 → 20260821.1435）
extract_version() {
  sed -nE 's/^V([0-9_.]+)__.*\.sql$/\1/p' <<<"$1" | tr '_' '.'
}

# 某目录下的 .sql 迁移文件名（目录不存在 → 空，不报错）
list_migrations() {
  [[ -d "$1" ]] || return 0
  ls -1 "$1" | grep '\.sql$' || true
}

# ---- 基线准备（PR 场景） ----
base=""
if [[ -n "$BASE_REF" ]]; then
  git fetch origin main --quiet 2>/dev/null || true
  if ! git rev-parse --verify --quiet origin/main >/dev/null; then
    echo "::error::取不到 origin/main，无法做冻结/撞号检查"
    exit 1
  fi
  base=$(git merge-base HEAD "$BASE_REF") || { echo "::error::merge-base HEAD $BASE_REF 失败"; exit 1; }
fi

summary=""
for MIG_DIR in "${MIG_DIRS[@]}"; do
  # legacy 名单只对业务目录生效；埋点目录一律要求时间戳
  allow_legacy=0
  [[ "$MIG_DIR" == "$BIZ_DIR" ]] && allow_legacy=1

  # ---- 检查 1：树内同号查重（目录内） ----
  dups=$(list_migrations "$MIG_DIR" | while read -r f; do extract_version "$f"; done | sort | uniq -d)
  if [[ -n "$dups" ]]; then
    echo "::error::迁移版本号在 $MIG_DIR 内重复（Flyway 将启动失败）: $(echo "$dups" | tr '\n' ' ')"
    list_migrations "$MIG_DIR" | while read -r f; do
      v=$(extract_version "$f")
      grep -qx "$v" <<<"$dups" && echo "  重复号 $v: $MIG_DIR/$f"
    done
    fail=1
  fi

  if [[ -n "$base" ]]; then
    # main 上本目录已有的版本号（各目录各取各的，跨目录同号不算撞号）
    main_versions=$(git ls-tree -r --name-only origin/main -- "$MIG_DIR" | sed 's|.*/||' \
      | while read -r f; do extract_version "$f"; done)

    # ---- 检查 2：冻结——main 上已有的迁移文件被修改/删除/改名 ----
    while read -r f; do
      [[ -z "$f" ]] && continue
      fname=$(basename "$f")
      if git cat-file -e "origin/main:$MIG_DIR/$fname" 2>/dev/null; then
        echo "::error file=$f::已合入 main 的迁移文件被修改或删除。已应用的迁移改动会导致 Flyway checksum 校验失败、启动即拒；改表请另起新迁移"
        fail=1
      fi
    done < <(git diff --name-only --diff-filter=MDR "$base" HEAD -- "$MIG_DIR")

    # ---- 检查 3 + 4：新增迁移的命名格式与撞号 ----
    while read -r f; do
      [[ -z "$f" ]] && continue
      fname=$(basename "$f")
      v=$(extract_version "$fname")
      # 存量序号迁移（早于时间戳制、多已应用到 prod/petgo_stag）保留原号，不强制改名。仅业务目录。
      if [[ $allow_legacy -eq 1 ]] && is_legacy "$v"; then
        echo "flyway-guard: 放行存量序号迁移 ${fname}（在 legacy 基线内）"
        continue
      fi
      if ! grep -qE "$TS_REGEX" <<<"$fname"; then
        if [[ $allow_legacy -eq 1 ]]; then
          echo "::error file=$f::新迁移必须用时间戳版本号 V<yyyyMMdd_HHmm>__<snake_case>.sql（如 V20260821_1435__init_shop_orders.sql）。序列号已停用；仅 scripts/ci/flyway-legacy-versions.txt 内的存量号例外"
        else
          echo "::error file=$f::埋点库迁移必须用时间戳版本号 V<yyyyMMdd_HHmm>__<snake_case>.sql，没有任何序号例外"
        fi
        fail=1
        continue
      fi
      if grep -qx "$v" <<<"$main_versions"; then
        echo "::error file=$f::版本号 $v 与 main 上 $MIG_DIR 已有迁移撞号，请换一个时间（同号 = Flyway 启动即崩）"
        fail=1
      fi
    done < <(git diff --name-only --diff-filter=A "$base" HEAD -- "$MIG_DIR")
  fi

  if [[ -d "$MIG_DIR" ]]; then
    summary+="${summary:+，}$(basename "$MIG_DIR") $(list_migrations "$MIG_DIR" | grep -c . || true) 个"
  else
    summary+="${summary:+，}$(basename "$MIG_DIR") 目录不存在（跳过）"
  fi
done

if [[ $fail -eq 0 ]]; then
  echo "flyway-guard: OK（${summary}，各目录无重号${BASE_REF:+，基线 $BASE_REF 冻结/命名/撞号检查通过}）"
fi
exit $fail
