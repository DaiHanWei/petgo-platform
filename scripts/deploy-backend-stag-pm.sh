#!/usr/bin/env bash
#
# TailTopia 后端 STAGING 部署脚本 —— PM 受限版
#
# 与 deploy-backend-stag.sh 的区别：服务器侧动作全部经由受限 SSH 入口
# （authorized_keys command= 绑定 ~/bin/stag-ops.sh），本机只做 mvn 构建和打包，
# 无论如何都碰不到生产容器/库/env。给非技术同事 + Claude 使用。
#
# 用法:
#   ./scripts/deploy-backend-stag-pm.sh          # mvn build → 上传 → 服务器重建 staging 容器
#
# 可选环境变量:
#   DEPLOY_HOST=hex@62.146.239.156   服务器（默认；hex=受限部署专用账号）
#   SKIP_BUILD=1                     跳过本地 mvn build，直接用现有 target/*.jar
#
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly BACKEND_DIR="$REPO_ROOT/petgo-backend"
readonly DEPLOY_HOST="${DEPLOY_HOST:-hex@62.146.239.156}"

log() { echo "[$(date +%H:%M:%S)] $*"; }
die() { echo "✗ $*" >&2; exit 1; }

branch="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
[[ "$branch" == "stag" ]] || die "当前分支 '$branch' 不是 stag。staging 只能从 stag 分支部署（本脚本无逃生门）"

cd "$BACKEND_DIR"
if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  command -v mvn >/dev/null 2>&1 && MVN=mvn || MVN=./mvnw
  log "本地构建 ($MVN -B package -DskipTests)"
  "$MVN" -B package -DskipTests -q
fi

jar="$(ls -t target/petgo-backend-*.jar 2>/dev/null | head -1)"
[[ -n "$jar" && -f "$jar" ]] || die "target/ 下找不到 petgo-backend-*.jar（先构建）"
log "使用 jar: $jar"

tarball="$(mktemp -t stag-pm-build).tar.gz"
tar -czf "$tarball" Dockerfile.deploy "$jar"

log "上传 build 包（受限通道）"
ssh "$DEPLOY_HOST" put-build < "$tarball"
rm -f "$tarball"

log "服务器端部署 staging 容器"
ssh "$DEPLOY_HOST" deploy

log "公网健康检查"
curl -fs https://api-stag.tailtopia.id/actuator/health && echo
echo "✓ staging 部署完成（生产服务不受影响）"
