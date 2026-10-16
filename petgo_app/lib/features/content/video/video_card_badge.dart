import 'package:flutter/material.dart';

import 'video_spec_messages.dart';

/// 列表里视频帖封面上的叠加层（专题线 video Story 2.3 · FR-14）：居中播放角标 + 右下「分:秒」时长。
///
/// 只是叠加层：`IgnorePointer`，点击仍落到卡片本身；**不自动播放**（决策 V-18）。
class VideoCardBadge extends StatelessWidget {
  const VideoCardBadge({super.key, required this.durationMs, this.compact = false});

  final int durationMs;

  /// 网格小格（主页 / 我的发布）用小一号的角标。
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final iconSize = compact ? 28.0 : 40.0;
    return IgnorePointer(
      child: Stack(
        key: const ValueKey('videoCardBadge'),
        children: [
          Center(
            child: Container(
              width: iconSize,
              height: iconSize,
              decoration: const BoxDecoration(color: Color(0x66000000), shape: BoxShape.circle),
              child: Icon(Icons.play_arrow_rounded, color: Colors.white, size: iconSize * 0.7),
            ),
          ),
          Positioned(
            right: 6,
            bottom: 6,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
              decoration: BoxDecoration(
                color: const Color(0x8C000000),
                borderRadius: BorderRadius.circular(4),
              ),
              child: Text(
                formatVideoDuration(durationMs),
                key: const ValueKey('videoCardDuration'),
                style: TextStyle(
                  color: Colors.white,
                  fontSize: compact ? 10 : 11,
                  fontWeight: FontWeight.w600,
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
