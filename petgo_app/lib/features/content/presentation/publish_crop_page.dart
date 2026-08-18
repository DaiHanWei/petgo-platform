import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../domain/feed_image_layout.dart';
import '../domain/image_crop.dart';

/// 裁剪页（V1.1.6 Story 3.5 · FR-71）。**只对超出容差区间的图出现**。
///
/// ## 🔴 按钮位置
/// **左上角 ✕ 退出**，前进动作（应用裁剪）由**底部主按钮**承担。
///
/// ⚠️ UI 稿屏 08 的图注写的是「右上角返回按钮」，那是**未同步的旧稿** ——
/// PRD 已于 2026-08-07 订正：右上角按 iOS / Material 惯例是**前进 / 确认**位，
/// 退出放那里会画出「左箭头放在右上角」这种方向与位置互相矛盾的控件（设计稿一度确实这么画了）。
///
/// ## 🛡 只有两档预设
/// 1:1 与 4:5，**刻意不给横图档**。超宽横图只能裁成这两档，属已知且接受的结果。
///
/// ## 批次锁定
/// [lockedPreset] 非空表示本批次已由**第一张需要裁剪的图**定下档位 ——
/// 此时用户仍可调整选区，但**不能换档**（同一批次里不允许一部分 1:1、一部分 4:5）。
class PublishCropPage extends StatefulWidget {
  const PublishCropPage({
    super.key,
    required this.bytes,
    required this.size,
    this.lockedPreset,
  });

  final Uint8List bytes;

  /// 原图宽高（调用方已读过文件头，不必在这里再读一次）。
  final ImageSize size;

  /// 本批次已锁定的档位；为空表示这是第一张需要裁剪的图。
  final CropPreset? lockedPreset;

  @override
  State<PublishCropPage> createState() => _PublishCropPageState();
}

class _PublishCropPageState extends State<PublishCropPage> {
  late CropPreset _preset = widget.lockedPreset ?? CropPreset.square;
  double _alignX = 0.5;
  double _alignY = 0.5;

  bool get _locked => widget.lockedPreset != null;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Column(
          children: [
            // 🔴 退出在**左上角**，且是 ✕（不是返回箭头）。
            Row(
              children: [
                IconButton(
                  key: const ValueKey('cropClose'),
                  icon: const Icon(Icons.close, color: Colors.white),
                  onPressed: () => Navigator.of(context).pop(),
                ),
                Expanded(
                  child: Text(
                    l10n.publishCropTitle,
                    style: const TextStyle(
                        color: Colors.white, fontSize: 16, fontWeight: FontWeight.w700),
                  ),
                ),
                // 右上角**刻意留空**：那是前进位，而本页的前进动作在底部。
                const SizedBox(width: 48),
              ],
            ),
            Expanded(
              child: Center(
                child: LayoutBuilder(
                  builder: (context, c) => _frame(c.maxWidth, c.maxHeight),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(
                  horizontal: AppSpacing.screenEdge, vertical: AppSpacing.md),
              child: Column(
                children: [
                  if (_locked)
                    Text(l10n.publishCropLocked,
                        style: const TextStyle(color: Colors.white70, fontSize: 12))
                  else
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        for (final p in CropPreset.values) _presetChip(p),
                      ],
                    ),
                  const SizedBox(height: AppSpacing.sm),
                  Text(l10n.publishCropHint,
                      style: const TextStyle(color: Colors.white54, fontSize: 11)),
                  const SizedBox(height: AppSpacing.md),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton(
                      key: const ValueKey('cropApply'),
                      style: FilledButton.styleFrom(backgroundColor: AppColors.mint),
                      onPressed: () => Navigator.of(context).pop(
                        CropChoice(preset: _preset, alignX: _alignX, alignY: _alignY),
                      ),
                      child: Text(l10n.publishCropApply),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _presetChip(CropPreset p) {
    final on = p == _preset;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xs),
      child: GestureDetector(
        key: ValueKey('cropPreset_${p.label}'),
        onTap: () => setState(() {
          _preset = p;
          // 换档位后可移动范围变了，选区回到居中，免得停在一个已经越界的位置上。
          _alignX = 0.5;
          _alignY = 0.5;
        }),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
          decoration: BoxDecoration(
            color: on ? AppColors.mint : Colors.white12,
            borderRadius: BorderRadius.circular(16),
          ),
          child: Text(p.label,
              style: TextStyle(
                  color: on ? AppColors.onAccent : Colors.white70,
                  fontSize: 13,
                  fontWeight: FontWeight.w700)),
        ),
      ),
    );
  }

  /// 固定比例取景框 + 拖动选区。
  ///
  /// 图片按 cover 铺满取景框，多出来的那个方向可以拖 —— 拖的就是最终保留哪一块。
  Widget _frame(double maxW, double maxH) {
    final aspect = _preset.aspect;
    var frameW = maxW;
    var frameH = frameW / aspect;
    if (frameH > maxH) {
      frameH = maxH;
      frameW = frameH * aspect;
    }

    final imgAspect = widget.size.ratio;
    double dispW;
    double dispH;
    if (imgAspect > aspect) {
      // 图比框宽 → 高度铺满，左右有富余，可横拖。
      dispH = frameH;
      dispW = frameH * imgAspect;
    } else {
      dispW = frameW;
      dispH = frameW / imgAspect;
    }
    final rangeX = dispW - frameW;
    final rangeY = dispH - frameH;

    return GestureDetector(
      onPanUpdate: (d) => setState(() {
        if (rangeX > 0) _alignX = (_alignX - d.delta.dx / rangeX).clamp(0.0, 1.0);
        if (rangeY > 0) _alignY = (_alignY - d.delta.dy / rangeY).clamp(0.0, 1.0);
      }),
      child: SizedBox(
        key: const ValueKey('cropFrame'),
        width: frameW,
        height: frameH,
        child: ClipRect(
          child: Stack(
            children: [
              Positioned(
                left: -rangeX * _alignX,
                top: -rangeY * _alignY,
                width: dispW,
                height: dispH,
                child: Image.memory(widget.bytes, fit: BoxFit.fill),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
