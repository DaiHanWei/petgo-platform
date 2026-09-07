import 'package:flutter/widgets.dart';

/// 一张卡的**导出画布**：出图的目标像素尺寸 + 卡面圆角。
///
/// 🔴 **画布是"预览"与"导出"共用的唯一尺寸源**。身份证那边踩过的坑是
/// 预览按一个尺寸、导出时另算一次（护照用了 KTP 的宽 ⇒ 导出尺寸错），
/// 所以这里把两者收进同一个值对象：预览用 [aspectRatio] 定形状，
/// 导出用 [width] 反算倍率，**结构上没有"算错另一个"的余地**。
///
/// ⚠️ 这个类**不认识"分享卡"**。它只是"一块多大的画布"，
/// 所以 V1.2.0 的宠物年龄换算卡（FR-65）能直接拿来用（AD-15 Rule 1c）。
@immutable
class CardCanvas {
  const CardCanvas({required this.size, this.radius = 0});

  /// 导出像素尺寸。卡面内容**按这个坐标系排版**，预览时整体缩小显示。
  final Size size;

  /// 卡面圆角（画布坐标系）。水印层按它对齐裁剪。
  final double radius;

  double get width => size.width;
  double get height => size.height;
  double get aspectRatio => size.width / size.height;

  /// 9:16 —— 竖屏 Story 版式。
  static const CardCanvas story = CardCanvas(size: Size(1080, 1920), radius: 48);

  /// 1:1 —— 方形版式。
  static const CardCanvas square = CardCanvas(size: Size(1080, 1080), radius: 48);

  @override
  bool operator ==(Object other) =>
      other is CardCanvas && other.size == size && other.radius == radius;

  @override
  int get hashCode => Object.hash(size, radius);

  @override
  String toString() => 'CardCanvas(${size.width.round()}×${size.height.round()})';
}
