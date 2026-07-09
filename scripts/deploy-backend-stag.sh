#!/usr/bin/env bash
#
# TailTopia 后端 STAGING 部署脚本（petgo-server-stag → 62.146.239.156，与生产 petgo-server 并列）
#
# 与 scripts/deploy-backend.sh 完全隔离：独立镜像 tag / 容器名 / 端口 / env 文件。
# **全程绝不触碰生产** 的 petgo-server 容器、petgo-server:latest / :previous 镜像。
# 详见 docs/runbook-staging.md。
#
# 用法:
#   ./scripts/deploy-backend-stag.sh          # 本地 mvn build（stag 分支）→ scp → 服务器 docker build + 重启 staging 容器
#
# 可选环境变量:
#   DEPLOY_HOST=dai@62.146.239.156   服务器地址（默认，与生产同主机）
#   SKIP_BUILD=1                     跳过本地 mvn build，直接用现有 target/*.jar
#   SKIP_TESTS=0                     本地 build 时跑测试（默认 1=skip）
#   ALLOW_BRANCH=1                   允许在非 stag 分支部署 staging（默认拒绝）
#
# 前置（一次性，详见 docs/runbook-staging.md §C）:
#   1. 服务器已有 jbp-net / petgo-postgres / redis（与生产共用）
#   2. petgo_stag 库已从生产克隆
#   3. ~/.env.petgo-stag 已就位（DB_NAME=petgo_stag / REDIS_DB=3 / profile=prod ...）
#
set -euo pipefail

# ============================================================================
# 配置（与生产错开：生产用 8084 / petgo-server / :latest / ~/.env.petgo）
# ============================================================================
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly BACKEND_DIR="$REPO_ROOT/petgo-backend"
readonly DEPLOY_HOST="${DEPLOY_HOST:-dai@62.146.239.156}"
readonly TIMESTAMP="$(date +%Y%m%d-%H%M%S)"

readonly IMAGE="petgo-server"       # 复用镜像名，但只用 :stag 系列 tag，绝不动 :latest/:previous
readonly TAG="stag"
readonly CONTAINER="petgo-server-stag"
readonly NETWORK="jbp-net"
readonly HOST_PORT="8085"           # 127.0.0.1:8085 → 容器 8080；Cloudflare Tunnel 指向此端口
readonly ENV_FILE="~/.env.petgo-stag"
readonly EXPECTED_BRANCH="stag"
readonly REMOTE_DIR="~/petgo-server-stag"

if [[ -t 1 ]]; then
  readonly C_GREEN='\033[0;32m' C_BLUE='\033[0;34m' C_YELLOW='\033[1;33m' C_RED='\033[0;31m' C_RESET='\033[0m'
else
  readonly C_GREEN='' C_BLUE='' C_YELLOW='' C_RED='' C_RESET=''
fi
log()  { echo -e "${C_BLUE}[$(date +%H:%M:%S)]${C_RESET} $*"; }
ok()   { echo -e "${C_GREEN}✓${C_RESET} $*"; }
warn() { echo -e "${C_YELLOW}⚠${C_RESET} $*"; }
die()  { echo -e "${C_RED}✗${C_RESET} $*" >&2; exit 1; }

require_cmd() { command -v "$1" >/dev/null 2>&1 || die "缺少命令: $1"; }

precheck() {
  require_cmd ssh
  require_cmd scp
  require_cmd tar
  require_cmd git

  # 分支护栏：staging 必须从 stag 分支部署（避免误把别的分支当 staging 上）
  local branch
  branch="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
  if [[ "$branch" != "$EXPECTED_BRANCH" ]]; then
    if [[ "${ALLOW_BRANCH:-0}" == "1" ]]; then
      warn "当前分支 '$branch' 非 '$EXPECTED_BRANCH'，因 ALLOW_BRANCH=1 继续"
    else
      die "当前分支 '$branch' 不是 '$EXPECTED_BRANCH'。staging 应从 stag 分支部署；确要强制用 ALLOW_BRANCH=1"
    fi
  fi

  ssh -o ConnectTimeout=5 -o BatchMode=yes "$DEPLOY_HOST" 'true' \
    || die "无法 SSH 到 $DEPLOY_HOST（检查公钥 / 网络）"
}

