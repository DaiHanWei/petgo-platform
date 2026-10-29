#!/usr/bin/env bash
# =============================================================================
# TailTopia —— 起一个云端 story 全量执行会话（一条 PRD 线 = 一个分支 = 一个会话）
#
# 用法：
#   scripts/cloud-run-story-loop.sh <ver> <主题> <分支> [--skip-epics "10 12"] [--dry-run]
# 版本 / 主题 / 分支全是参数，任何版本通用；唯一前提是该版本目录下已有 sprint-status-<ver>-<主题>.yaml。
# 例：
#   scripts/cloud-run-story-loop.sh v1.3.0 admin feat/1.3.0-ops-ui-refactor --skip-epics "10"
#   scripts/cloud-run-story-loop.sh v1.4.0 app   feat/1.4.0-app
#   scripts/cloud-run-story-loop.sh v2.0.0 shop  feat/2.0.0-shop
#
# 做什么：
#   1. 校验 sprint-status-<ver>-<主题>.yaml 存在、分支已 push 且远端与本地同步。
#   2. 用固定模板拼云端首条提示词（分支 / 版本 / 主题 / 跳过 Epic 全参数化）。
#      主题专属追加规则放 _bmad-output/implementation-artifacts/<ver>/cloud-rules-<主题>.md（可选，存在即拼入）。
#   3. `claude --cloud "<提示词>"` 起会话。并行多条线 = 多次执行本脚本。
#
# 环境：所有会话共用同一个云端环境（tailtopia-l0，setup script = scripts/cloud-setup.sh），
#       环境只建一次，不随分支变。见 docs/runbooks/cloud-dev-workflow.md Stage D。
# =============================================================================
set -euo pipefail

usage() { sed -n '2,20p' "$0"; exit 1; }
[ $# -ge 3 ] || usage

VER="$1"; THEME="$2"; BRANCH="$3"; shift 3
SKIP_EPICS=""; DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-epics) SKIP_EPICS="$2"; shift 2 ;;
    --dry-run)    DRY_RUN=1; shift ;;
    *) echo "未知参数: $1"; usage ;;
  esac
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
impl_dir="_bmad-output/implementation-artifacts/${VER}"
plan_dir="_bmad-output/planning-artifacts/${VER}"
status_file="$impl_dir/sprint-status-${VER}-${THEME}.yaml"
rules_file="$impl_dir/cloud-rules-${THEME}.md"

# ---- 1. 校验 ---------------------------------------------------------------
[ -f "${status_file}" ] || { echo "FATAL: 找不到 ${status_file}（先跑 sprint-planning 生成）"; exit 1; }
[ -f "${plan_dir}/README.md" ] || { echo "FATAL: 找不到 ${plan_dir}/README.md"; exit 1; }

git fetch -q origin
if ! git rev-parse -q --verify "refs/remotes/origin/${BRANCH}" >/dev/null; then
  echo "FATAL: 远端没有分支 ${BRANCH}。先 git push -u origin ${BRANCH}（云端从 GitHub clone，不看本地）"; exit 1
fi
if git rev-parse -q --verify "refs/heads/${BRANCH}" >/dev/null; then
  local_sha="$(git rev-parse "refs/heads/${BRANCH}")"; remote_sha="$(git rev-parse "refs/remotes/origin/${BRANCH}")"
  if [ "$local_sha" != "$remote_sha" ]; then
    echo "FATAL: 本地 ${BRANCH} ($local_sha) 与远端 ($remote_sha) 不一致，先 push 或 pull 再起会话"; exit 1
  fi
fi
ready_count="$(grep -c 'ready-for-dev' "${status_file}" || true)"
echo "==> ${status_file} 里 ready-for-dev 条目：$ready_count"

skip_line="（无需跳过的 Epic）"
if [ -n "$SKIP_EPICS" ]; then
  skip_line="Epic ${SKIP_EPICS// / 与 Epic } 的 story 全部跳过，保持 ready-for-dev 不动——它们要等其它线合入后再做。"
fi
extra_rules=""
if [ -f "$rules_file" ]; then
  extra_rules="$(cat "$rules_file")"
  echo "==> 拼入主题专属规则：$rules_file"
fi

# ---- 2. 提示词模板 -----------------------------------------------------------
PROMPT="$(cat <<EOF
${VER} ${THEME} 线，分支 ${BRANCH}。若当前不在该分支，先 git fetch origin && git checkout ${BRANCH}。
先读 _bmad-output/project-context.md 与 ${plan_dir}/README.md。

循环执行，直到 ${status_file} 里没有 ready-for-dev 的 story：
1. 按 ${status_file} 从上到下取第一条 ready-for-dev 的 story。${skip_line}
2. 对该 story 跑 bmad-dev-story：只到 L0（后端 cd petgo-backend && ./mvnw -B clean package；前端 flutter analyze + flutter test；不起容器、不连库、不连第三方）。L0 不绿不许进下一步。
3. 跑 bmad-code-review 复审本 story 的改动，CONFIRMED 的修掉。
4. 提交（一 story 一 commit，message 前缀 \`feat(${VER}): <story key>\`），推远端 ${BRANCH}。
5. sprint-status 把该 story 置 review；Completion Notes 必须写「L1/L2 待本地验收」。任何 story 不得标 done。
6. 取下一条。

硬规则：
- 不设人工检查点，中途不问我，除非遇到「无法解析版本」「迁移号撞车」「上一条 story 的改动导致本条无法编译」三类才停下报告。
- Flyway 迁移一律时间戳版本号 V<yyyyMMdd_HHmm>__<snake>.sql，取创建时刻；提交前跑 bash scripts/ci/check-flyway-versions.sh origin/main。
- 不部署任何环境、不 push 其它分支、不改集成分支 / main。
- 严格按 story 文件的 Dev Notes「必须保留」列施工；架构疑问以 ${plan_dir} 下本主题的架构 delta 为准，决策以本主题的决策日志为准，不自行拍板。
- 上下文被压缩后继续按 sprint-status 当前状态接着跑，不重做已 review 的 story。
${extra_rules}

全部跑完后输出：完成的 story 列表、跳过的 story 列表、每条 Completion Notes 里标注的待本地验收项汇总、迁移文件清单。
EOF
)"

# ---- 3. 起会话 ---------------------------------------------------------------
if [ "$DRY_RUN" = 1 ]; then
  echo "==> --dry-run，只打印提示词："; echo "-----"; echo "$PROMPT"; echo "-----"; exit 0
fi
echo "==> 起云端会话：${VER} / ${THEME} / ${BRANCH}"
exec claude --cloud "$PROMPT"
