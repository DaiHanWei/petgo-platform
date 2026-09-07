import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';

import 'card_canvas.dart';

/// 通用出图管线：**把给定 widget 出成指定画布尺寸的 PNG**。
///
/// 🔴 **它不认识"分享卡"这个概念**（AD-15 Rule 1c）——入参只有
/// 「截图区的 key」+「目标画布」，卡面长什么样完全由调用方决定。
/// V1.2.0 的 FR-65 宠物年龄换算卡直接复用本管线，
/// 所以这里**不能**出现任何 post / 分享 / 二维码相关的判断。
///
/// 做法整段照抄宠物身份证的 HD 导出链路（AD-15 Rule 1b），
/// 那条链路已在生产跑着，重写只会引入新 bug：
///
/// 1. `RepaintBoundary` + `toImage`
/// 2. 🔴 **按目标画布宽度反算截图倍率**（见 [capture]）
/// 3. **白底合成防黑角**（见 [capture] 内注释，bug 20260731-441）
/// 4. PNG 编码
///
/// 存相册 / 系统分享见 `card_export.dart`；水印与预览骨架见 `card_frame.dart`。
class CardRenderPipeline {
  CardRenderPipeline._();

  /// 把 [boundaryKey] 标记的子树出成 [canvas] 尺寸的 PNG 字节。
  ///
  /// 返回 `null` 只有两种情况：boundary 还没挂载、或尺寸为 0（都属于调用时机不对）。
  static Future<Uint8List?> capture({
    required GlobalKey boundaryKey,
    required CardCanvas canvas,
  }) async {
    final boundary = boundaryKey.currentContext?.findRenderObject() as RenderRepaintBoundary?;
    if (boundary == null) return null;
    if (boundary.size.width <= 0 || boundary.size.height <= 0) return null;

    // 🔴 **这一行是整个 story 的关键**（照抄 id_card_detail_page.dart:259）。
    // 屏幕上这张卡可能只有 300 逻辑像素宽，直接 toImage 出来就是 300px 的图 ——
    // 里头的二维码只剩几十像素，**扫不出来**。
    // 反算出倍率再截图，导出图才回到画布的真实分辨率，
    // 于是「按画布坐标排版」的二维码在导出图里自然就有足够边长。
    final pixelRatio = canvas.width / boundary.size.width;
    final ui.Image shot = await boundary.toImage(pixelRatio: pixelRatio);

    try {
      return await _composeOnWhite(shot);
    } finally {
      shot.dispose();
    }
  }

  /// 合成到白底再编码 PNG。
  ///
  /// ⚠️ **这一段别省**（bug 20260731-441，AD-15 Rule 1 点名）：卡面 `ClipRRect`
  /// 圆角外是 alpha=0 的透明像素。PNG 存透明本身没问题，但**相册深色主题 /
  /// IM 转发压缩会把透明平铺成黑角**，用户看到的就是四个黑角。
  static Future<Uint8List?> _composeOnWhite(ui.Image shot) async {
    final recorder = ui.PictureRecorder();
    final composeCanvas = Canvas(recorder);
    composeCanvas.drawRect(
      Rect.fromLTWH(0, 0, shot.width.toDouble(), shot.height.toDouble()),
      Paint()..color = const Color(0xFFFFFFFF),
    );
    composeCanvas.drawImage(shot, Offset.zero, Paint());
    final picture = recorder.endRecording();
    final ui.Image composed = await picture.toImage(shot.width, shot.height);
    try {
      final byteData = await composed.toByteData(format: ui.ImageByteFormat.png);
      return byteData?.buffer.asUint8List();
    } finally {
      composed.dispose();
      picture.dispose();
    }
  }
}
