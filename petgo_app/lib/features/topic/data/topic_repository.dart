import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../content/domain/feed_item.dart';
import '../../content/domain/pinned_slot.dart';
import '../domain/topic.dart';

/// 话题读接口数据层（V1.4.2 social-topic · Story 4.3 · D-13 / D-20）。游客可调（后端 GET 全部 permitAll）。
///
/// - 404（不存在 / 禁用 / 非法名）→ [TopicNotFoundException]；其余 [DioException] 原样抛（UI 判 C11）。
/// - 🔴 **不自己处理 401**（拦截器负责）。
/// - 帖子列表复用 [FeedPage] / [FeedItem.fromJson]，不另写卡片模型。
abstract class TopicRepository {
  Future<TopicDetail> getDetail(String name);

  /// Terbaru 用 [cursor]、Populer 用 [page]（1 起，上限 50，超了后端 422）。
  Future<FeedPage> getPosts(String name, {required TopicSort sort, String? cursor, int? page});

  /// 空态推荐（≤10）。
  Future<List<TopicSummary>> getRelated(String name);

  /// Discover 首页话题行（≤5）。
  Future<List<TopicSummary>> getHomeRow();

  /// 话题坑位顶置：`GET /content-posts/pinned?topic=<标准名>`，无生效配置 → null。
  ///
  /// ⚠️ 不经 `FeedRepository.getPinnedSlot`（改它的签名要动 10 个实现类，story「待确认」第 3 条）；
  /// 解析复用同一个 [PinnedSlot.fromJson]。
  Future<PinnedSlot?> getPinnedSlot(String name);

  /// 发布页 # 联想（V1.4.2 Story 4.4 · C2）：`GET /topic-suggestions?q=`，**裸数组**、服务端顺序即官方在前。
  /// [q] 为空 / null → 不带 `q`（热门）。仅 role=USER（游客到不了发布页，不处理 401）。
  Future<List<TopicSummary>> suggest({String? q});
}

class DioTopicRepository implements TopicRepository {
  DioTopicRepository(this.dio);

  final Dio dio;

  /// 404 → [TopicNotFoundException]，其余原样。
  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) throw const TopicNotFoundException();
      rethrow;
    }
  }

  @override
  Future<TopicDetail> getDetail(String name) => _guard(() async {
        final resp = await dio.get<Map<String, dynamic>>(ApiPaths.topicDetail(name));
        return TopicDetail.fromJson(resp.data!);
      });

  @override
  Future<FeedPage> getPosts(String name, {required TopicSort sort, String? cursor, int? page}) =>
      _guard(() async {
        final resp = await dio.get<Map<String, dynamic>>(
          ApiPaths.topicPosts(name),
          queryParameters: {
            'sort': sort.wire,
            if (sort == TopicSort.latest) 'cursor': ?cursor,
            if (sort == TopicSort.popular) 'page': ?page,
          },
        );
        return FeedPage.fromJson(resp.data ?? const {});
      });

  @override
  Future<List<TopicSummary>> getRelated(String name) => _guard(() async {
        final resp = await dio.get<Object>(ApiPaths.topicRelated(name));
        return TopicSummary.listFromJson(resp.data);
      });

  @override
  Future<List<TopicSummary>> getHomeRow() async {
    final resp = await dio.get<Object>(ApiPaths.topicHomeRow);
    return TopicSummary.listFromJson(resp.data);
  }

  @override
  Future<PinnedSlot?> getPinnedSlot(String name) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.contentPinnedSlot,
      queryParameters: {'topic': name},
    );
    return PinnedSlot.fromJson(resp.data ?? const {});
  }

  @override
  Future<List<TopicSummary>> suggest({String? q}) async {
    final resp = await dio.get<Object>(
      ApiPaths.topicSuggestions,
      queryParameters: {if (q != null && q.isNotEmpty) 'q': q},
    );
    return TopicSummary.listFromJson(resp.data);
  }
}

final Provider<TopicRepository> topicRepositoryProvider =
    Provider<TopicRepository>((ref) => DioTopicRepository(ref.read(dioProvider)));
