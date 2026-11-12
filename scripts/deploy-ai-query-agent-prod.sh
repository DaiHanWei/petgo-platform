#!/usr/bin/env bash
#
# ops AI 查数 agent —— 生产部署脚本（容器 ai-query-agent）
#
# 由 deploy-ai-query-agent-stag.sh 派生，差异只在名字与挂载：容器 / 镜像 / 网络 / 卷改为生产名，
# 代码取 main，另挂生产日志 ~/petgo-server/logs → /logs/prod:ro。只在收到明确部署指令后执行。
#
# ⚠️ 2026-11-11 Dai 拍板：不等 Story 3.1 白名单代理先上生产，明确接受「agent 可任意出网 +
#    SUBPROCESS_ENV_SCRUB=0 时子进程可读订阅 token」在生产数据上的外带风险。3.1 落地后去掉 EGRESS_NETWORK。
#
# 做什么：
#   1. SSH 到服务器，用 deploy key 在服务器上拉取仓库（不从本机上传代码）
#   2. docker build ai-query-agent:prod
#   3. 确保 internal 网络 aiq-prod-net 存在，把 petgo-server 接入；petgo-postgres 须 CONFIRM_SHARED_INFRA=1
#   4. 以 1 核 2G、只读根文件系统、只读日志 / 代码挂载、不挂 docker socket、不映射端口的方式重建容器
#
# 🔴 每次部署生产后端（deploy-backend.sh 重建 petgo-server）后，petgo-server 会脱离 aiq-prod-net，
#    AI 查数连不上 agent：重跑本脚本，或 `docker network connect aiq-prod-net petgo-server`。
#
# 用法:
#   CONFIRM_SHARED_INFRA=1 ./scripts/deploy-ai-query-agent-prod.sh
#
# 可选环境变量:
#   DEPLOY_HOST=dai@62.146.239.156   服务器地址
#   GIT_REF=main                       服务器上检出的分支 / tag / commit
#   CONFIRM_SHARED_INFRA=1             允许 docker network connect petgo-postgres（共享基建）
#   EGRESS_NETWORK=aiq-prod-egress     白名单代理落地前的临时出网网络；设为空则不接（claude 将无法访问 Anthropic）
#
# 服务器一次性前置（runbook §8）:
#   ~/.ssh/ai_query_agent_deploy_key   只读 deploy key（与 stag 共用）
#   ~/ai-query-agent/env               agent env，权限 600（AGENT_SECRET 与生产 AI_QUERY_AGENT_SECRET 相同）
#   ~/ai-query-agent/pgpass            claude_bot 的 .pgpass，权限 600，属主 uid 10002
#                                      （第二行为埋点库 analytics_reader，env 配 AIQ_ANALYTICS_PGDATABASE / _PGUSER；
#                                       缺失只警告，见 runbook §8.1）
#
set -euo pipefail

readonly DEPLOY_HOST="${DEPLOY_HOST:-dai@62.146.239.156}"
readonly GIT_REF="${GIT_REF:-main}"
readonly REPO_URL="git@github.com:DaiHanWei/petgo-platform.git"
readonly CONFIRM_SHARED_INFRA="${CONFIRM_SHARED_INFRA:-0}"
readonly EGRESS_NETWORK="${EGRESS_NETWORK-aiq-prod-egress}"

readonly REMOTE_BASE="~/ai-query-agent"
readonly REMOTE_SRC="$REMOTE_BASE/src"
readonly DEPLOY_KEY="~/.ssh/ai_query_agent_deploy_key"
readonly IMAGE="ai-query-agent:prod"
readonly CONTAINER="ai-query-agent"
readonly INTERNAL_NETWORK="aiq-prod-net"
readonly OPS_CONTAINER="petgo-server"
readonly DB_CONTAINER="petgo-postgres"
readonly HOME_VOLUME="ai-query-agent-home"
readonly CLAUDE_UID="10002"
readonly DATA_VOLUME="ai-query-agent-data"
readonly EXPORT_VOLUME="ai-query-agent-exports"
# stag 只挂 stag 日志（spec §7）
readonly STAG_LOG_DIR="~/petgo-server-stag/logs"
readonly PROD_LOG_DIR="~/petgo-server/logs"

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
  echo "⚠ env 未配置 AIQ_ANALYTICS_PGDATABASE / AIQ_ANALYTICS_PGUSER：AI 查数只能查业务库（埋点库接入见 runbook §8.1）"
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
  -v $PROD_LOG_DIR:/logs/prod:ro \\
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
