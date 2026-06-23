import 'package:flutter/material.dart';

/// 病例图全屏查看（黑底 + 双指缩放 + 点击关闭）。
///
/// 兽医侧上下文卡 / 待接单预览、用户侧会话病例摘要条共用（私密桶短 TTL 签名 URL）。
Future<void> showCaseImageFullScreen(BuildContext context, String url) {
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
                child: Image.network(
                  url,
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
