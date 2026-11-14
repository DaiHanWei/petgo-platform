#!/usr/bin/env bash
# =============================================================================
# V1.5.0 pet-species Story 1.4 · FR-208 存量物种迁移入口（见同目录 README.md）。
#
# 用法：  ./run-migration.sh <mapping.csv>           # 干跑：执行并打印统计，最后 ROLLBACK
#         ./run-migration.sh <mapping.csv> apply     # 真跑：COMMIT
#
# 连接参数只走 libpq 环境变量（PGHOST / PGPORT / PGDATABASE / PGUSER / PGPASSWORD 或 ~/.pgpass），
# 🔴 本脚本不写、不打印任何凭证。🔴 生产执行前须操作者明确确认（README 第 ③ 步）。
# =============================================================================
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "用法: $0 <mapping.csv> [apply]" >&2
    exit 2
fi
csv="$1"
apply=0
if [[ $# -eq 2 ]]; then
    if [[ "$2" != "apply" ]]; then
        echo "第二个参数只能是 apply" >&2
        exit 2
    fi
    apply=1
fi
if [[ ! -r "$csv" ]]; then
    echo "读不到映射文件: $csv" >&2
    exit 2
fi

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# psql 单引号参数里的单引号写两个（路径含 ' 时）。
here_q="${here//\'/\'\'}"
script="$(mktemp)"
trap 'rm -f "$script"' EXIT

# psql 的 \copy 不做变量替换，映射 CSV 经 stdin（pstdin）喂入；apply-mapping.sql 用 \i 绝对路径引入。
cat >"$script" <<SQL
\\set ON_ERROR_STOP 1
BEGIN;
CREATE TEMP TABLE species_mapping (
    pet_id         BIGINT,
    pet_name       TEXT,
    current_type   TEXT,
    target_species TEXT,
    target_breed   TEXT,
    confidence     TEXT,
    reason         TEXT
);
\\copy species_mapping FROM pstdin WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')
\\i '${here_q}/apply-mapping.sql'
\\if :apply
COMMIT;
\\echo '已提交（apply）'
\\else
ROLLBACK;
\\echo '干跑：已回滚。确认统计无误后加 apply 参数真跑。'
\\endif
SQL

psql -X -v ON_ERROR_STOP=1 -v apply="$apply" -f "$script" <"$csv"