# ============================================================================
# 部署 petgo-server-stag
# ============================================================================
deploy_server() {
  log "▶ 部署 $CONTAINER（staging，端口 $HOST_PORT）"
  cd "$BACKEND_DIR"

  # 本地构建 jar（服务器不装 maven）。
  if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
    local mvn_cmd
    if command -v mvn >/dev/null 2>&1; then mvn_cmd="mvn"; else mvn_cmd="./mvnw"; fi
    if [[ "${SKIP_TESTS:-1}" == "1" ]]; then
      log "本地 build ($mvn_cmd -B package -DskipTests)"
      "$mvn_cmd" -B package -DskipTests -q
    else
      log "本地 build ($mvn_cmd -B package，含测试)"
      "$mvn_cmd" -B package -q
    fi
  else
    warn "SKIP_BUILD=1，跳过本地 build"
  fi

  local jar
  jar="$(ls -t target/petgo-backend-*.jar 2>/dev/null | head -1)"
  [[ -n "$jar" && -f "$jar" ]] || die "target/ 下找不到 petgo-backend-*.jar"
  log "使用 jar: $jar ($(du -h "$jar" | cut -f1))"

  local tarball="/tmp/petgo-server-stag-build-$TIMESTAMP.tar.gz"
  log "打包 Dockerfile.deploy + jar"
  tar -czf "$tarball" Dockerfile.deploy "$jar"

  log "scp 到服务器"
  scp -q "$tarball" "$DEPLOY_HOST:/tmp/"
  local remote_tar="/tmp/$(basename "$tarball")"

  log "服务器端：docker build + 重启 staging 容器"
  ssh "$DEPLOY_HOST" bash -se <<EOF
set -euo pipefail
mkdir -p $REMOTE_DIR && cd $REMOTE_DIR

# 备份当前 jar（供本地诊断）
if ls target/petgo-backend-*.jar >/dev/null 2>&1; then
  cp target/petgo-backend-*.jar $REMOTE_DIR/../petgo-server-stag-bak-$TIMESTAMP.jar 2>/dev/null || true
fi

# 解压新产物（含 Dockerfile.deploy + target/*.jar）
tar -xzf "$remote_tar"
rm "$remote_tar"

# 前置校验：网络 / 依赖容器 / staging env / staging 库
docker network inspect $NETWORK >/dev/null 2>&1 || { echo "ERROR: docker 网络 $NETWORK 不存在"; exit 1; }
docker ps --format '{{.Names}}' | grep -qx petgo-postgres || { echo "ERROR: petgo-postgres 容器未运行"; exit 1; }
docker ps --format '{{.Names}}' | grep -qx redis || { echo "ERROR: 共享 redis 容器未运行"; exit 1; }
[[ -f ~/.env.petgo-stag ]] || { echo "ERROR: ~/.env.petgo-stag 缺失，见 runbook-staging.md §C2"; exit 1; }
docker exec petgo-postgres psql -U petgo -lqt | cut -d'|' -f1 | grep -qw petgo_stag \
  || { echo "ERROR: petgo_stag 库不存在，先按 runbook-staging.md §C1 克隆"; exit 1; }

# 护栏：确认 env 指向 staging 库（防误用生产 env 把 staging 容器接到生产库）
if ! grep -q '^DB_NAME=petgo_stag$' ~/.env.petgo-stag; then
  echo "ERROR: ~/.env.petgo-stag 的 DB_NAME 不是 petgo_stag，拒绝部署（防误连生产库）"; exit 1
fi

# 备份当前 staging 镜像（供 staging 回滚；用 :stag-previous，绝不碰生产 :previous）
docker tag $IMAGE:$TAG $IMAGE:$TAG-previous 2>/dev/null || true

# 构建镜像（只打 :stag 系列 tag，绝不打 :latest）
docker build -f Dockerfile.deploy -t $IMAGE:$TAG -t "$IMAGE:$TAG-$TIMESTAMP" .

# 重启 staging 容器（只动 petgo-server-stag，绝不碰生产 petgo-server）
docker stop $CONTAINER 2>/dev/null || true
docker rm   $CONTAINER 2>/dev/null || true
mkdir -p $REMOTE_DIR/logs
docker run -d \\
  --name $CONTAINER \\
  --network $NETWORK \\
  -p 127.0.0.1:$HOST_PORT:8080 \\
  --restart unless-stopped \\
  --env-file ~/.env.petgo-stag \\
  -v $REMOTE_DIR/logs:/app/logs \\
  $IMAGE:$TAG

# 等待健康检查（health 含 db + redis）
echo "等待 actuator/health..."
for i in \$(seq 1 18); do
  sleep 5
  if curl -fs http://127.0.0.1:$HOST_PORT/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    echo "✓ $CONTAINER UP"
    docker logs --tail 5 $CONTAINER
    exit 0
  fi
  echo "  ... \$((i*5))s"
done
echo "ERROR: 90s 未 UP，看日志:"
docker logs --tail 60 $CONTAINER
exit 1
EOF

  rm "$tarball"
  ok "${CONTAINER} 部署完成（127.0.0.1:${HOST_PORT}）"
}

main() {
  precheck
  deploy_server
  ok "staging 部署完成 ✨  —— 公网入口在 Cloudflare 把 api-stag/ops-stag 指向 127.0.0.1:${HOST_PORT}（见 runbook-staging.md §E）"
  warn "生产 petgo-server / :latest 未受影响。"
}

main "$@"
