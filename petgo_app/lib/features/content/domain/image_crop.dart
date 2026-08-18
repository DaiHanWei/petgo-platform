/// 发布端的裁剪判定与几何（V1.1.6 Story 3.5 · FR-71）。
///
/// ## 🔴 容差区间只有一份
/// 判"要不要裁"用的就是展示端那对常量（[kFeedRatioMin] / [kFeedRatioMax]），**不另立一份**。
/// 两份一旦漂移，用户会遇到最费解的一类行为：**上传时被判定"不用裁"、展示时却被裁了**
/// （或反过来，白裁一次）。这类不一致没有任何错误信息，只会被当成玄学。
///
/// 闭区间口径也照搬：**只有越界才判需裁**，端点算区间内 —— 3:4 竖拍恰为 0.75 且最常见。
library;

import 'dart:typed_data';

import 'package:image/image.dart' as img;

import '../../../core/analytics/analytics.dart';
import 'feed_image_layout.dart';

/// 裁剪预设。**只有两档**。
///
/// 🛡 **刻意不提供横图预设**：上界放宽到 1.34 之后落进裁剪框的图已很少，
/// 再加第三档会让裁剪页交互变重。因此超宽横图（16:9、全景）只能裁成这两档 ——
/// 这是**已知且接受**的结果，不是缺陷。谁想"顺手补个 16:9"，先看这段。
enum CropPreset {
  square('1:1', 1.0),
  portrait('4:5', 4 / 5);

  const CropPreset(this.label, this.aspect);

  /// 界面上直接显示的档位文案（数字比例，不需要翻译）。
  final String label;

  /// 宽 ÷ 高。
  final double aspect;
}

/// 这张图是否需要裁剪。
///
/// 落在闭区间内 → **直接使用，不弹裁剪框、不做任何裁切**。
bool needsCrop(int width, int height) {
  if (width <= 0 || height <= 0) return false; // 量不出来的不打扰用户
  final ratio = width / height;
  return ratio < kFeedRatioMin || ratio > kFeedRatioMax;
}

/// 源图上的裁剪矩形（像素）。
class CropRect {
  const CropRect(this.x, this.y, this.width, this.height);

  final int x;
  final int y;
  final int width;
  final int height;

  @override
  String toString() => 'CropRect($x, $y, $width, $height)';
}

/// 算出源图上要保留的那块矩形。
///
/// [alignX] / [alignY] 是选区在**可移动范围**内的位置（0 = 贴左/贴上，1 = 贴右/贴下，
/// 0.5 = 居中）。只有被裁的那个方向上的对齐值起作用，另一个方向铺满、其对齐值无效。
///
/// 做成纯函数是为了**能用算术断言**：裁得对不对，靠数字比靠肉眼看截图可靠得多。
CropRect computeCropRect({
  required int width,
  required int height,
  required double targetAspect,
  double alignX = 0.5,
  double alignY = 0.5,
}) {
  final ratio = width / height;
  if (targetAspect > ratio) {
    // 目标比原图更宽 → 宽度铺满、**上下裁**。
    final h = (width / targetAspect).round().clamp(1, height);
    final y = ((height - h) * alignY.clamp(0.0, 1.0)).round();
    return CropRect(0, y, width, h);
  }
  if (targetAspect < ratio) {
    // 目标比原图更窄 → 高度铺满、**左右裁**。
    final w = (height * targetAspect).round().clamp(1, width);
    final x = ((width - w) * alignX.clamp(0.0, 1.0)).round();
    return CropRect(x, 0, w, height);
  }
  // 正好相等 → 一个像素都不裁。
  return CropRect(0, 0, width, height);
}

/// 只读图片头拿宽高，**不整张解码**。
///
/// 判定"要不要裁"要对每张选中的图都做一次，整张解码 9 遍会让选完图之后卡住好几秒。
/// 解码器的 `startDecode` 只读文件头，代价可以忽略。
ImageSize? imageSizeOf(Uint8List bytes) {
  try {
    final decoder = img.findDecoderForData(bytes);
    if (decoder == null) return null;
    final info = decoder.startDecode(bytes);
    if (info == null) return null;
    final size = ImageSize(info.width, info.height);
    return size.isUsable ? size : null;
  } catch (_) {
    return null;
  }
}

