#!/usr/bin/env bash
# =============================================================================
# PetGo —— 云端（claude.ai/code）环境 setup script【参考模板 / 复制底稿】
# -----------------------------------------------------------------------------
# 这份文件【不自动生效】。把它的内容【粘贴到 Web UI 的 Environment 设置 → Setup script 字段】。
# 入口：claude.ai/code 顶部「云图标（当前环境名）」→ 环境选择器 → Add environment 或某环境右侧齿轮
#       → 同一对话框里有：Network access / 环境变量 / Setup script。
# 仓库这份只为版本化留底；云端 setup script 配在网页端，不读仓库。
#
# 运行环境：Ubuntu 24.04（x64），以 root 运行；每个环境【首次】会话执行一次后缓存
#          （改本脚本 / 改网络设置 / 缓存约 7 天过期 → 重建）。需在约 5 分钟内跑完。
#
# 装什么（双产物 L0 所需）：
#   - Flutter 3.44.x（前端 petgo_app：analyze / test）
#   - JDK 25（后端 petgo-backend：编译/打包）
#   - Maven 【不装】—— 用项目自带 ./mvnw（=Maven 3.9.16，公网 Central），apt 的 3.8.7 太老且会盖掉 JDK 25
#
# 网络：Network access 选 "Trusted" 通常已含 github.com / pub.dev / storage.googleapis.com / api.adoptium.net。
#       某步首跑 404/被挡 → 切 "Full" 或 Custom 放行对应域名后重试（改脚本会重建缓存）。
# 环境变量：本项目云端只做 L0（不连 DB/第三方），【无需】设任何 secret。
# =============================================================================
set -euo pipefail

INSTALL_ROOT="/opt"
FLUTTER_DIR="$INSTALL_ROOT/flutter"
JDK_DIR="$INSTALL_ROOT/jdk-25"
PROFILE_D="/etc/profile.d/petgo-toolchain.sh"

echo "==> [1/4] 基础工具（云基础镜像通常已带 git/curl/ca-certificates；apt 仅尽力补齐，失败不致命）"
# 云镜像预置的第三方 PPA（deadsnakes/ondrej 等）可能 403，与本工具链无关 → 移除，避免 apt update 失败
rm -f /etc/apt/sources.list.d/*deadsnakes* /etc/apt/sources.list.d/*ondrej* 2>/dev/null || true
apt-get update -y || true
apt-get install -y --no-install-recommends git curl ca-certificates tar gzip || true
# 兜底校验：Flutter 需 git、JDK 下载需 curl。缺则明确报错退出。
command -v git  >/dev/null || { echo "FATAL: git 不可用"; exit 1; }
command -v curl >/dev/null || { echo "FATAL: curl 不可用"; exit 1; }

echo "==> [2/4] Flutter（前端：analyze/test 用）"
# git clone --depth 1 取 stable（当前即 3.44.x）。不做 precache：analyze/test 不需要平台引擎产物，省时间。
if [ ! -d "$FLUTTER_DIR" ]; then
  git clone --depth 1 -b stable https://github.com/flutter/flutter.git "$FLUTTER_DIR"
fi
git config --global --add safe.directory "$FLUTTER_DIR"
ln -sf "$FLUTTER_DIR/bin/flutter" /usr/local/bin/flutter
ln -sf "$FLUTTER_DIR/bin/dart"    /usr/local/bin/dart
flutter --version || true          # 首次调用触发 Dart SDK 下载到 flutter cache
flutter config --no-analytics || true

echo "==> [3/4] JDK 25（后端：编译/打包用）"
# 直接从 github.com 下 Temurin 25.0.3（github 在 Trusted 白名单内；api.adoptium.net 不在 → 会 403）。
# 版本钉死保证可复现。VERIFY：若云 VM 为 arm64，把 x64→aarch64 并换对应资产名。
JDK_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9/OpenJDK25U-jdk_x64_linux_hotspot_25.0.3_9.tar.gz"
if [ ! -x "$JDK_DIR/bin/java" ]; then
  curl -fsSL "$JDK_URL" -o /tmp/jdk25.tar.gz
  mkdir -p "$JDK_DIR"
  tar -xzf /tmp/jdk25.tar.gz -C "$JDK_DIR" --strip-components=1
fi
ln -sf "$JDK_DIR/bin/java"  /usr/local/bin/java
ln -sf "$JDK_DIR/bin/javac" /usr/local/bin/javac
java -version || true

echo "==> [4/4] 持久化 JAVA_HOME（mvnw/maven 需要；PATH 已由 /usr/local/bin 软链兜底）"
cat > "$PROFILE_D" <<EOF
export JAVA_HOME="$JDK_DIR"
export PATH="$JDK_DIR/bin:$FLUTTER_DIR/bin:\$PATH"
EOF
grep -q petgo-toolchain /root/.bashrc 2>/dev/null || echo "source $PROFILE_D" >> /root/.bashrc
export JAVA_HOME="$JDK_DIR"

echo "==> setup 完成。"
echo "    验证：flutter --version && java -version && (cd petgo-backend && ./mvnw -v)"
echo "    注：后端用 ./mvnw（不是 mvn）；依赖走项目级 .mvn/settings.xml 直连 Central。"
