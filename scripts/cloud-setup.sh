#!/usr/bin/env bash
# =============================================================================
# PetGo —— 云端（claude.ai/code）环境 setup script【参考模板】
# -----------------------------------------------------------------------------
# 这份文件【不是】自动生效的。它是给你【复制粘贴到 Web UI 的 Environment → Setup script】用的底稿。
# 云端 setup script 配置在网页端，不在仓库里；本文件入库只为版本化留底 + 团队对齐。
#
# 运行环境：Ubuntu 24.04，以 root 运行，每个环境【首次】会话执行一次后被缓存
#          （改本脚本 / 改网络设置 / 缓存约 7 天过期 → 重建）。必须在约 5 分钟内跑完。
#
# 装什么（双产物）：
#   - Flutter 3.44.x（前端 petgo_app）
#   - JDK 25 + Maven（后端 petgo-backend）
#
# 网络：Web UI 网络访问选 "Trusted" 通常已含 pub.dev / storage.googleapis.com；
#       若下列任何下载在首跑失败，多半是域名白名单——把网络切到 "Full" 或加 Custom 允许域名后重试。
# =============================================================================
set -euo pipefail

FLUTTER_CHANNEL="stable"     # VERIFY：需精确 3.44.x 时改为 checkout 具体 tag
INSTALL_ROOT="/opt"
PROFILE_D="/etc/profile.d/petgo-toolchain.sh"

echo "==> [1/4] 基础工具"
apt-get update -y
apt-get install -y --no-install-recommends git curl unzip xz-utils ca-certificates

echo "==> [2/4] Flutter（前端）"
# VERIFY：首跑确认 github.com / storage.googleapis.com 可达；precache 是耗时大头，超 5 分钟就去掉 --android 或精简。
if [ ! -d "$INSTALL_ROOT/flutter" ]; then
  git clone --depth 1 -b "$FLUTTER_CHANNEL" https://github.com/flutter/flutter.git "$INSTALL_ROOT/flutter"
fi
export PATH="$INSTALL_ROOT/flutter/bin:$PATH"
git config --global --add safe.directory "$INSTALL_ROOT/flutter"
flutter --version || true
flutter precache --android --no-ios   # 云端 headless：iOS 工具链无意义，只留 android/build 用
flutter config --no-analytics || true

echo "==> [3/4] JDK 25 + Maven（后端）"
# VERIFY：JDK 25 是前沿版本。下面用 Adoptium 直链装；首跑若 404/域名被挡：
#   方案A 换 api.adoptium.net 最新 25 资产链接；方案B 用 SDKMAN（需放行 get.sdkman.io / api.sdkman.io）。
JDK_TARBALL_URL="https://github.com/adoptium/temurin25-binaries/releases/latest/download/OpenJDK25U-jdk_x64_linux_hotspot.tar.gz"  # VERIFY 实际资产名
if [ ! -d "$INSTALL_ROOT/jdk-25" ]; then
  curl -fsSL "$JDK_TARBALL_URL" -o /tmp/jdk25.tar.gz
  mkdir -p "$INSTALL_ROOT/jdk-25"
  tar -xzf /tmp/jdk25.tar.gz -C "$INSTALL_ROOT/jdk-25" --strip-components=1
fi
export JAVA_HOME="$INSTALL_ROOT/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"
java -version || true

apt-get install -y --no-install-recommends maven   # Maven 3.9.x；JAVA_HOME 已指向 25，mvn 会用它
mvn -version || true

echo "==> [4/4] 持久化 PATH（让后续 agent shell 也认得 flutter/java/mvn）"
cat > "$PROFILE_D" <<EOF
export JAVA_HOME="$INSTALL_ROOT/jdk-25"
export PATH="$INSTALL_ROOT/flutter/bin:\$JAVA_HOME/bin:\$PATH"
EOF
# 非交互 shell 兜底：部分 agent shell 不读 profile.d，再补一份到 root bashrc
grep -q petgo-toolchain /root/.bashrc 2>/dev/null || echo "source $PROFILE_D" >> /root/.bashrc

echo "==> setup 完成。验证：flutter --version && java -version && mvn -version"
