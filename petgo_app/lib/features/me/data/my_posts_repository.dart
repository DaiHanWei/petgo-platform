import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 「我的发布」条目（对应后端 `GET /api/v1/me/posts` 的 FeedItemResponse 子集，Story 7.1）。
class MyPost {
  const MyPost({required this.id, required this.type, this.text, this.firstImageUrl});

  final int id;
  final String type;
  final String? text;
  final String? firstImageUrl;

  factory MyPost.fromJson(Map<String, dynamic> json) => MyPost(
        id: (json['id'] as num).toInt(),
        type: (json['type'] ?? '') as String,
        text: json['body'] as String?,
        firstImageUrl: json['firstImageUrl'] as String?,
      );
}

/// 「我的发布」数据层（Story 7.1，FR-36）：游标分页倒序三类混合内容。
///
/// AC6（F9 · R2）：顺序为后端 `GET /me/posts` 的**发布时间 `created_at` 倒序**（权威），前端**原样渲染、
/// 不客户端重排**；[MyPost] 刻意**不携带 `event_date`** —— 杜绝与成长档案（按事件日期 `event_date` 排序）
/// 混用为同一副本。同一条成长日历内容在「我的发布」按 created_at、在成长档案按 event_date，是两套口径。
class MyPostsRepository {
  MyPostsRepository({required this.dio});

  final Dio dio;

  Future<({List<MyPost> items, String? nextCursor, bool hasMore})> fetch({String? cursor}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.mePosts,
      queryParameters: {'cursor': ?cursor},
    );
    final data = resp.data!;
    final items = (data['items'] as List? ?? [])
        .map((e) => MyPost.fromJson((e as Map).cast<String, dynamic>()))
        .toList();
    return (items: items, nextCursor: data['nextCursor'] as String?, hasMore: (data['hasMore'] ?? false) as bool);
  }
}

final myPostsRepositoryProvider =
    Provider<MyPostsRepository>((ref) => MyPostsRepository(dio: ref.read(dioProvider)));

/// 首屏「我的发布」（autoDispose，进页拉一批）。
final myPostsProvider = FutureProvider.autoDispose<List<MyPost>>(
    (ref) async => (await ref.read(myPostsRepositoryProvider).fetch()).items);
