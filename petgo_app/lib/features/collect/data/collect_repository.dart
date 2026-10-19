import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/collected_item.dart';

/// 收藏 / 取消收藏结果：`{collected}`。不带计数（场所收藏数不展示；帖子收藏数只在作者看详情时下发）。
class CollectResult {
  const CollectResult({required this.collected});

  final bool collected;

  factory CollectResult.fromJson(Map<String, dynamic>? json) => CollectResult(collected: json?['collected'] == true);
}

/// 列表一页（Story 2.4）。`nextCursor` 非空即有下一页。
typedef CollectionPageData<T> = ({List<T> items, String? nextCursor});

/// 收藏数据层（V1.4.0 ④ Story 2.3 建，Story 2.4 在同一处追加列表方法）。需 JWT；游客由 `requireLogin` 先拦。
abstract class CollectRepository {
  Future<CollectResult> collectPost(int postId);

  Future<CollectResult> uncollectPost(int postId);

  Future<CollectResult> collectPlace(String placeToken);

  Future<CollectResult> uncollectPlace(String placeToken);

  /// Story 2.4：我的帖子收藏（按收藏时间倒序，后端排好，客户端不重排）。
  Future<CollectionPageData<CollectedPost>> fetchCollectedPosts({String? cursor});

  /// Story 2.4：我的场所收藏（MERGED 已按保留场所去重）。
  Future<CollectionPageData<CollectedPlace>> fetchCollectedPlaces({String? cursor});
}

class DioCollectRepository implements CollectRepository {
  DioCollectRepository(this.dio);

  final Dio dio;

  @override
  Future<CollectResult> collectPost(int postId) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.contentPostCollect(postId));
    return CollectResult.fromJson(resp.data);
  }

  @override
  Future<CollectResult> uncollectPost(int postId) async {
    final resp = await dio.delete<Map<String, dynamic>>(ApiPaths.contentPostCollect(postId));
    return CollectResult.fromJson(resp.data);
  }

  @override
  Future<CollectResult> collectPlace(String placeToken) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.placeCollect(placeToken));
    return CollectResult.fromJson(resp.data);
  }

  @override
  Future<CollectResult> uncollectPlace(String placeToken) async {
    final resp = await dio.delete<Map<String, dynamic>>(ApiPaths.placeCollect(placeToken));
    return CollectResult.fromJson(resp.data);
  }

  @override
  Future<CollectionPageData<CollectedPost>> fetchCollectedPosts({String? cursor}) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meCollectionPosts,
        queryParameters: {'cursor': ?cursor});
    return _page(resp.data, CollectedPost.tryFromJson);
  }

  @override
  Future<CollectionPageData<CollectedPlace>> fetchCollectedPlaces({String? cursor}) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meCollectionPlaces,
        queryParameters: {'cursor': ?cursor});
    return _page(resp.data, CollectedPlace.tryFromJson);
  }

  /// 宽进：单行坏数据（缺 postId / placeToken 等）只跳过该行，不让整页失败。
  static CollectionPageData<T> _page<T>(Map<String, dynamic>? json, T? Function(Map<String, dynamic>) parse) {
    final raw = json?['items'];
    final items = <T>[];
    if (raw is List) {
      for (final e in raw) {
        if (e is! Map<String, dynamic>) continue;
        final T? row;
        try {
          row = parse(e);
        } catch (_) {
          continue;
        }
        if (row != null) items.add(row);
      }
    }
    return (items: items, nextCursor: json?['nextCursor'] as String?);
  }
}

final Provider<CollectRepository> collectRepositoryProvider =
    Provider<CollectRepository>((ref) => DioCollectRepository(ref.read(dioProvider)));
