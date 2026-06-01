#!/usr/bin/env bash
# PetGo SessionStart hook —— 本地 + 云端（claude.ai/code）通用。
# 职责：会话开始时，在 Flutter 工程已存在且 flutter 可用时，预拉前端依赖。
# 设计为完全容错：脚手架（story 1.1）尚未生成、或本机/云端未装 Flutter 时，静默跳过、绝不让会话启动失败。
set -u

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
app_dir="$repo_root/petgo_app"

# 1) Flutter 工程还没生成（story 1.1 之前）→ 跳过
if [ ! -f "$app_dir/pubspec.yaml" ]; then
  echo "[session-start] petgo_app/ 脚手架尚未生成，跳过 flutter pub get。"
  exit 0
fi

# 2) flutter 不在 PATH（本机未装 / 云端 setup script 未跑成功）→ 跳过（不阻断会话）
if ! command -v flutter >/dev/null 2>&1; then
  echo "[session-start] 未检测到 flutter，可执行；跳过 pub get（云端请确认 setup script 已装 Flutter SDK）。"
  exit 0
fi

# 3) 正常路径：拉依赖（失败也不阻断会话，仅告警）
echo "[session-start] flutter pub get @ petgo_app ..."
( cd "$app_dir" && flutter pub get ) || echo "[session-start] flutter pub get 失败（不阻断会话，dev 时手动排查）。"
exit 0
