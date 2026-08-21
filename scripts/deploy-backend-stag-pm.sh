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
# --no-xattrs：macOS bsdtar 默认把扩展属性（com.apple.provenance）写成 pax 头，
# 服务器端 GNU tar 读到会刷一行 "Ignoring unknown extended header keyword" 噪音，
# 掩盖真正的失败原因。包内容不需要 xattr，直接不写。
tar --no-xattrs -czf "$tarball" Dockerfile.deploy "$jar"

# 上传前先在本地验一次包完整 —— 否则本地就坏了的包传上去，服务器只会报
# 「缺 Dockerfile.deploy」，让人以为是打包漏文件（2026-08-20 实际踩过：
# 那次是**传输截断**，服务器读到断口前列不出条目，报错指向了错的地方）。
tar -tzf "$tarball" >/dev/null 2>&1 || die "本地 build 包损坏（tar -tzf 读不通），重试构建"
tarball_bytes="$(wc -c < "$tarball" | tr -d ' ')"

log "上传 build 包（受限通道，$((tarball_bytes / 1048576)) MB）"
# ⚠️ put-build 走 stdin，**传输截断时服务器侧看到的是一个合法但不完整的流**。
# 服务器会用「解不开 / 缺条目」拒掉，这里把它明确翻译成「上传失败，重试」，
# 别让下一个人去查打包脚本漏没漏文件。
if ! ssh "$DEPLOY_HOST" put-build < "$tarball"; then
  rm -f "$tarball"
  die "上传被服务器拒绝（多为传输截断；包本地已验证完整，$tarball_bytes 字节）。直接重试本脚本，或加 SKIP_BUILD=1 复用已构建的 jar"
fi
rm -f "$tarball"

log "服务器端部署 staging 容器"
ssh "$DEPLOY_HOST" deploy || die "服务器端部署失败，用 'ssh $DEPLOY_HOST logs 200' 看原因"

log "公网健康检查"
# ⚠️ 到这一步为止，上面每一环失败都已经 die 了（set -e + 显式判断）。
# 但**调用方**若把本脚本接进管道（`./deploy... | tail`），管道退出码取自最后一环、
# 不是本脚本 —— 失败会被读成成功。要判断成败请看下面这行 ✓，或直接看退出码
# （别用管道，或给调用方加 set -o pipefail）。
curl -fs https://api-stag.tailtopia.id/actuator/health && echo \
  || die "容器已重建但公网健康检查不通（Cloudflare Tunnel 或应用启动失败）：ssh $DEPLOY_HOST logs 200"
echo "✓ staging 部署完成（生产服务不受影响）"
