#!/usr/bin/env bash
#
# ops AI 查数 agent —— STAGING 部署脚本（容器 ai-query-agent-stag）
#
# 只在收到明确部署指令后执行（spec §7）。详见 docs/runbooks/runbook-ai-query-agent.md。
#
# 做什么：
#   1. SSH 到服务器，在服务器上用 deploy key 拉取仓库（不从本机上传任何代码）
#   2. docker build ai-query-agent 镜像（:stag）
#   3. 确保 stag 专用 internal 网络存在，按需把 petgo-server-stag 接入
#   4. 以 1 核 2G、只读根文件系统、只读日志/代码挂载、不挂 docker socket、不映射端口的方式重建容器
#      （代码挂载为无 .git 的快照 repo-<sha>，claude 以独立 uid 10002 运行）
#
# 不做什么：
#   - 不碰生产容器 ai-query-agent / petgo-server
#   - 不自动把共享的 petgo-postgres 接入网络（生产基建变更，须 Dai 确认后带 CONFIRM_SHARED_INFRA=1 执行）
#   - 不建库账号（见 ai-query-agent/deploy/sql/，由 DBA 手工执行）
#
# 用法:
#   ./scripts/deploy-ai-query-agent-stag.sh
#
# 可选环境变量:
#   DEPLOY_HOST=dai@62.146.239.156   服务器地址（与现有 stag 部署脚本一致）
#   GIT_REF=feat/ops-ai-query          服务器上检出的分支 / tag / commit
#   CONFIRM_SHARED_INFRA=1             已经 Dai 确认，允许 docker network connect petgo-postgres
#   EGRESS_NETWORK=aiq-stag-egress     Story 3.1 白名单代理落地前，临时提供出网的普通 bridge 网络；设为空则不接
#
# 服务器一次性前置（runbook §2）:
#   ~/.ssh/ai_query_agent_deploy_key        只读 deploy key（GitHub 仓库 Deploy keys，Read-only）
#   ~/ai-query-agent-stag/env               agent env 文件，权限 600（模板 ai-query-agent/.env.example）
#   ~/ai-query-agent-stag/pgpass            claude_bot_stag 的 .pgpass，权限 600，属主 uid 10002
#                                           （第二行为埋点库 analytics_reader_stag，env 配 AIQ_ANALYTICS_PGDATABASE / _PGUSER；
#                                            缺失只警告，见 runbook §2 第 6 步）
#
set -euo pipefail

readonly DEPLOY_HOST="${DEPLOY_HOST:-dai@62.146.239.156}"
readonly GIT_REF="${GIT_REF:-feat/ops-ai-query}"
readonly REPO_URL="git@github.com:DaiHanWei/petgo-platform.git"
readonly CONFIRM_SHARED_INFRA="${CONFIRM_SHARED_INFRA:-0}"
readonly EGRESS_NETWORK="${EGRESS_NETWORK-aiq-stag-egress}"

readonly REMOTE_BASE="~/ai-query-agent-stag"
readonly REMOTE_SRC="$REMOTE_BASE/src"
readonly DEPLOY_KEY="~/.ssh/ai_query_agent_deploy_key"
readonly IMAGE="ai-query-agent:stag"
readonly CONTAINER="ai-query-agent-stag"
readonly INTERNAL_NETWORK="aiq-stag-net"
readonly OPS_CONTAINER="petgo-server-stag"
readonly DB_CONTAINER="petgo-postgres"
readonly HOME_VOLUME="ai-query-agent-stag-home"
readonly CLAUDE_UID="10002"
readonly DATA_VOLUME="ai-query-agent-stag-data"
readonly EXPORT_VOLUME="ai-query-agent-stag-exports"
# stag 只挂 stag 日志（spec §7）
readonly STAG_LOG_DIR="~/petgo-server-stag/logs"

log() { echo "[$(date +%H:%M:%S)] $*"; }
die() { echo "✗ $*" >&2; exit 1; }

command -v ssh >/dev/null || die "缺少 ssh"
ssh -o ConnectTimeout=5 -o BatchMode=yes "$DEPLOY_HOST" true || die "无法 SSH 到 $DEPLOY_HOST"

log "▶ 部署 ${CONTAINER}（GIT_REF=${GIT_REF}）"
ssh "$DEPLOY_HOST" bash -se <<EOF
set -euo pipefail

for f in $REMOTE_BASE/env $REMOTE_BASE/pgpass $DEPLOY_KEY; do
  [ -f "\$f" ] || { echo "缺少 \$f，见 runbook §2"; exit 1; }
done
for f in $REMOTE_BASE/env $REMOTE_BASE/pgpass; do
  [ "\$(stat -c %a "\$f")" = "600" ] || { echo "\$f 权限必须是 600"; exit 1; }
done
# psql 以 claude 的 uid 读取 .pgpass，文件须归该 uid 所有
[ "\$(stat -c %u $REMOTE_BASE/pgpass)" = "$CLAUDE_UID" ] \
  || { echo "pgpass 属主必须是 uid ${CLAUDE_UID}：sudo chown $CLAUDE_UID:$CLAUDE_UID $REMOTE_BASE/pgpass"; exit 1; }
if grep -Eiq '^[[:space:]]*(export[[:space:]]+)?(LARK|FEISHU)_' $REMOTE_BASE/env; then
  echo "env 文件含 Lark 变量，拒绝部署"; exit 1
