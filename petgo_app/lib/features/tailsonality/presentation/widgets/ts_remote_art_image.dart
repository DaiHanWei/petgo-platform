import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../../data/ts_remote_art.dart';

/// Tailsonality 远程素材图（按需下载，见 [TsRemoteArt]）。角色卡（结果卡 / 分享卡 / Diary 缩略图）与配型卡共用。
///
/// 下载中与取不到都画 [placeholder]（不转圈、不报错）；图到了自动换上。
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
}

class _TsRemoteArtImageState extends State<TsRemoteArtImage> {
  Uint8List? _bytes;

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
    _bytes = TsRemoteArt.peek(name);
    if (_bytes != null) return;
    TsRemoteArt.load(name).then((bytes) {
      if (mounted && bytes != null && widget.name == name) setState(() => _bytes = bytes);
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
      errorBuilder: (context, _, _) => widget.placeholder(context),
    );
  }
}
