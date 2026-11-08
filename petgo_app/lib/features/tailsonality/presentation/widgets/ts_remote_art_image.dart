import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../../data/ts_remote_art.dart';

/// Tailsonality 远程素材图（按需下载，见 [TsRemoteArt]）。角色卡（结果卡 / 分享卡 / Diary 缩略图）与配型卡共用。
///
/// 下载中与取不到都画 [placeholder]（不转圈、不报错）；图到了自动换上。
///
/// 截图前须 [waitPainted]：字节到手 ≠ 已画上屏（`Image.memory` 异步解码，解码前什么都不画，截出来是白图）。
class TsRemoteArtImage extends StatefulWidget {
  const TsRemoteArtImage({
    super.key,
    required this.name,
    required this.placeholder,
    this.fit = BoxFit.cover,
  });

  /// [TsRemoteArt.role] / [TsRemoteArt.match] 给出的名字。
  final String name;
  final WidgetBuilder placeholder;
  final BoxFit fit;

  @override
  State<TsRemoteArtImage> createState() => _TsRemoteArtImageState();

  /// 等 [boundary] 子树里所有远程图真正画上屏（含下载 + 解码），超时或取不到图返回 false。
  ///
  /// 🔴 截卡前必调：只判 [TsRemoteArt.peek] 有字节不够 —— 解码完成前 `Image.memory` 一帧都不画，
  /// 此时 `toImage` 截到空图，再垫白底就成了纯白 PNG（线上白图帖的根因）。
  static Future<bool> waitPainted(BuildContext boundary, {Duration timeout = const Duration(seconds: 8)}) async {
    final states = <_TsRemoteArtImageState>[];
    void visit(Element e) {
      if (e is StatefulElement && e.state is _TsRemoteArtImageState) states.add(e.state as _TsRemoteArtImageState);
      e.visitChildElements(visit);
    }

    if (boundary is Element) boundary.visitChildElements(visit);
    try {
      final painted = await Future.wait(states.map((s) => s._painted.future)).timeout(timeout);
      if (painted.contains(false)) return false;
    } on TimeoutException {
      return false;
    }
    // 标记发生在 build 阶段，等这一帧画完再截。
    await WidgetsBinding.instance.endOfFrame;
    return true;
  }
}

class _TsRemoteArtImageState extends State<TsRemoteArtImage> {
  Uint8List? _bytes;

  /// 当前图第一帧已画上屏 → true；取不到 / 解码失败 → false。换图时重置。
  Completer<bool> _painted = Completer<bool>();

  void _settle(bool painted) {
    if (!_painted.isCompleted) _painted.complete(painted);
  }

  @override
  void initState() {
    super.initState();
    _resolve();
  }

  @override
  void didUpdateWidget(TsRemoteArtImage old) {
    super.didUpdateWidget(old);
    if (old.name != widget.name) _resolve();
  }

  void _resolve() {
    final name = widget.name;
    if (_painted.isCompleted) _painted = Completer<bool>();
    _bytes = TsRemoteArt.peek(name);
    if (_bytes != null) return;
    TsRemoteArt.load(name).then((bytes) {
      if (!mounted || widget.name != name) return;
      if (bytes == null) {
        _settle(false);
      } else {
        setState(() => _bytes = bytes);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final bytes = _bytes;
    if (bytes == null) return widget.placeholder(context);
    return Image.memory(
      bytes,
      key: ValueKey('tsRemoteArt_${widget.name}'),
      fit: widget.fit,
      gaplessPlayback: true,
      frameBuilder: (context, child, frame, _) {
        if (frame != null) _settle(true);
        return child;
      },
      errorBuilder: (context, _, _) {
        _settle(false);
        return widget.placeholder(context);
      },
    );
  }
}
