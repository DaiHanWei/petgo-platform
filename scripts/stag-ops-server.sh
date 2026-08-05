#!/usr/bin/env bash
#
# TailTopia staging 受限运维入口（安装于服务器 dai@62.146.239.156 的 ~/bin/stag-ops.sh）
#
# 用途：给非技术同事（PM）的专用 SSH key 在 authorized_keys 里用 command= 强制绑定本脚本，
# 无论客户端请求执行什么，落到服务器只能触发下面白名单动作，物理上碰不到生产资源
# （petgo-server / 8084 / petgo 库 / redis DB2 / ~/.env.petgo）。
#
# 安装（见 docs/runbook-stag-pm.md §A）：
#   scp scripts/stag-ops-server.sh dai@62.146.239.156:~/bin/stag-ops.sh && ssh dai@62.146.239.156 chmod +x '~/bin/stag-ops.sh'
#   authorized_keys 追加（一行）：
#   command="/home/dai/bin/stag-ops.sh",no-port-forwarding,no-agent-forwarding,no-X11-forwarding,no-pty <PM公钥> stag-pm
#
set -euo pipefail

readonly BUILD_TAR="/tmp/stag-pm-build.tar.gz"
readonly REMOTE_DIR="$HOME/petgo-server-stag"
readonly IMAGE="petgo-server"
readonly TAG="stag"
readonly CONTAINER="petgo-server-stag"
readonly NETWORK="jbp-net"
readonly HOST_PORT="8085"
readonly ENV_FILE="$HOME/.env.petgo-stag"
readonly MAX_TAR_BYTES=314572800  # 300MB

deny() { echo "DENIED: $*" >&2; exit 1; }

do_put_build() {
  head -c "$MAX_TAR_BYTES" > "$BUILD_TAR"
  [[ -s "$BUILD_TAR" ]] || deny "空 build 包"
  tar -tzf "$BUILD_TAR" | grep -q '^Dockerfile\.deploy$' || { rm -f "$BUILD_TAR"; deny "build 包缺 Dockerfile.deploy"; }
  tar -tzf "$BUILD_TAR" | grep -q '^target/petgo-backend-.*\.jar$' || { rm -f "$BUILD_TAR"; deny "build 包缺 target/petgo-backend-*.jar"; }
  echo "OK put-build $(du -h "$BUILD_TAR" | cut -f1)"
}

do_deploy() {
  [[ -f "$BUILD_TAR" ]] || deny "先 put-build 再 deploy"
  local ts; ts="$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$REMOTE_DIR" && cd "$REMOTE_DIR"
  tar -xzf "$BUILD_TAR"
  rm -f "$BUILD_TAR"

  # 前置校验（与 deploy-backend-stag.sh 服务器段一致）
  docker network inspect "$NETWORK" >/dev/null 2>&1 || deny "docker 网络 $NETWORK 不存在"
  docker ps --format '{{.Names}}' | grep -qx petgo-postgres || deny "petgo-postgres 容器未运行"
  docker ps --format '{{.Names}}' | grep -qx redis || deny "共享 redis 容器未运行"
  [[ -f "$ENV_FILE" ]] || deny "$ENV_FILE 缺失"
  grep -q '^DB_NAME=petgo_stag$' "$ENV_FILE" || deny "env 的 DB_NAME 不是 petgo_stag，拒绝部署（防误连生产库）"
  docker exec petgo-postgres psql -U petgo -lqt | cut -d'|' -f1 | grep -qw petgo_stag || deny "petgo_stag 库不存在"

  # 备份当前 staging 镜像（仅 :stag-previous，绝不碰生产 :latest/:previous）
  docker tag "$IMAGE:$TAG" "$IMAGE:$TAG-previous" 2>/dev/null || true
  docker build -f Dockerfile.deploy -t "$IMAGE:$TAG" -t "$IMAGE:$TAG-$ts" .

  docker stop "$CONTAINER" 2>/dev/null || true
  docker rm "$CONTAINER" 2>/dev/null || true
  mkdir -p "$REMOTE_DIR/logs"
  docker run -d \
    --name "$CONTAINER" \
    --network "$NETWORK" \
    -p "127.0.0.1:$HOST_PORT:8080" \
    --restart unless-stopped \
    --env-file "$ENV_FILE" \
    -v "$REMOTE_DIR/logs:/app/logs" \
    "$IMAGE:$TAG"

  echo "等待 actuator/health..."
  for i in $(seq 1 18); do
    sleep 5
    if curl -fs "http://127.0.0.1:$HOST_PORT/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      echo "✓ $CONTAINER UP"
      docker logs --tail 5 "$CONTAINER" 2>&1
      return 0
    fi
    echo "  ... $((i*5))s"
  done
  echo "ERROR: 90s 未 UP，最近日志:"
  docker logs --tail 60 "$CONTAINER" 2>&1
  exit 1
}

do_logs() {
  local n="${1:-100}"
  [[ "$n" =~ ^[0-9]+$ && "$n" -le 2000 ]] || deny "logs 行数须为 ≤2000 的数字"
  docker logs --tail "$n" "$CONTAINER" 2>&1
}

main() {
  local raw="${SSH_ORIGINAL_COMMAND:-}"
  # 只按空白切词，拒绝任何 shell 元字符（防注入）
  [[ "$raw" =~ ^[A-Za-z0-9._[:space:]-]*$ ]] || deny "命令含非法字符"
  read -r cmd arg _ <<< "$raw" || true
  case "${cmd:-}" in
    put-build) do_put_build ;;
    deploy)    do_deploy ;;
    logs)      do_logs "${arg:-100}" ;;
    health)    curl -fs "http://127.0.0.1:$HOST_PORT/actuator/health" && echo ;;
    ps)        docker ps --filter "name=$CONTAINER" --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' ;;
    restart)   docker restart "$CONTAINER" && echo "OK restart $CONTAINER" ;;
    *)         deny "仅允许: put-build | deploy | logs [N] | health | ps | restart" ;;
  esac
}

main
