#!/usr/bin/env bash
#
# TailTopia staging 受限运维入口（安装于服务器专用账号 hex@62.146.239.156 的 /home/hex/bin/stag-ops.sh）
#
# 用途：非技术同事（PM）走独立系统账号 hex（有 docker 组、无 sudo、密码锁定），其唯一
# SSH key 在 authorized_keys 里用 command= 强制绑定本脚本——无论客户端请求执行什么，
# 落到服务器只能触发下面白名单动作，物理上碰不到生产资源
# （petgo-server / 8084 / petgo 库 / redis DB2 / dai 的 ~/.env.petgo）。
#
# 安装（见 docs/runbook-stag-pm.md §A）：
#   /home/hex/bin/stag-ops.sh (755, hex:hex) + /home/hex/.env.petgo-stag（从 dai 的复制）
#   /home/hex/.ssh/authorized_keys 仅一行：
#   command="/home/hex/bin/stag-ops.sh",no-port-forwarding,no-agent-forwarding,no-X11-forwarding,no-pty <PM公钥> stag-pm
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

  # ⚠️ 2026-08-20：先判「包能不能解开」，再判「缺哪个条目」。
  # 顺序反了会指错地方 —— 那天上传被截断，tar 读到断口前一个条目都列不出来，
  # 于是报「缺 Dockerfile.deploy」，让人跑去查打包脚本漏没漏文件，
  # 而真正的毛病是重传就能解决的传输截断。
  local listing
  if ! listing="$(tar -tzf "$BUILD_TAR" 2>/dev/null)"; then
    rm -f "$BUILD_TAR"
    deny "build 包解不开（gzip/tar 流损坏，多为上传截断）—— 重传即可，不用查打包脚本"
  fi

  # 恰好等于上限 ⇒ 极可能是被 head -c 削掉了尾巴（合法 gzip 前缀也能解出部分条目，
  # 所以这个判断必须独立做，不能指望上面那步报错）。
  if [[ "$(wc -c < "$BUILD_TAR")" -eq "$MAX_TAR_BYTES" ]]; then
    rm -f "$BUILD_TAR"
    deny "build 包达到 ${MAX_TAR_BYTES} 字节上限、已被截断 —— 包太大，联系 Dai 调 MAX_TAR_BYTES"
  fi

  grep -q '^Dockerfile\.deploy$' <<<"$listing" || { rm -f "$BUILD_TAR"; deny "build 包缺 Dockerfile.deploy（包能解开，是真的没这个文件）"; }
  grep -q '^target/petgo-backend-.*\.jar$' <<<"$listing" || { rm -f "$BUILD_TAR"; deny "build 包缺 target/petgo-backend-*.jar（包能解开，是真的没这个文件）"; }
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
