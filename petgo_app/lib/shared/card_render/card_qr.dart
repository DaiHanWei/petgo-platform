import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:qr_flutter/qr_flutter.dart';

/// 卡片上的二维码（AD-15 Rule 4 的四条硬要求）。
///
/// 🔴 **边长写的是导出像素**：本组件必须放在 [CardFrame] 的卡面里，
/// 那里的布局坐标系就是画布坐标系（1 单位 = 导出图 1 像素）。
/// 所以 [side] = 200 意味着**导出图里的二维码就是 200px**，
/// 与它在屏幕上被缩到多小无关。
///
/// 反过来说：**不要按预览尺寸给边长**。预览里这块可能只有 60 逻辑像素，
/// 按 60 出图就是 60px 的码 —— 扫不出来。
///
/// 四条硬要求的落点：
/// 1. 内容 = 该条内容的分享链接 → [data] 由调用方传，本组件不构造链接
/// 2. 四周留白 ≥ 4 个码元 → [quietZonePxFor]
/// 3. 导出边长 ≥ [minExportSide]（140px）→ 构造期 assert + 运行期 clamp
/// 4. 纯文字模板必须有白色底板 → [onWhitePlate]（默认开）
class CardQr extends StatelessWidget {
  const CardQr({
    super.key,
    required this.data,
    this.side = defaultSide,
    this.onWhitePlate = true,
  }) : assert(
          side >= minExportSide,
          '二维码导出边长不得小于 ${minExportSide}px，否则扫不出来（AD-15 Rule 4 第 3 条）',
        );

  /// 二维码内容。
  ///
  /// 🔴 必须是**该条内容的分享链接**，不是通用下载页（AD-15 Rule 4 第 1 条）——
  /// 扫码的人要落到他看到的那条内容上。
  final String data;

  /// 码本身的边长，**画布坐标 = 导出像素**。不含留白。
  final double side;

  /// 白色底板（AD-15 Rule 4 第 4 条）。
  ///
  /// 🔴 **纯文字模板必须开**：那套模板背景是紫色渐变，二维码直接压上去
  /// 会显著降低识别率。默认 `true`（宁可多一块白底，不要扫不出来）。
  final bool onWhitePlate;

  /// 导出分辨率下的最小边长（AD-15 Rule 4 第 3 条）。
  static const double minExportSide = 140;

  /// 四周留白的码元数下限（AD-15 Rule 4 第 2 条）。
  static const int quietZoneModules = 4;

  static const double defaultSide = 220;

  /// 计算留白像素时假定的**最少码元数**（QR version 1 = 21×21）。
  ///
  /// 为什么取"最少"：码元数越少，每个码元越宽，4 个码元所需的留白就越大。
  /// 按最少码元算出的留白，对任何实际版本都 ≥4 码元 —— 这是安全的那一边。
  /// （实际版本由 `QrVersions.auto` 按内容长度决定，编译期不可知。）
  static const int _fewestModules = 21;

  /// 给定码边长时，单侧留白应有的像素宽度。
  static double quietZonePxFor(double side) =>
      side * quietZoneModules / _fewestModules;

  /// 含留白的整体占位边长（排版时按这个留位置）。
  static double footprintFor(double side) => side + 2 * quietZonePxFor(side);

  @override
  Widget build(BuildContext context) {
    // release 下 assert 不生效，兜一层 clamp：宁可版式挤一点，也不出不可扫的码。
    final effectiveSide = math.max(side, minExportSide);
    final quietZone = quietZonePxFor(effectiveSide);

    return Container(
      width: footprintFor(effectiveSide),
      height: footprintFor(effectiveSide),
      padding: EdgeInsets.all(quietZone),
      decoration: onWhitePlate
          ? BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(quietZone),
            )
          : null,
      child: QrImageView(
        key: const ValueKey('cardQrImage'),
        data: data,
        version: QrVersions.auto,
        // 留白由外层 padding 显式给出（可测量、可断言），不用组件的默认 padding。
        padding: EdgeInsets.zero,
        backgroundColor: Colors.white,
      ),
    );
  }
}
