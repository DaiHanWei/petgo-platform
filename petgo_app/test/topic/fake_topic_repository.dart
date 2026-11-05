import 'dart:async';

import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/domain/pinned_slot.dart';
import 'package:tailtopia/features/topic/data/topic_repository.dart';
import 'package:tailtopia/features/topic/domain/topic.dart';

/// 测试用话题仓库（V1.4.2 social-topic · Story 4.3）。每个接口可配返回值 / 异常 / 闸门，并记录调用。
class FakeTopicRepository implements TopicRepository {
  FakeTopicRepository({
    this.detail = const TopicDetail(name: 'kucinglucu', displayName: 'KucingLucu'),
    Map<TopicSort, List<FeedPage>>? pages,
    this.related = const [],
    this.pinned,
    this.homeRow = const [],
  }) : pages = pages ?? {TopicSort.latest: [FeedPage(items: [feedItem(1)])], TopicSort.popular: [FeedPage(items: [feedItem(2)])]};

  TopicDetail detail;
  Object? detailError;
  Completer<void>? detailGate;

  /// 每个排序的分页序列（第 n 次取该排序 = 第 n 页；越界返回空页）。
  Map<TopicSort, List<FeedPage>> pages;
  final Map<TopicSort, Object?> postsError = {};
  Completer<void>? postsGate;

  /// 某排序第几次调用（从 0 起）失败一次（翻页失败用）。
  final Map<TopicSort, int> failOnCall = {};

  List<TopicSummary> related;
  bool relatedFails = false;

  PinnedSlot? pinned;
  bool pinnedFails = false;

  List<TopicSummary> homeRow;
  bool homeRowFails = false;

  /// # 联想（Story 4.4）：按关键词给结果（键 null = 无 q 的热门）；缺键 → [suggestDefault]。
  final Map<String?, List<TopicSummary>> suggestions = {};
  List<TopicSummary> suggestDefault = const [];
  Object? suggestError;

  /// 某个关键词的请求挂起，直到闸门放行（测「新请求丢弃旧结果」）。
  final Map<String?, Completer<void>> suggestGates = {};

  /// 每次 suggest 调用的 q（null = 不带 q）。
  final List<String?> suggestCalls = [];

  int detailCalls = 0;
  int pinnedCalls = 0;
  int relatedCalls = 0;
  int homeRowCalls = 0;
  final List<(TopicSort, String?, int?)> postsCalls = [];

  int _callsFor(TopicSort s) => postsCalls.where((c) => c.$1 == s).length;

  @override
  Future<TopicDetail> getDetail(String name) async {
    detailCalls++;
    if (detailGate != null) await detailGate!.future;
    if (detailError != null) throw detailError!;
    return detail;
  }

  @override
  Future<FeedPage> getPosts(String name, {required TopicSort sort, String? cursor, int? page}) async {
    final n = _callsFor(sort);
    postsCalls.add((sort, cursor, page));
    if (postsGate != null) await postsGate!.future;
    final err = postsError[sort];
    if (err != null) throw err;
    if (failOnCall[sort] == n) {
      failOnCall.remove(sort);
      throw Exception('boom');
    }
    // 首屏（无游标 / page<=1）从第 0 页起；续拉按游标 / 页码定位。
    final seq = pages[sort] ?? const [];
    final idx = sort == TopicSort.popular
        ? (page ?? 1) - 1
        : (cursor == null ? 0 : int.parse(cursor.substring(1)));
    if (idx < 0 || idx >= seq.length) return const FeedPage(items: []);
    return seq[idx];
  }

  @override
  Future<List<TopicSummary>> getRelated(String name) async {
    relatedCalls++;
    if (relatedFails) throw Exception('boom');
    return related;
  }

  @override
  Future<List<TopicSummary>> getHomeRow() async {
    homeRowCalls++;
    if (homeRowFails) throw Exception('boom');
    return homeRow;
  }

  @override
  Future<PinnedSlot?> getPinnedSlot(String name) async {
    pinnedCalls++;
    if (pinnedFails) throw Exception('boom');
    return pinned;
  }

  @override
  Future<List<TopicSummary>> suggest({String? q}) async {
    suggestCalls.add(q);
    final gate = suggestGates[q];
    if (gate != null) await gate.future;
    if (suggestError != null) throw suggestError!;
    return suggestions[q] ?? suggestDefault;
  }
}

/// 无图文字卡（不触发网络图片）。
FeedItem feedItem(int id, {int authorId = 7, String? body}) => FeedItem(
      id: id,
      authorId: authorId,
      authorDeleted: false,
      authorNickname: 'Author$authorId',
      type: 'DAILY',
      body: body ?? 'post $id',
      createdAt: DateTime.utc(2026, 11, 1),
    );

/// Terbaru 第 [index] 页的游标（与 [FakeTopicRepository.getPosts] 的解析约定一致：`c<下一页序号>`）。
String cursorFor(int nextIndex) => 'c$nextIndex';

List<TopicSummary> topics(int n, {Set<int> official = const {}, Set<int> featured = const {}}) => [
      for (var i = 1; i <= n; i++)
        TopicSummary(
          name: 'topik$i',
          displayName: 'Topik$i',
          official: official.contains(i),
          featured: featured.contains(i),
        ),
    ];
