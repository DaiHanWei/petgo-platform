import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../../data/ts_role_art.dart';

/// 角色卡图（按需下载版，见 [TsRoleArt]）。结果卡卡面 / 分享卡 / Diary 测试条目缩略图共用。
///
/// 下载中与取不到都画 [placeholder]（不转圈、不报错）；图到了自动换上。
class TsRoleArtImage extends StatefulWidget {
  const TsRoleArtImage({
    super.key,
    required this.letters,
    required this.placeholder,
    this.fit = BoxFit.cover,
  });

  final String letters;
  final WidgetBuilder placeholder;
  final BoxFit fit;

  @override
  State<TsRoleArtImage> createState() => _TsRoleArtImageState();
}

class _TsRoleArtImageState extends State<TsRoleArtImage> {
  Uint8List? _bytes;

  @override
  void initState() {
    super.initState();
    _resolve();
  }

  @override
  void didUpdateWidget(TsRoleArtImage old) {
    super.didUpdateWidget(old);
    if (old.letters != widget.letters) _resolve();
  }

  void _resolve() {
    final letters = widget.letters;
    _bytes = TsRoleArt.peek(letters);
    if (_bytes != null) return;
    TsRoleArt.load(letters).then((bytes) {
      if (mounted && bytes != null && widget.letters == letters) setState(() => _bytes = bytes);
    });
  }

  @override
  Widget build(BuildContext context) {
    final bytes = _bytes;
    if (bytes == null) return widget.placeholder(context);
    return Image.memory(
      bytes,
      key: ValueKey('tsRoleArt_${widget.letters}'),
      fit: widget.fit,
      gaplessPlayback: true,
      errorBuilder: (context, _, _) => widget.placeholder(context),
    );
  }
}
