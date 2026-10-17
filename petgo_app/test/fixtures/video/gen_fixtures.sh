#!/usr/bin/env bash
# 专题线 video Story 1.5：MP4 规格检查器的 L0 样片（每个 ≤ 1MB）。
#
# 重新生成：cd petgo_app/test/fixtures/video && bash gen_fixtures.sh
# 需要 ffmpeg（含 libx264 / libx265 / libmp3lame）。首次生成用的是 Homebrew ffmpeg（见下方 ffmpeg -version 输出）。
# 生成后跑 flutter test test/features/content/video/ 确认判定没变。
#
# 剪映 / CapCut 实际导出的样片（capcut_export.mp4）需要运营导出后手工放进来，本脚本不生成。
set -euo pipefail
cd "$(dirname "$0")"
ffmpeg -version | head -1

FF="ffmpeg -y -hide_banner -loglevel error"
# 低码率合成画面 + 正弦音，保证体积小。
src() { echo "-f lavfi -i testsrc2=size=$1:rate=15 -f lavfi -i sine=frequency=440:sample_rate=22050"; }
X264="-c:v libx264 -preset veryfast -b:v 60k -pix_fmt yuv420p"
AAC="-c:a aac -b:a 24k"
CLEAN="-map_metadata -1"

# shellcheck disable=SC2046
$FF $(src 720x1280) -t 4 $X264 $AAC $CLEAN -movflags +faststart ok_avc_720x1280.mp4
$FF -f lavfi -i testsrc2=size=720x1280:rate=15 -t 4 $X264 -an $CLEAN -movflags +faststart ok_no_audio.mp4
$FF $(src 1280x720) -t 4 $X264 $AAC $CLEAN -movflags +faststart ok_landscape_1280x720.mp4
# 编码 1280×720 + 显示旋转 90° → 显示方向 720×1280。
# ⚠️ -display_rotation 放在编码输入上时 ffmpeg 8 会自动转正（直接编出 720×1280、无矩阵）；
#    所以先编横屏，再用流复制（-c copy）只写旋转矩阵。
$FF $(src 1280x720) -t 4 $X264 $AAC $CLEAN -movflags +faststart _rot_src.mp4
$FF -display_rotation 90 -i _rot_src.mp4 -c copy $CLEAN -movflags +faststart ok_rotated.mp4
rm -f _rot_src.mp4
$FF $(src 720x1280) -t 4 -c:v libx265 -tag:v hvc1 -b:v 60k -x265-params log-level=error $AAC $CLEAN \
    -movflags +faststart hevc.mp4
$FF $(src 720x1280) -t 4 $X264 -vf "setparams=color_trc=smpte2084:color_primaries=bt2020:colorspace=bt2020nc" $AAC $CLEAN \
    -movflags +faststart+write_colr hdr_pq.mp4
$FF $(src 720x1280) -t 4 $X264 $AAC $CLEAN not_faststart.mp4
$FF $(src 720x1280) -t 4 $X264 $AAC -metadata location=+37.5090+127.0620/ -movflags +faststart location.mp4
$FF $(src 1080x1920) -t 3 $X264 $AAC $CLEAN -movflags +faststart too_big_1080x1920.mp4
$FF -f lavfi -i testsrc2=size=360x640:rate=2 -t 91 -c:v libx264 -preset veryfast -b:v 8k -pix_fmt yuv420p -an \
    $CLEAN -movflags +faststart too_long_91s.mp4
$FF $(src 720x1280) -t 2 $X264 $AAC $CLEAN -movflags +faststart too_short_2s.mp4
$FF $(src 720x1280) -t 4 $X264 -c:a libmp3lame -b:a 32k $CLEAN -movflags +faststart mp3_audio.mp4
$FF $(src 720x1280) -t 4 $X264 $AAC $CLEAN -movflags +faststart -f mov quicktime.mov
# 多项同时不合格：HEVC + 非 faststart。
$FF $(src 720x1280) -t 4 -c:v libx265 -tag:v hvc1 -b:v 60k -x265-params log-level=error $AAC $CLEAN \
    hevc_not_faststart.mp4
head -c 4096 /dev/urandom > garbage.bin

ls -l
