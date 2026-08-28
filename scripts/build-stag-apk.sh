#!/usr/bin/env bash
#
# TailTopia stag 内测包打包脚本（stag 分支专属）
#
# 🔴 **两个地址都要注入**（bug 20260828）：只传 API 地址、不传 H5 地址的话，
# 包连的是 staging、而卡片与二维码里的分享链接却指向**正式站** ——
# 那些 token 只存在于 staging 库里，正式站一律 404。
# 症状是"分享出去的链接谁点开都是空白"，而 App 内一切正常，从包里看不出任何异常。
#
# 版本边界（2026-08-06 决策）：pubspec.yaml 的 version 保持与其它分支一致（如 1.1.2+8，
# **不带 -stag**，合并零冲突面）；`-stag` 后缀由本脚本在构建时经 `--build-name` 注入
# （versionName=1.1.2-stag → PostHog $app_version 自动区分 stag 测试数据与生产数据）。
# ⚠️ stag 分支出包**必须**走本脚本，直接 flutter build 打出的包会丢 -stag 污染埋点口径。
#
# 用法:
#   ./scripts/build-stag-apk.sh           # release 模式 + debug 签名（内测包，真机 Google 登录可用）
#   ./scripts/build-stag-apk.sh debug     # debug 模式（连 stag，开发自测）
#
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly APP_DIR="$REPO_ROOT/petgo_app"
readonly MODE="${1:-release}"

die() { echo "✗ $*" >&2; exit 1; }
log() { echo "[$(date +%H:%M:%S)] $*"; }

branch="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
[[ "$branch" == "stag" ]] || die "当前分支 '$branch' 不是 stag。-stag 包只能从 stag 分支打"

# 解析 pubspec 版本（形如 1.1.2+8）；带前缀防止解析到别的行。
version_line="$(grep -E '^version:' "$APP_DIR/pubspec.yaml" | head -1 | awk '{print $2}')"
[[ -n "$version_line" ]] || die "pubspec.yaml 里找不到 version:"
build_name="${version_line%%+*}"
build_number="${version_line##*+}"
[[ "$build_name" != *-stag* ]] || die "pubspec version 不应带 -stag（后缀由本脚本注入），先还原成纯净版本号"
stag_name="${build_name}-stag"
log "版本: pubspec=$version_line → 包版本 $stag_name+$build_number ($MODE)"

cd "$APP_DIR"

if [[ "$MODE" == "debug" ]]; then
  flutter build apk --debug --build-name="$stag_name" --build-number="$build_number" \
    --dart-define=PETGO_API_BASE_URL=https://api-stag.tailtopia.id \
    --dart-define=PETGO_H5_BASE_URL=https://api-stag.tailtopia.id
  out="build/app/outputs/flutter-apk/app-debug.apk"
else
  # release 内测包：debug 签名（挪开 key.properties → gradle 回退 debug key，SHA-1 已注册
  # Google 登录可用）+ 指向 staging + 真登录。
  readonly KEYPROPS="$APP_DIR/android/key.properties"
  moved=0
  if [[ -f "$KEYPROPS" ]]; then
    mv "$KEYPROPS" "$KEYPROPS.stagbak"
    moved=1
  fi
  trap '[[ "$moved" == 1 ]] && mv -f "$KEYPROPS.stagbak" "$KEYPROPS" 2>/dev/null || true' EXIT
  flutter build apk --release \
    --build-name="$stag_name" --build-number="$build_number" \
    --dart-define=PETGO_API_BASE_URL=https://api-stag.tailtopia.id \
    --dart-define=PETGO_H5_BASE_URL=https://api-stag.tailtopia.id \
    --dart-define=PETGO_DEV_STUB_LOGIN=false \
    --dart-define=GOOGLE_SERVER_CLIENT_ID=952015467016-3q9vb0ro18fnecl9gpnrddbfj9snqer0.apps.googleusercontent.com
  out="build/app/outputs/flutter-apk/app-release.apk"
fi

# 产物命名：<版本号>-<时间戳 月日时分秒>.apk（如 1.1.2-stag+8-0806161045.apk）
dest="$HOME/Downloads/${stag_name}+${build_number}-$(date +%m%d%H%M%S).apk"
cp "$out" "$dest"
log "✓ 出包: $dest"
# 自检：包内 versionName 必须带 -stag（防脚本/参数回归）。
aapt="$(ls "$HOME"/Library/Android/sdk/build-tools/*/aapt2 2>/dev/null | tail -1 || true)"
if [[ -n "$aapt" ]]; then
  # pipefail 下 head/grep -q 提前关管道会让 aapt 吃 SIGPIPE 误报失败——先整段落变量再判断。
  badging="$("$aapt" dump badging "$dest" 2>/dev/null || true)"
  if grep -q "versionName='${stag_name}'" <<< "$badging"; then
    log "OK versionName=${stag_name} 校验通过"
  else
    die "包内 versionName 校验失败, 应为 ${stag_name}"
  fi
fi
