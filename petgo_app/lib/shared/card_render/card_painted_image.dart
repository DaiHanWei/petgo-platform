import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

/// 卡面图片节点：**知道自己第一帧是否真正画上了屏**（V1.4.2 social-topic Story 3.1 · D-21）。
///
/// 截卡前须 [waitPainted]：`Image.asset` / `Image.network` / `Image.memory` 都是异步解码，
/// 解码完成前一帧都不画；此时 `toImage` 截到空图，`CardRenderPipeline` 再垫白底就成了纯白 PNG
/// （线上 Tailsonality 白图帖的根因，修复见 `TsRemoteArtImage.waitPainted`，本组件是它的通用版）。
///
/// - [image] 非空：`frameBuilder` 收到首帧 → 已画上屏；解码 / 加载失败 → 画 [fallback]，同样算已画上屏
///   （回落图有内容，如物种默认头像、紫渐变奖杯）；
/// - [image] 为空：直接画 [fallback]，已画上屏；
/// - [pending] 为 true：画 [fallback] 但**不算**就绪（如素材清单还没读完、不知道该画哪张）——
///   调用方随后换成真图 / 真回落时会挂上新节点，[waitPainted] 会重新收集。
///
/// ⚠️ 放在 `shared/card_render/`：与「分享卡」无关，任何走 `CardRenderPipeline` 的卡面都能用（AD-15 Rule 1c）。
class CardPaintedImage extends StatefulWidget {
  const CardPaintedImage({
    super.key,
    required this.image,
    required this.fallback,
    this.width,
    this.height,
    this.fit = BoxFit.cover,
    this.pending = false,
  });

  final ImageProvider? image;
  final WidgetBuilder fallback;
  final double? width;
  final double? height;
  final BoxFit fit;
  final bool pending;

  @override
  State<CardPaintedImage> createState() => _CardPaintedImageState();

  /// 等 [boundary] 子树里所有 [CardPaintedImage] 的第一帧真正画上屏，超时返回 false。
  ///
  /// 等待期间节点可能被替换（素材清单读完 → 由「未就绪回落」换成真图），故每轮等完后重新收集，
  /// 直到某一轮收集到的节点全部就绪；整体受 [timeout] 约束。[boundary] 已卸载 → false。
  static Future<bool> waitPainted(BuildContext boundary, {Duration timeout = const Duration(seconds: 8)}) async {
    final deadline = DateTime.now().add(timeout);
    while (true) {
      if (!boundary.mounted) return false;
      final states = <_CardPaintedImageState>[];
      void visit(Element e) {
        if (e is StatefulElement && e.state is _CardPaintedImageState) states.add(e.state as _CardPaintedImageState);
        e.visitChildElements(visit);
      }

      if (boundary is Element) boundary.visitChildElements(visit);
      final pending = states.where((s) => !s._isPainted).toList();
      if (pending.isEmpty) break;
      final left = deadline.difference(DateTime.now());
      if (left <= Duration.zero) return false;
      try {
        await Future.wait(pending.map((s) => s._painted.future)).timeout(left);
      } on TimeoutException {
        return false;
      }
      // 标记发生在 build 阶段，等这一帧画完再复查（可能有新挂上的节点）。
      if (!await endOfFrameWithin(deadline.difference(DateTime.now()))) return false;
    }
    // 标记发生在 build 阶段，等这一帧画完再截。
    if (!await endOfFrameWithin(deadline.difference(DateTime.now()))) return false;
    return boundary.mounted;
  }

  /// 测试缝：非 null 时 [endOfFrameWithin] 等它而不是真实的 `endOfFrame`（模拟 App 进后台、不再出帧）。
  @visibleForTesting
  static Future<void> Function()? endOfFrameForTest;

  /// 等下一帧画完，最多等 [left]：按时画完 → true；超时 / [left] 已用尽 → false。
  ///
  /// 🔴 截卡链路上的每个 `endOfFrame` 都必须走这里：App 进后台后引擎不再出帧，裸 `await endOfFrame`
  /// 会永远挂住，调用方的「出图中」状态也就永远不结束（复审 3-1 PLAUSIBLE-1）。
  static Future<bool> endOfFrameWithin(Duration left) async {
    if (left <= Duration.zero) return false;
    try {
      await (endOfFrameForTest?.call() ?? WidgetsBinding.instance.endOfFrame).timeout(left);
      return true;
    } on TimeoutException {
      return false;
    }
  }

  /// 测试缝：非 null 时 [precacheSvgAssets] 走它（`rootBundle` 读资源在 widget test 的假时钟里不完成）。
  @visibleForTesting
  static Future<void> Function(List<String> paths)? precacheSvgForTest;

  /// 截图前把卡面用到的 SVG 预加载进 flutter_svg 缓存并等它完成（`SvgPicture` 没有首帧回调，
  /// 只能先把字节解析好，挂载后再等一帧）。任一失败 → 抛出，调用方按「出图失败」处理。
  static Future<void> precacheSvgAssets(List<String> paths) async {
    final seam = precacheSvgForTest;
    if (seam != null) return seam(paths);
    await Future.wait([
      for (final p in paths)
        () {
          final loader = SvgAssetLoader(p);
          return svg.cache.putIfAbsent(loader.cacheKey(null), () => loader.loadBytes(null));
        }(),
    ]);
  }
}

class _CardPaintedImageState extends State<CardPaintedImage> {
  /// 当前图第一帧已画上屏（或已画回落图）→ complete(true)。换图时重置。
  Completer<bool> _painted = Completer<bool>();

  bool get _isPainted => _painted.isCompleted;

  void _settle() {
    if (!_painted.isCompleted) _painted.complete(true);
  }

  @override
  void didUpdateWidget(CardPaintedImage old) {
    super.didUpdateWidget(old);
    if (old.image != widget.image || old.pending != widget.pending) {
      if (_painted.isCompleted) _painted = Completer<bool>();
    }
  }

  @override
  void dispose() {
    // 被替换的节点不再阻塞等待方（waitPainted 会重新收集新节点）。
    _settle();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final image = widget.image;
    if (image == null || widget.pending) {
      if (!widget.pending) _settle();
      return widget.fallback(context);
    }
    return Image(
      image: image,
      width: widget.width,
      height: widget.height,
      fit: widget.fit,
      gaplessPlayback: true,
      frameBuilder: (context, child, frame, wasSynchronouslyLoaded) {
        if (frame != null || wasSynchronouslyLoaded) _settle();
        return child;
      },
      errorBuilder: (context, _, _) {
        _settle();
        return widget.fallback(context);
      },
    );
  }
}
