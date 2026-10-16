/// 视频帖的跳转与来源（专题线 video Story 2.3 / 2.4 · AD-10）。
///
/// 列表点视频卡片 → `/content/:id?src=<source>`，`extra` 带 [VideoRouteExtra]：
/// 播放页据此跳过一次加载态，并把 `source` 带进所有播放埋点。图文帖的跳转**一字不变**。
library;

import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';

/// 进入播放页的来源（埋点 `source` 取值，AD-10）。
enum VideoSource {
  feed('feed'),
  category('category'),
  following('following'),
  profile('profile'),
  myPosts('my_posts'),
  pinned('pinned'),
  notification('notification'),
  push('push'),
  share('share'),
  other('other');

  const VideoSource(this.wire);

  /// 埋点 / query 参数 `src` 的取值。
  final String wire;

  /// `src` 参数 → 来源；缺省 / 未知 → [other]。
  static VideoSource parse(String? raw) =>
      VideoSource.values.firstWhere((s) => s.wire == raw, orElse: () => VideoSource.other);
}

/// 路由 `extra`：已知是视频帖时由列表带进来。
class VideoRouteExtra {
  const VideoRouteExtra({required this.source});

  final VideoSource source;
}

/// 详情路由位置。视频帖带 `src`，图文帖与改动前一致。
String contentDetailLocation(int id, {bool isVideo = false, VideoSource? source, bool focusComments = false}) {
  final params = <String>[
    if (focusComments) 'focus=comments',
    if (isVideo && source != null) 'src=${source.wire}',
  ];
  return params.isEmpty ? '/content/$id' : '/content/$id?${params.join('&')}';
}

/// 打开帖子详情：视频帖带 `src` 与 [VideoRouteExtra]，图文帖与改动前一致（`/content/:id`，无 extra）。
Future<void> pushContentDetail(BuildContext context, int id,
    {required bool isVideo, required VideoSource source, bool focusComments = false}) {
  final location = contentDetailLocation(id, isVideo: isVideo, source: source, focusComments: focusComments);
  return isVideo ? context.push(location, extra: VideoRouteExtra(source: source)) : context.push(location);
}