/// 按目标比例裁切并重编码为 JPEG。
///
/// ⚠️ 这里**是**整张解码（裁剪本来就要动像素），但只对真正需要裁的图做 ——
/// 按 FR 的预期，那应该是少数。
Uint8List cropToAspect(
  Uint8List input, {
  required double targetAspect,
  double alignX = 0.5,
  double alignY = 0.5,
  int quality = 90,
}) {
  // ⚠️ 解码器对非图片字节会**抛异常**（不是返回 null）—— 实测某些格式的探测器
  // 会直接下标越界。发布流程不能因为一张坏图整个崩掉，所以这里必须 try/catch。
  img.Image? decoded;
  try {
    decoded = img.decodeImage(input);
  } catch (_) {
    decoded = null;
  }
  if (decoded == null) return input; // 解不开就原样放行，不要把发布流程卡死
  final rect = computeCropRect(
    width: decoded.width,
    height: decoded.height,
    targetAspect: targetAspect,
    alignX: alignX,
    alignY: alignY,
  );
  final cropped = img.copyCrop(
    decoded,
    x: rect.x,
    y: rect.y,
    width: rect.width,
    height: rect.height,
  );
  // 裁剪后重编码：顺带保证元数据仍是空的（发布路径的隐私口径）。
  cropped.exif = img.ExifData();
  return Uint8List.fromList(img.encodeJpg(cropped, quality: quality));
}


/// 用户在裁剪页做出的选择。
class CropChoice {
  const CropChoice({required this.preset, required this.alignX, required this.alignY});

  final CropPreset preset;

  /// 选区在可移动范围内的位置（0 = 贴边，0.5 = 居中，1 = 贴另一边）。
  final double alignX;
  final double alignY;
}

/// 「问用户要怎么裁」——由调用方注入（实际实现是打开裁剪页）。
///
/// [locked] 非空表示本批次的档位已定，界面上不该再让用户换档。
/// 返回 null = 用户退出。
typedef CropAsk = Future<CropChoice?> Function(
    Uint8List bytes, ImageSize size, CropPreset? locked);

/// 一批图片的裁剪编排（V1.1.6 Story 3.5 · AC3）。
///
/// 把"谁要裁、档位怎么锁"这段逻辑与界面拆开，是为了**能用 L0 测**：
/// 批次锁定规则有两处特别容易做反，靠手点很难覆盖全。
///
/// ## 🛡 两处容易做反的地方
/// 1. **落在容差区间内的图完全不参与** —— 不进裁剪页、也不被锁定影响。
///    同一帖里图 1 保持 0.8、图 2 被裁成 1:1，是**符合规则的正确结果，不是 bug**。
/// 2. **全部落在区间内时，连"选比例"这一步都不该出现**。
///    比例锁定只在真正需要裁剪时才发生，不是每次上传都强制走一遍。
///
/// 返回 null 表示**用户中途退出**，本次上传应整体取消。
Future<List<Uint8List>?> applyBatchCrop(
  List<Uint8List> images, {
  required CropAsk ask,
}) async {
  final batchSize = images.length;
  CropPreset? locked;
  final out = <Uint8List>[];

  for (final bytes in images) {
    final size = imageSizeOf(bytes);

    // 量不出宽高、或落在容差区间内 → **原样放行**，一个像素都不动。
    if (size == null || !needsCrop(size.w, size.h)) {
      out.add(bytes);
      continue;
    }

    // 埋点 E-8：每张触发裁剪的图报一次 —— 这样 original_ratio 的分布才有意义。
    //
    // 🔴 这个分布是**判断容差区间 0.75~1.34 取值是否合适的唯一依据**，
    // 理想结果是这个事件本身**量很小**（说明绝大多数图都免裁走了主路径）。
    //
    // ⚠️ 事件名与 PRD 原文的 `publish_image_crop_required` 不同：本项目的埋点命名规范要求
    // **动作落在词尾**（`_shown` / `_tapped` 之类），好让产品一眼分得清曝光与点击；
    // `_required` 是状态不是动作，会被命名规范测试拦下。改为 `_shown` 语义也更准
    // （事件确实是在裁剪页出现的那一刻上报）。PRD §3.2 已同步订正。
    Analytics.capture('publish_image_crop_shown', {
      'original_ratio': double.parse((size.w / size.h).toStringAsFixed(3)),
      // 本次选图的**总张数**，含免裁的那些。
      'batch_size': batchSize,
    });

    final choice = await ask(bytes, size, locked);
    if (choice == null) return null; // 用户退出 → 取消本次上传

    // 第一张需要裁的定下档位，其余同样需要裁的沿用。
    locked ??= choice.preset;
    out.add(cropToAspect(
      bytes,
      targetAspect: locked.aspect,
      alignX: choice.alignX,
      alignY: choice.alignY,
    ));
  }
  return out;
}
