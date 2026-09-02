import 'dart:io';

import 'package:flutter/material.dart';

/// 图片全屏查看（黑底 + 双指缩放 + 点击关闭）。
///
/// 兽医侧上下文卡 / 待接单预览、用户侧会话病例摘要条、IM 聊天气泡、
/// 以及商品详情图区（R-1）共用。
///
/// [srcs] 每一项支持远端 http(s) 签名 URL（[Image.network]）与本地文件路径
/// （[Image.file]，如聊天刚发出的乐观上屏图），按前缀逐项自动择一。
///
/// 🔴 调用方要传**原图 URL**，不是列表里那张缩略图。商品图在列表与详情图区都走
/// OSS 缩略（`ShopImage` 按显示尺寸取图），把缩略图丢进来的话双指放大只会看到一团糊 ——
/// 而「看清楚」正是打开这个查看器的唯一理由。
Future<void> showImageGalleryFullScreen(
  BuildContext context, {
  required List<String> srcs,
  int initialIndex = 0,
}) {
  if (srcs.isEmpty) return Future<void>.value();
  return showDialog<void>(
    context: context,
    barrierColor: Colors.black87,
    builder: (ctx) => _FullScreenGallery(
      srcs: srcs,
      // 越界一律夹回合法范围：调用方传的下标来自各自的轮播状态，
      // 少一张图（加载失败被过滤掉）就会越界，不该因此崩在 PageController 里。
      initialIndex: initialIndex.clamp(0, srcs.length - 1),
    ),
  );
}

/// 单张图的全屏查看。[showImageGalleryFullScreen] 的单图快捷方式。
///
/// ⚠️ 保留独立入口只为不动既有四处调用方（兽医 / IM / 病例摘要）——
/// 实现只有一份，行为与多图版完全一致。
Future<void> showCaseImageFullScreen(BuildContext context, String src) =>
    showImageGalleryFullScreen(context, srcs: [src]);

class _FullScreenGallery extends StatefulWidget {
  const _FullScreenGallery({required this.srcs, required this.initialIndex});

  final List<String> srcs;
  final int initialIndex;

  @override
  State<_FullScreenGallery> createState() => _FullScreenGalleryState();
}

class _FullScreenGalleryState extends State<_FullScreenGallery> {
  late final PageController _controller =
      PageController(initialPage: widget.initialIndex);
  late int _index = widget.initialIndex;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final multi = widget.srcs.length > 1;
    return GestureDetector(
      // 点空白处关闭 —— 全屏看图没有别的可点，点哪儿都该是「看完了」。
      onTap: () => Navigator.of(context).pop(),
      child: Stack(
        children: [
          Positioned.fill(
            child: PageView.builder(
              controller: _controller,
              itemCount: widget.srcs.length,
              // 单图时禁掉滑动：可滑但滑不动会让人以为还有下一张没加载出来。
              physics: multi
                  ? const PageScrollPhysics()
                  : const NeverScrollableScrollPhysics(),
              onPageChanged: (i) => setState(() => _index = i),
              itemBuilder: (c, i) => InteractiveViewer(
                minScale: 1,
                maxScale: 4,
                // 🔴 contain：这里就是为了**看全貌**才打开的。
                //    列表与详情图区用 cover 裁切是另一回事（见 ShopImage.fit 的说明）。
                child: Center(child: _image(widget.srcs[i])),
              ),
            ),
          ),
          Positioned(
            top: 40,
            right: 16,
            child: IconButton(
              icon: const Icon(Icons.close, color: Colors.white, size: 28),
              onPressed: () => Navigator.of(context).pop(),
            ),
          ),
          if (multi)
            Positioned(
              left: 0,
              right: 0,
              bottom: 40,
              child: Center(
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
                  decoration: BoxDecoration(
                    color: Colors.black54,
                    borderRadius: BorderRadius.circular(11),
                  ),
                  child: Text('${_index + 1}/${widget.srcs.length}',
                      key: const ValueKey('imageViewerPageIndicator'),
                      style: const TextStyle(color: Colors.white, fontSize: 12)),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _image(String src) {
    const broken =
        Icon(Icons.broken_image_outlined, color: Colors.white54, size: 48);
    return src.startsWith('http')
        ? Image.network(src, fit: BoxFit.contain, errorBuilder: (_, _, _) => broken)
        : Image.file(File(src), fit: BoxFit.contain, errorBuilder: (_, _, _) => broken);
  }
}
