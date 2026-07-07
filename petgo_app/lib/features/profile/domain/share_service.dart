import 'dart:ui' show Rect;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:share_plus/share_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 系统分享函数（注入便于测试替身）。
/// [sharePositionOrigin]：iOS **必传**——iPad 上缺失会崩，iPhone 上缺失会导致
/// UIActivityViewController 定位异常/不弹（bug 20260707：成长档案分享按钮点了没反应）。由调用方按按钮 RenderBox 传入。
typedef ShareFn = Future<void> Function(String text, {Rect? sharePositionOrigin});

/// 调系统分享菜单（Story 2.7 · F2）。真机弹 WhatsApp/Instagram/复制链接等。
final Provider<ShareFn> shareServiceProvider = Provider<ShareFn>(
  (ref) => (text, {Rect? sharePositionOrigin}) async {
    await Share.share(text, sharePositionOrigin: sharePositionOrigin);
  },
);

const String kShareFabAnimatedShownKey = 'petgo.share_fab_animated_shown';

/// 分享 FAB 首访动效标记（Story 2.7 · F1）：true=已展示过（复访静态）。
final FutureProvider<bool> shareFabAnimatedShownProvider = FutureProvider<bool>((ref) async {
  final prefs = await SharedPreferences.getInstance();
  return prefs.getBool(kShareFabAnimatedShownKey) ?? false;
});

/// 持久化「动效已展示」（动效播完后调）。
Future<void> markShareFabAnimated() async {
  final prefs = await SharedPreferences.getInstance();
  await prefs.setBool(kShareFabAnimatedShownKey, true);
}
