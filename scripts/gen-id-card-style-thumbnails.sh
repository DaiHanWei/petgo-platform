#!/usr/bin/env bash
# V1.3.4 ktp Story 1.5（D-2 / AD-8）：重新生成「Pilih Kartu」8 张样式缩略图。
# 用注册表 IdCardStyle 的卡面组件 + 固定示例数据渲染（widget test 截图），再转 webp 入库：
#   petgo_app/assets/id_card/styles/<snake>.webp
# 设计日后可直接同名替换图片，代码不改。跑完请逐张目检。
# 转码优先用 cwebp -q 90（brew install webp）；没有 cwebp 时回落 python3 + Pillow（quality=90）。
set -euo pipefail
cd "$(dirname "$0")/../petgo_app"

# 先清空上次产物：改名 / 删除的卡款不留下孤儿 PNG 被转进 assets（该目录整体打包进 App）。
rm -rf build/id_card_style_thumbs
GEN_ID_CARD_THUMBS=1 flutter test test/tool/id_card_style_thumbnails_test.dart

mkdir -p assets/id_card/styles
for png in build/id_card_style_thumbs/*.png; do
  name="$(basename "$png" .png)"
  out="assets/id_card/styles/${name}.webp"
  if command -v cwebp >/dev/null 2>&1; then
    cwebp -quiet -q 90 "$png" -o "$out"
  else
    python3 -c "import sys; from PIL import Image; Image.open(sys.argv[1]).save(sys.argv[2], 'WEBP', quality=90)" "$png" "$out"
  fi
  echo "→ $out"
done