fi
# 埋点库只读账号（analytics-pipeline Story 4.8）：缺失只警告、照常部署（agent 仍可只查业务库）
if ! grep -Eq '^[[:space:]]*(export[[:space:]]+)?AIQ_ANALYTICS_PGDATABASE=[^[:space:]]' $REMOTE_BASE/env \
   || ! grep -Eq '^[[:space:]]*(export[[:space:]]+)?AIQ_ANALYTICS_PGUSER=[^[:space:]]' $REMOTE_BASE/env; then
  echo "⚠ env 未配置 AIQ_ANALYTICS_PGDATABASE / AIQ_ANALYTICS_PGUSER：AI 查数只能查业务库（埋点库接入见 runbook §2 第 6 步）"
fi

# 1. 用 deploy key 在服务器上拉取代码
export GIT_SSH_COMMAND="ssh -i $DEPLOY_KEY -o IdentitiesOnly=yes"
if [ ! -d $REMOTE_SRC/.git ]; then
  git clone --filter=blob:none --no-checkout $REPO_URL $REMOTE_SRC
fi
git -C $REMOTE_SRC fetch --prune origin
git -C $REMOTE_SRC checkout --force --detach "origin/$GIT_REF" 2>/dev/null \
  || git -C $REMOTE_SRC checkout --force --detach "$GIT_REF"
SHA=\$(git -C $REMOTE_SRC rev-parse HEAD)
echo "代码版本: \$SHA"
# 给 claude 读的代码用独立快照（无 .git 历史），下次部署 checkout 不会改动运行中容器看到的文件
SNAP=$REMOTE_BASE/repo-\$SHA
if [ ! -d "\$SNAP" ]; then
  rm -rf "\$SNAP.tmp" && mkdir -p "\$SNAP.tmp"
  git -C $REMOTE_SRC archive HEAD | tar -x -C "\$SNAP.tmp"
  mv "\$SNAP.tmp" "\$SNAP"
fi

# 2. 构建镜像
docker build -t $IMAGE $REMOTE_SRC/ai-query-agent

# 3. 网络：stag 专用 internal 网络（无出网能力）
docker network inspect $INTERNAL_NETWORK >/dev/null 2>&1 \
  || docker network create --internal $INTERNAL_NETWORK
if ! docker inspect -f '{{json .NetworkSettings.Networks}}' $OPS_CONTAINER | grep -q '"$INTERNAL_NETWORK"'; then
  docker network connect $INTERNAL_NETWORK $OPS_CONTAINER
  echo "已把 $OPS_CONTAINER 接入 $INTERNAL_NETWORK"
fi
if ! docker inspect -f '{{json .NetworkSettings.Networks}}' $DB_CONTAINER | grep -q '"$INTERNAL_NETWORK"'; then
  if [ "$CONFIRM_SHARED_INFRA" = "1" ]; then
    docker network connect $INTERNAL_NETWORK $DB_CONTAINER
    echo "已把 $DB_CONTAINER 接入 ${INTERNAL_NETWORK}（已确认的共享基建变更）"
  else
    echo "✗ $DB_CONTAINER 尚未接入 ${INTERNAL_NETWORK}。这是共享基建变更，须 Dai 确认后以 CONFIRM_SHARED_INFRA=1 重跑"
    exit 1
  fi
fi

# 4. 重建容器
docker rm -f $CONTAINER >/dev/null 2>&1 || true
docker run -d \\
  --name $CONTAINER \\
  --network $INTERNAL_NETWORK \\
  --restart unless-stopped \\
  --cpus 1 --memory 2g --memory-swap 2g --pids-limit 256 \\
  --read-only --tmpfs /tmp:rw,size=256m \\
  --cap-drop ALL --cap-add SETUID --cap-add SETGID --cap-add KILL --cap-add DAC_OVERRIDE \\
  --security-opt no-new-privileges \\
  --env-file $REMOTE_BASE/env \\
  -e PGPASSFILE=/run/secrets/pgpass \\
  -v $REMOTE_BASE/pgpass:/run/secrets/pgpass:ro \\
  -v $STAG_LOG_DIR:/logs/stag:ro \\
  -v "\$SNAP":/repo:ro \\
  -v $HOME_VOLUME:/home/claude \\
  -v $DATA_VOLUME:/data \\
  -v $EXPORT_VOLUME:/exports \\
  $IMAGE

# Story 3.1 白名单代理落地前，临时接一个可出网的普通网络供 claude 访问 Anthropic API
if [ -n "$EGRESS_NETWORK" ]; then
  docker network inspect $EGRESS_NETWORK >/dev/null 2>&1 || docker network create $EGRESS_NETWORK
  docker network connect $EGRESS_NETWORK $CONTAINER
  echo "⚠ 临时出网网络 $EGRESS_NETWORK 已接入；Story 3.1 改为白名单代理后移除"
fi

# 清理旧代码快照
for d in $REMOTE_BASE/repo-*; do
  [ "\$d" = "\$SNAP" ] || rm -rf "\$d"
done

sleep 3
docker ps --filter name=^/$CONTAINER\$ --format '{{.Names}} {{.Status}}'
docker logs --tail 5 $CONTAINER
EOF
log "完成。按 runbook §4 做部署后检查。"
