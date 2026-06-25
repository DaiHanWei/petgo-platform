import 'dart:io';

import 'package:flutter/material.dart';

/// 图片全屏查看（黑底 + 双指缩放 + 点击关闭）。
///
/// 兽医侧上下文卡 / 待接单预览、用户侧会话病例摘要条、IM 聊天气泡共用。
/// [src] 支持远端 http(s) 签名 URL（[Image.network]）与本地文件路径（[Image.file]，
/// 如聊天刚发出的乐观上屏图），按前缀自动择一。
Future<void> showCaseImageFullScreen(BuildContext context, String src) {
  final bool remote = src.startsWith('http');
  return showDialog<void>(
    context: context,
    barrierColor: Colors.black87,
    builder: (ctx) => GestureDetector(
      onTap: () => Navigator.of(ctx).pop(),
      child: Stack(
        children: [
          Positioned.fill(
            child: InteractiveViewer(
              minScale: 1,
              maxScale: 4,
              child: Center(
                child: remote
                    ? Image.network(
                        src,
                        fit: BoxFit.contain,
                        errorBuilder: (_, _, _) =>
                            const Icon(Icons.broken_image_outlined, color: Colors.white54, size: 48),
                      )
                    : Image.file(
                        File(src),
                        fit: BoxFit.contain,
                        errorBuilder: (_, _, _) =>
                            const Icon(Icons.broken_image_outlined, color: Colors.white54, size: 48),
                      ),
              ),
            ),
          ),
          Positioned(
            top: 40,
            right: 16,
            child: IconButton(
              icon: const Icon(Icons.close, color: Colors.white, size: 28),
              onPressed: () => Navigator.of(ctx).pop(),
            ),
          ),
        ],
      ),
    ),
  );
}
