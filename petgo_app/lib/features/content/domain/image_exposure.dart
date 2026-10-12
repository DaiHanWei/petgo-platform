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

import 'feed_image_layout.dart';

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
