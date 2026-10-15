import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/feed_item.dart';

/// 按 id 取回**本人**的一条帖（Feed 卡片同形 DTO）。取不到 → null。
typedef OwnPostLookup = Future<FeedItem?> Function(int postId);

/// 兜底取数：`GET /me/posts` 首屏（created_at 倒序，刚发的帖必在其中），按 id 找。
///
/// 🛡 不往 `FeedRepository` 接口上加方法 —— 那会让一串测试 fake 都得补实现，
/// 而这条只在「定位兜底」一处用。测试直接 override 本 provider。
final Provider<OwnPostLookup> ownPostLookupProvider = Provider<OwnPostLookup>((ref) {
  return (postId) async {
    final resp = await ref.read(dioProvider).get<Map<String, dynamic>>(ApiPaths.mePosts);
    for (final raw in (resp.data?['items'] as List? ?? const [])) {
      if (raw is! Map) continue;
      final item = FeedItem.fromJson(raw.cast<String, dynamic>());
      if (item.id == postId) return item;
    }
    return null;
  };
});
