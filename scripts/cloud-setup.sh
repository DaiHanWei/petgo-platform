#!/usr/bin/env bash
# =============================================================================
# PetGo —— 云端（claude.ai/code）环境 setup script【复制底稿】
# 把本文件内容【整段粘贴到 Web UI 的 Environment 设置 → Setup script 字段】。
# 入口：claude.ai/code 顶部「云图标（当前环境名）」→ 环境选择器 → Add environment / 齿轮 → Setup script。
#
# 装什么：
#   - Flutter 3.44.x（前端 petgo_app：analyze / test）
#   - Java 21：用云端【自带的 openjdk-21】，不下载、不改 JAVA_HOME。
#       原因：① 后端基线已是 Java 21；② 系统 openjdk-21 的信任库与系统 CA 同步（含云代理的 MITM CA），
#             避免 mvnw 连 Maven Central 时的 PKIX/证书报错（自己下的 Temurin 信任库不含代理 CA 会失败）。
#   - Maven：不装，用项目自带 ./mvnw（=3.9.16，走项目 .mvn/settings.xml 直连 Central）。
#
# 网络：Network access 选 "Trusted" 通常已含 github.com / pub.dev / storage.googleapis.com。
# 环境变量：本项目云端只做 L0（不连 DB/第三方），留空即可。
# =============================================================================
set -euo pipefail

FLUTTER_DIR="/opt/flutter"
JAVA21_DIR="/usr/lib/jvm/java-21-openjdk-amd64"

echo "==> [1/3] 基础工具（apt 尽力补齐，失败不致命）"
rm -f /etc/apt/sources.list.d/*deadsnakes* /etc/apt/sources.list.d/*ondrej* 2>/dev/null || true
apt-get update -y || true
apt-get install -y --no-install-recommends git curl ca-certificates || true
command -v git  >/dev/null || { echo "FATAL: git 不可用"; exit 1; }
command -v curl >/dev/null || { echo "FATAL: curl 不可用"; exit 1; }

echo "==> [2/3] Java 21（云端自带 openjdk-21；不下载、不改 JAVA_HOME）"
# 兜底：若 openjdk-21 缺失/被改过，重装原生 Debian 包（依赖 ca-certificates-java，信任库与系统同步）
if [ ! -x "$JAVA21_DIR/bin/javac" ] || [ -L "$JAVA21_DIR" ]; then
  rm -rf "$JAVA21_DIR" 2>/dev/null || true
  apt-get install -y openjdk-21-jdk-headless || true
fi
# 关键：把云代理的 CA 同步进 Java 信任库 → 根治 mvnw 的 PKIX/证书校验失败
update-ca-certificates -f >/dev/null 2>&1 || true
java -version 2>&1 | head -1 || true

echo "==> [3/3] Flutter"
if [ ! -d "$FLUTTER_DIR" ]; then
  git clone --depth 1 -b stable https://github.com/flutter/flutter.git "$FLUTTER_DIR"
fi
git config --global --add safe.directory "$FLUTTER_DIR"
ln -sf "$FLUTTER_DIR/bin/flutter" /usr/local/bin/flutter
ln -sf "$FLUTTER_DIR/bin/dart"    /usr/local/bin/dart
flutter --version || true
flutter config --no-analytics || true

echo "==> setup 完成：Java 21（系统自带）+ Flutter 3.44。后端用 ./mvnw。"
