#!/usr/bin/env bash
#
# TailTopia 后端一键部署脚本（petgo-backend → 62.146.239.156，与 Logistic 共用同一台 Docker 主机）
#
# 用法:
#   ./scripts/deploy-backend.sh          # 本地 mvn build → scp → 服务器 docker build + 重启容器
#
# 可选环境变量:
#   DEPLOY_HOST=dai@62.146.239.156   服务器地址（默认）
#   SKIP_BUILD=1                     跳过本地 mvn build，直接用现有 target/*.jar
#   SKIP_TESTS=0                     本地 build 时跑测试（默认 1=skip，与 logistic 一致）
#
# 前置（一次性，详见 docs/deployment-guide-backend.md §2）:
#   1. 本地装好 mvn（或用仓库自带 ./mvnw）/ Java 21
#   2. SSH 公钥已注册到服务器
#   3. 服务器已建好 jbp-net 网络、petgo-postgres 容器、共享 redis 容器、~/.env.petgo
#
set -euo pipefail

# ============================================================================
# 配置
# ============================================================================
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly BACKEND_DIR="$REPO_ROOT/petgo-backend"
readonly DEPLOY_HOST="${DEPLOY_HOST:-dai@62.146.239.156}"
readonly TIMESTAMP="$(date +%Y%m%d-%H%M%S)"

# 容器/端口约定（与 logistic 错开：8080 jbp-kf / 8081 logistic-server / 8082 app / 8083 web）
readonly IMAGE="petgo-server"
readonly CONTAINER="petgo-server"
readonly NETWORK="jbp-net"
readonly HOST_PORT="8084"        # 127.0.0.1:8084 → 容器 8080；Cloudflare Tunnel 指向此端口
readonly ENV_FILE="~/.env.petgo"

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
  ssh -o ConnectTimeout=5 -o BatchMode=yes "$DEPLOY_HOST" 'true' \
    || die "无法 SSH 到 $DEPLOY_HOST（检查公钥 / 网络）"
}

# ============================================================================
# 部署 petgo-server
# ============================================================================
deploy_server() {
  log "▶ 部署 $CONTAINER"
  cd "$BACKEND_DIR"

  # 本地构建 jar（与 logistic 同款：服务器不装 maven，jar 在本地出）。
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

  local tarball="/tmp/petgo-server-build-$TIMESTAMP.tar.gz"
  log "打包 Dockerfile.deploy + jar"
  tar -czf "$tarball" Dockerfile.deploy "$jar"

  log "scp 到服务器"
  scp -q "$tarball" "$DEPLOY_HOST:/tmp/"
  local remote_tar="/tmp/$(basename "$tarball")"

  log "服务器端：docker build + 重启容器"
  ssh "$DEPLOY_HOST" bash -se <<EOF
set -euo pipefail
mkdir -p ~/petgo-server && cd ~/petgo-server

# 备份当前 jar（供本地诊断）
if ls target/petgo-backend-*.jar >/dev/null 2>&1; then
  cp target/petgo-backend-*.jar ~/petgo-server-bak-$TIMESTAMP.jar 2>/dev/null || true
fi

# 解压新产物（含 Dockerfile.deploy + target/*.jar）
tar -xzf "$remote_tar"
rm "$remote_tar"

# 前置校验：网络 / 依赖容器 / env 文件
docker network inspect $NETWORK >/dev/null 2>&1 || { echo "ERROR: docker 网络 $NETWORK 不存在，先做一次性初始化（见 runbook §2）"; exit 1; }
docker ps --format '{{.Names}}' | grep -qx petgo-postgres || { echo "ERROR: petgo-postgres 容器未运行，先做一次性初始化（见 runbook §2.3）"; exit 1; }
docker ps --format '{{.Names}}' | grep -qx redis || { echo "ERROR: 共享 redis 容器未运行（见 runbook §2.3）"; exit 1; }
[[ -f ~/.env.petgo ]] || { echo "ERROR: ~/.env.petgo 缺失，参考 runbook §2.4 创建"; exit 1; }

# 备份当前镜像（供回滚）
docker tag $IMAGE:latest $IMAGE:previous 2>/dev/null || true

# 构建镜像
docker build -f Dockerfile.deploy -t $IMAGE:latest -t "$IMAGE:$TIMESTAMP" .

# 重启容器
docker stop $CONTAINER 2>/dev/null || true
docker rm   $CONTAINER 2>/dev/null || true
docker run -d \\
  --name $CONTAINER \\
  --network $NETWORK \\
  -p 127.0.0.1:$HOST_PORT:8080 \\
  --restart unless-stopped \\
  --env-file ~/.env.petgo \\
  $IMAGE:latest

# 等待健康检查（health 含 db + redis，UP 即三者连通）
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
  ok "全部完成 ✨  —— 公网入口需在 Cloudflare 控制台把域名指向 127.0.0.1:${HOST_PORT}（见 runbook §2.5）"
}

main "$@"
