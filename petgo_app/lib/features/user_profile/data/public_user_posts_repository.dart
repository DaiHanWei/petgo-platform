import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 他人主页内容网格里的一条（对应后端 `FeedItemResponse` 的子集）。
/// V1.3.0 batch-b1 Story 2.2 · FR-118.2。
///
/// ⚠️ **刻意只取网格用得上的四个字段**。这个接口回的是完整的 `FeedItemResponse`
/// （作者投影、点赞数、已赞、评论数都有），但主页网格一格里只有封面 + 类型角标 ——
/// 把整个 Feed 模型搬过来，下一个人就会开始在网格上加点赞按钮，而那是详情页的事。
///
/// ⚠️ **没有 `visibility` 字段**：这个列表服务端只给 PUBLIC（NFR-2），
/// 「仅自己可见」角标在他人主页上永远不该出现。
class PublicUserPost {
  const PublicUserPost({required this.id, required this.type, this.firstImageUrl});

  final int id;

  /// 内容类型线格式：DAILY / GROWTH_MOMENT / KNOWLEDGE。
  final String type;

  final String? firstImageUrl;

  factory PublicUserPost.fromJson(Map<String, dynamic> json) => PublicUserPost(
        id: (json['id'] as num).toInt(),
        type: (json['type'] ?? '') as String,
        firstImageUrl: json['firstImageUrl'] as String?,
      );
}

/// 一页内容 + 游标（游标分页，不用 offset：新帖插入不会让翻页错位或重复）。
class PublicUserPostPage {
  const PublicUserPostPage({
    required this.items,
    required this.hasMore,
    this.nextCursor,
  });

  static const PublicUserPostPage empty =
      PublicUserPostPage(items: <PublicUserPost>[], hasMore: false);

  final List<PublicUserPost> items;
  final bool hasMore;
  final String? nextCursor;

  /// 追加下一页（「加载更多」只往后拼，不重拉已看过的部分）。
  PublicUserPostPage append(PublicUserPostPage next) => PublicUserPostPage(
        items: [...items, ...next.items],
        hasMore: next.hasMore,
        nextCursor: next.nextCursor,
      );

  factory PublicUserPostPage.fromJson(Map<String, dynamic> json) => PublicUserPostPage(
        items: (json['items'] as List? ?? const [])
            .map((e) => PublicUserPost.fromJson((e as Map).cast<String, dynamic>()))
            .toList(growable: false),
        hasMore: (json['hasMore'] ?? false) as bool,
        nextCursor: json['nextCursor'] as String?,
      );
}

/// 他人主页内容区数据层。只读、游客可调。
abstract class PublicUserPostsRepository {
  Future<PublicUserPostPage> fetch(int userId, {String? cursor});
}

class DioPublicUserPostsRepository implements PublicUserPostsRepository {
  DioPublicUserPostsRepository(this.dio);

  final Dio dio;

  @override
  Future<PublicUserPostPage> fetch(int userId, {String? cursor}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.userPublicPosts(userId),
      queryParameters: {'cursor': ?cursor},
    );
    return PublicUserPostPage.fromJson(resp.data!);
  }
}

final Provider<PublicUserPostsRepository> publicUserPostsRepositoryProvider =
    Provider<PublicUserPostsRepository>(
        (ref) => DioPublicUserPostsRepository(ref.read(dioProvider)));
