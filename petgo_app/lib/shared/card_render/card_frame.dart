import 'package:flutter/widgets.dart';

import 'card_canvas.dart';

/// 卡片预览骨架：**按画布坐标排版，缩放显示，原分辨率导出**。
///
/// 🔴 这是整套基建"二维码不会扫不出来"的结构性保证：
/// 卡面内容在一个 `SizedBox(canvas.size)` 里排版，所以**布局里写的 1 单位
/// 就是导出图里的 1 像素**。二维码想要导出边长 ≥140px，直接写 140 即可 ——
/// 不需要在每个子组件里各算一遍倍率（散着算就一定会有人算错）。
/// 屏幕上的缩小由 `FittedBox` 负责，导出时由
/// [CardRenderPipeline.capture] 反算倍率还原。
///
/// 水印按 [CardWatermark] 的约定挂在 `RepaintBoundary` **外面**（兄弟节点）。
///
/// ⚠️ 本组件**不认识"分享卡"**：给它任何 widget 都能出图（AD-15 Rule 1c），
/// 也**不要求卡面有图片区** —— 纯文字帖的卡面同样能出（AD-15 Rule 3）。
class CardFrame extends StatelessWidget {
  const CardFrame({
    super.key,
    required this.boundaryKey,
    required this.canvas,
    required this.child,
    this.watermark,
  });

  /// 交给 [CardRenderPipeline.capture] 的截图区 key。
  final GlobalKey boundaryKey;

  final CardCanvas canvas;

  /// 卡面内容。按 `canvas.size` 坐标系排版。
  final Widget child;

  /// 可选水印层（[CardWatermark]）。挂在截图区外，故导出图不含它。
  final Widget? watermark;

  @override
  Widget build(BuildContext context) {
    return AspectRatio(
      aspectRatio: canvas.aspectRatio,
      child: Stack(
        // 紧约束：loose 会让 FittedBox 直接撑到画布原尺寸（1080 宽会溢出屏幕）。
        fit: StackFit.expand,
        children: [
          RepaintBoundary(
            key: boundaryKey,
            child: FittedBox(
              fit: BoxFit.contain,
              child: SizedBox(
                width: canvas.width,
                height: canvas.height,
                child: child,
              ),
            ),
          ),
          ?watermark,
        ],
      ),
    );
  }
}
