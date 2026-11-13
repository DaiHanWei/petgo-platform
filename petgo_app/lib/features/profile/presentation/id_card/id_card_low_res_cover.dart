import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';

import '../../../../core/theme/colors.dart';

/// 未解锁卡的预览降清倍数：有效分辨率 = 高清导出宽 ÷ 本值（V1.4.2「KTP 高清防绕过」§3）。
///
/// KTP 高清 1988 宽 ÷ 8 ≈ 249 宽。验收原则：一眼看得出不是高清；大字（名字、证件号）仍可辨认。
/// 修复线 2026-11-13 产品看实机 1/4、1/5、1/6、1/8 四档对比后定 1/8（1/3、1/4 在手机屏上与高清几乎无差别）。
/// 免费分享导出的带水印图也用同一档（§3.2）。
const double kIdCardPreviewDownscale = 8;

/// 未解锁卡的**低清遮罩**：把下面的矢量卡面按 1/[kIdCardPreviewDownscale] 分辨率栅格化，
/// 再拉伸盖在原位 —— 屏幕上（以及系统截图里）看到的只是这张低清图。
///
/// ## 为什么是「盖一层」而不是「换掉」矢量卡面
/// 付费导出（`_exportHd`）截的是矢量卡面所在的内层 `RepaintBoundary`（[sourceKey]）。
/// 解锁成功后会**立刻**导出，那一刻页面可能还没随新状态重建 —— 若矢量卡面被换成了低清图，
/// 付费用户拿到的就是低清图。盖在上面则互不干扰：导出照旧截矢量子树，屏幕只看得到遮罩。
///
/// 用法：作为 [Stack] 兄弟，放在 [sourceKey] 那个 boundary **之后**、水印之前；
/// 同样必须在 [sourceKey] 子树之外（否则会把自己截进付费导出图）。
///
/// 卡面内容变化时（建卡页改信息 / 换照片）父级重建本组件即触发重新栅格化（轻微防抖）；
/// 第一张图出来前先盖一块不透明底色，不让高清卡面露出来哪怕一帧。
class IdCardLowResCover extends StatefulWidget {
  const IdCardLowResCover({
    super.key,
    required this.sourceKey,
    required this.canvas,
    required this.canvasRadius,
    this.downscale = kIdCardPreviewDownscale,
  });

  /// 矢量卡面所在 `RepaintBoundary` 的 key（即付费导出用的那个）。
  final GlobalKey sourceKey;

  /// 卡面画布尺寸（高清导出规格，如 1988×1200）。
  final Size canvas;

  /// 卡面圆角（画布坐标系）。
  final double canvasRadius;

  final double downscale;

  @override
  State<IdCardLowResCover> createState() => _IdCardLowResCoverState();
}

class _IdCardLowResCoverState extends State<IdCardLowResCover> {
  ui.Image? _image;
  Timer? _debounce;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _rasterize());
  }

  @override
  void didUpdateWidget(IdCardLowResCover oldWidget) {
    super.didUpdateWidget(oldWidget);
    // 父级重建 = 卡面可能变了（建卡页改信息、换照片、换卡款）。防抖后重拍一张。
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 120), _rasterize);
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _generation++;
    _image?.dispose();
    super.dispose();
  }

  Future<void> _rasterize() async {
    final gen = ++_generation;
    await WidgetsBinding.instance.endOfFrame;
    if (!mounted || gen != _generation) return;
    final ctx = widget.sourceKey.currentContext;
    if (ctx == null || !ctx.mounted) return;
    // 先等卡面里的图片（宠物照片、卡面素材）解码完，否则拍下来的是空位。
    final pending = <Future<void>>[];
    void visit(Element el) {
      final w = el.widget;
      if (w is Image) pending.add(precacheImage(w.image, el, onError: (_, _) {}));
      el.visitChildren(visit);
    }

    ctx.visitChildElements(visit);
    await Future.wait(pending);
    if (!mounted || gen != _generation) return;
    await WidgetsBinding.instance.endOfFrame;
    if (!mounted || gen != _generation) return;
    final boundary = widget.sourceKey.currentContext?.findRenderObject() as RenderRepaintBoundary?;
    if (boundary == null || !boundary.hasSize || boundary.size.width <= 0) return;
    ui.Image shot;
    try {
      shot = await boundary.toImage(
          pixelRatio: (widget.canvas.width / widget.downscale) / boundary.size.width);
    } catch (_) {
      return; // 正在重绘等偶发失败：保留上一张，下次重建再拍。
    }
    if (!mounted || gen != _generation) {
      shot.dispose();
      return;
    }
    setState(() {
      _image?.dispose();
      _image = shot;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Positioned.fill(
      child: IgnorePointer(
        child: LayoutBuilder(
          builder: (context, constraints) => ClipRRect(
            borderRadius:
                BorderRadius.circular(widget.canvasRadius * constraints.maxWidth / widget.canvas.width),
            child: _image == null
                ? const ColoredBox(key: ValueKey('idCardLowResPending'), color: AppColors.cream2)
                : RawImage(
                    key: const ValueKey('idCardLowResImage'),
                    image: _image,
                    fit: BoxFit.fill,
                    // 低质量放大：像素被拉伸后自然发虚，正是要的效果。
                    filterQuality: FilterQuality.low,
                  ),
          ),
        ),
      ),
    );
  }
}
