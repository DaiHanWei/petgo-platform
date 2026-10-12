/// 露出区域（V1.3.4 Story 2.3 · FR-125 / AD-8）的领域类型。
///
/// ## 只服务「超界图」
/// 进到露出区域页的只有比例落在 `kFeedRatioMin ~ kFeedRatioMax` 闭区间**之外**的图
/// （旧口径 `needsCrop == true`）；区间内的图不会进来，也就没有 offset。
///
/// ## offset 语义（与 2.2 `feedImageAlignment` 同一把尺子）
/// `0` / `0.5` / `1` = 白框贴**起始边**（宽图贴左 / 竖图贴上）/ 居中 / 贴**结束边**（贴右 / 贴下）。
/// 页面上白框左（上）边 = `range * offset`；展示端 `BoxFit.cover + Alignment(2o − 1)`
/// 截出的正是这一段。两端任何一端改公式，另一端必须同改。
///
/// 「露出区域」统一用 `exposure` 命名，与旧 `crop`（物理裁剪）区分 —— 原图完整保留，
/// 这里只决定首页展示哪一段。
library;

import 'dart:typed_data';

import '../../../core/analytics/analytics.dart';
import 'feed_image_layout.dart';
import 'image_crop.dart';

/// 交给露出区域页的一张图：原图字节 + 原图宽高（调用方已读过文件头）。
class ExposureImage {
  const ExposureImage({required this.bytes, required this.size});

  final Uint8List bytes;
  final ImageSize size;
}

/// 露出区域页「Lanjut」的整批结果；两个列表与传入 `images` **同序等长**。
class ExposureResult {
  const ExposureResult({required this.offsets, required this.adjusted});

  /// 每张图的露出位置，`0.0 ~ 1.0`；未调的为 [kExposureDefaultOffset]。
  final List<double> offsets;

  /// 每张图是否被用户拖动改变过 offset（拖回 0.5 仍为 true）。
  final List<bool> adjusted;
}

/// 默认露出位置：白框在可移动方向上居中。
const double kExposureDefaultOffset = 0.5;

/// 过宽（`ratio > kFeedRatioMax`）为宽图；否则（只会是过竖）为竖图。
bool exposureIsWide(ImageSize s) => s.ratio > kFeedRatioMax;

/// 白框（展示窗口）比例由越界方向唯一决定：过宽 1.78 / 过竖 0.75，不给选。
double exposureWindowAspect(ImageSize s) =>
    exposureIsWide(s) ? kFeedRatioMax : kFeedRatioMin;

/// 埋点用的窗口比例口径（2.4 的编排函数上报）。
String exposureAnalyticsRatio(ImageSize s) => exposureIsWide(s) ? '1x78' : '0x75';

/// 一张已过露出区域编排、可直接入发帖页的图（V1.3.4 Story 2.4）。
class PreparedImage {
  const PreparedImage({required this.bytes, this.cropOffset});

  /// `ImageProcessor` 的产物本身（与传入 [prepareBatchExposure] 的是同一个对象）。
  final Uint8List bytes;

  /// 露出位置；只有超界图有值，区间内 / 量不出尺寸的为 null（展示端居中）。
  final double? cropOffset;
}

/// 「问用户露出哪一段」—— 由调用方注入（实际实现是打开 `PublishExposurePage`）。
/// 返回 null = 用户 ✕ / 返回。
typedef ExposureAsk = Future<ExposureResult?> Function(List<ExposureImage> overflow);

/// 一批图片的露出区域编排（V1.3.4 Story 2.4 · FR-125 / AD-8）。
///
/// 🔴 处理后的字节原样放行，本函数不解码、不裁切、不重编码（PRD AC1 哈希一致）——
/// 只读文件头拿宽高（[imageSizeOf]），尺寸即 `ImageProcessor` 产物的真实尺寸
/// （处理时已按 EXIF 朝向落像素并清空 EXIF，文件头宽高就是展示宽高）。
///
/// - 无超界图（全部落在 `kFeedRatioMin ~ kFeedRatioMax` 闭区间内，或量不出尺寸）→
///   **不调 [ask]**、不报任何埋点，offset 全 null；
/// - 有超界图 → 整批超界图**一次**交给 [ask]（同页左右切换），顺序 = 选图顺序；
/// - [ask] 返回 null → 返回 null，调用方本次**一张都不加**（区间内的也不加）。
Future<List<PreparedImage>?> prepareBatchExposure(
  List<Uint8List> images, {
  required ExposureAsk ask,
}) async {
  final sizes = [for (final b in images) imageSizeOf(b)];
  final overflowIdx = <int>[
    for (var i = 0; i < images.length; i++)
      if (sizes[i] != null && needsCrop(sizes[i]!.w, sizes[i]!.h)) i,
  ];
  if (overflowIdx.isEmpty) {
    return [for (final b in images) PreparedImage(bytes: b)];
  }

  // 埋点 E-8：每张超界图一条，打开露出区域页**之前**上报；batch_size 含区间内的图。
  for (final i in overflowIdx) {
    final s = sizes[i]!;
    Analytics.capture('publish_image_crop_shown', {
      'original_ratio': double.parse((s.w / s.h).toStringAsFixed(3)),
      'batch_size': images.length,
    });
  }

  final r = await ask([
    for (final i in overflowIdx) ExposureImage(bytes: images[i], size: sizes[i]!),
  ]);
  if (r == null) return null; // ✕ / 返回 → 本次整批取消，不报 completed

  // 埋点 E-9（V1.3.4 口径）：Lanjut 后每张超界图一条；target_ratio 由越界方向唯一决定。
  for (var k = 0; k < overflowIdx.length; k++) {
    Analytics.capture('publish_image_crop_completed', {
      'target_ratio': exposureAnalyticsRatio(sizes[overflowIdx[k]]!),
      'adjusted': k < r.adjusted.length && r.adjusted[k],
    });
  }

  final offsetAt = <int, double>{
    for (var k = 0; k < overflowIdx.length; k++)
      overflowIdx[k]: k < r.offsets.length
          ? r.offsets[k].clamp(0.0, 1.0).toDouble()
          : kExposureDefaultOffset,
  };
  return [
    for (var i = 0; i < images.length; i++)
      PreparedImage(bytes: images[i], cropOffset: offsetAt[i]),
  ];
}
