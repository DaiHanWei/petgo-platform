import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/collect/data/collect_repository.dart';
import 'package:tailtopia/features/collect/domain/collected_item.dart';

/// V1.4.0 ④ Story 2.4 测试共用：收藏仓库 fake（`extends Fake`：仓库加方法不打破既有测试）。
class FakeCollectRepo extends Fake implements CollectRepository {
  /// 每次 fetch 依次取一页；取空了返回空页。
  final List<CollectionPageData<CollectedPost>> postPages = [];
  final List<CollectionPageData<CollectedPlace>> placePages = [];
  final List<String?> postCursors = [];
  final List<String?> placeCursors = [];
  Object? fetchError;
  Object? loadMoreError;
  Object? uncollectError;
  final List<String> uncollects = [];

  int get postFetches => postCursors.length;
  int get placeFetches => placeCursors.length;

  @override
  Future<CollectionPageData<CollectedPost>> fetchCollectedPosts({String? cursor}) async {
    postCursors.add(cursor);
    if (fetchError != null && cursor == null) throw fetchError!;
    if (loadMoreError != null && cursor != null) throw loadMoreError!;
    return postPages.isEmpty ? (items: <CollectedPost>[], nextCursor: null) : postPages.removeAt(0);
  }

  @override
  Future<CollectionPageData<CollectedPlace>> fetchCollectedPlaces({String? cursor}) async {
    placeCursors.add(cursor);
    if (fetchError != null && cursor == null) throw fetchError!;
    if (loadMoreError != null && cursor != null) throw loadMoreError!;
    return placePages.isEmpty ? (items: <CollectedPlace>[], nextCursor: null) : placePages.removeAt(0);
  }

  @override
  Future<CollectResult> uncollectPost(int postId) async {
    uncollects.add('post:$postId');
    if (uncollectError != null) throw uncollectError!;
    return const CollectResult(collected: false);
  }

  @override
  Future<CollectResult> uncollectPlace(String placeToken) async {
    uncollects.add('place:$placeToken');
    if (uncollectError != null) throw uncollectError!;
    return const CollectResult(collected: false);
  }
}

DioException http404() {
  final req = RequestOptions(path: '/x');
  return DioException(requestOptions: req, response: Response(requestOptions: req, statusCode: 404));
}

CollectedPost visiblePost(int id, {String type = 'DAILY', String? excerpt, String nickname = 'Rina'}) =>
    CollectedPost.fromJson({
      'postId': id,
      'collectedAt': '2026-10-18T05:00:00Z',
      'available': true,
      'post': {
        'type': type,
        'excerpt': excerpt ?? 'excerpt $id',
        'author': {'nickname': nickname},
      },
    });

CollectedPost hiddenPost(int id) =>
    CollectedPost.fromJson({'postId': id, 'collectedAt': '2026-10-18T05:00:00Z', 'available': false});

CollectedPlace visiblePlace(String token, {String? type = 'CAFE', String? address = 'Jl. Test'}) =>
    CollectedPlace.fromJson({
      'placeToken': token,
      'collectedAt': '2026-10-18T05:00:00Z',
      'available': true,
      'name': 'Kopi $token',
      'place': {'type': type, 'addressText': address},
    });

CollectedPlace hiddenPlace(String token) => CollectedPlace.fromJson(
    {'placeToken': token, 'collectedAt': '2026-10-18T05:00:00Z', 'available': false, 'name': 'Gone $token'});
