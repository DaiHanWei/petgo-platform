import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/content/data/like_repository.dart';
import 'package:tailtopia/features/content/domain/post_like_state.dart';
import 'package:tailtopia/features/content/presentation/feed_controller.dart';

/// V1.5.0 likes Story 3.1 · AC7（FR-210，B-AC11 / AC13 ~ AC17）：同一帖在各处读同一个有效值。
///
/// 每个「位置」用 (snapLiked, snapCount, snapEpoch) 表示它手里的快照 —— 与 MasonryCard / 详情底栏传给
/// LikeButton 的三件套一致。
class _Repo implements LikeRepository {
  _Repo({this.fail = false});

  bool fail;
  int likeCalls = 0;
  int unlikeCalls = 0;
  int serverCount = 3;
  Completer<void>? gate;

  @override
  Future<LikeResult> like(int postId) async {
    likeCalls++;
    if (gate != null) await gate!.future;
    if (fail) throw Exception('network');
    serverCount++;
    return LikeResult(liked: true, likeCount: serverCount);
  }

  @override
  Future<LikeResult> unlike(int postId) async {
    unlikeCalls++;
    if (gate != null) await gate!.future;
    if (fail) throw Exception('network');
    serverCount--;
    return LikeResult(liked: false, likeCount: serverCount);
  }
}

const _track = LikeTracking(source: 'feed', trigger: 'button', feedTab: 'all');

void main() {
  late _Repo repo;
  late ProviderContainer c;
  final events = <(String, Map<String, Object>?)>[];

  setUp(() {
    repo = _Repo();
    c = ProviderContainer(overrides: [likeRepositoryProvider.overrideWithValue(repo)]);
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() {
    c.dispose();
    Analytics.debugCaptureSink = null;
  });

  PostLikeStore store() => c.read(postLikeStoreProvider.notifier);

  LikeView view(int postId, {bool liked = false, int count = 3, int epoch = 0}) =>
      store().effective(postId, snapLiked: liked, snapCount: count, snapEpoch: epoch);

  Future<LikeToggleOutcome> tap(int postId, LikeView current, {LikeTracking tracking = _track}) =>
      store().toggle(postId, current: current, wantLiked: !current.liked, tracking: tracking);

  test('1. 顶置卡与普通卡同一帖：点任一 → 两处同时变（B-AC11）', () async {
    final pinnedEpoch = store().currentEpoch;
    final normalEpoch = store().currentEpoch;
    final out = await tap(7, view(7, epoch: pinnedEpoch));
    expect(out.status, LikeToggleStatus.settled);
    expect(view(7, epoch: pinnedEpoch).liked, isTrue);
    expect(view(7, epoch: normalEpoch).liked, isTrue);
    expect(view(7, epoch: normalEpoch).count, 4);
  });

  test('2. 发现点赞 → 重建发现（新快照）仍已赞；关注流同帖读到已赞（B-AC15 / AC16）', () async {
    final discoverEpoch = store().currentEpoch;
    final followingEpoch = store().currentEpoch;
    await tap(7, view(7, epoch: discoverEpoch));
    // 重建：发请求前读版本号，后端返回已赞
    final rebuilt = store().currentEpoch;
    expect(view(7, liked: true, count: 4, epoch: rebuilt).liked, isTrue);
    expect(view(7, liked: true, count: 4, epoch: rebuilt).count, 4);
    // 关注流还拿着点赞前的快照
    expect(view(7, epoch: followingEpoch).liked, isTrue);
  });

  test('3. 话题页点赞 → 首页同帖已赞；反之亦然（B-AC17）', () async {
    final topicEpoch = store().currentEpoch;
    final homeEpoch = store().currentEpoch;
    await tap(8, view(8, epoch: topicEpoch));
    expect(view(8, epoch: homeEpoch).liked, isTrue);
    await tap(8, view(8, epoch: homeEpoch));
    expect(view(8, epoch: topicEpoch).liked, isFalse);
    expect(view(8, epoch: topicEpoch).count, 3);
  });

  test('4. 详情页点赞 / 取消 → Feed 卡同步（B-AC14）', () async {
    final feedEpoch = store().currentEpoch;
    final detailEpoch = store().currentEpoch;
    await tap(9, view(9, epoch: detailEpoch));
    expect(view(9, epoch: feedEpoch).liked, isTrue);
    await tap(9, view(9, epoch: detailEpoch));
    expect(view(9, epoch: feedEpoch).liked, isFalse);
    expect(repo.likeCalls, 1);
    expect(repo.unlikeCalls, 1);
  });

  test('5. 下拉刷新新快照以后端为准；飞行中刷新仍显示乐观值，落定后按后端（B-AC13）', () async {
    await tap(10, view(10, epoch: store().currentEpoch));
    // 刷新：别处取消了，后端说未赞 2 → 以新快照为准，不闪回
    final refreshed = store().currentEpoch;
    expect(view(10, liked: false, count: 2, epoch: refreshed).liked, isFalse);
    expect(view(10, liked: false, count: 2, epoch: refreshed).count, 2);

    // 飞行中
    repo.gate = Completer<void>();
    final pending = tap(11, view(11));
    final midRefresh = store().currentEpoch + 100; // 刷新快照版本号再新也不盖飞行中的乐观值
    expect(view(11, epoch: midRefresh).liked, isTrue);
    expect(view(11, epoch: midRefresh).inFlight, isTrue);
    repo.gate!.complete();
    await pending;
    expect(view(11, epoch: 0).liked, isTrue);
    expect(view(11, epoch: 0).inFlight, isFalse);
  });

  test('6. 失败回滚到点击前的有效值；飞行中再点不发第二次请求、不报事件', () async {
    repo.fail = true;
    final out = await tap(12, view(12));
    expect(out.status, LikeToggleStatus.failed);
    expect(view(12).liked, isFalse);
    expect(view(12).count, 3);

    repo.fail = false;
    repo.gate = Completer<void>();
    final first = tap(13, view(13));
    final second = await tap(13, view(13));
    expect(second.status, LikeToggleStatus.ignoredInFlight);
    repo.gate!.complete();
    await first;
    expect(repo.likeCalls, 2); // 12 一次 + 13 一次
    expect(events.where((e) => e.$1 == 'post_like_tapped'), hasLength(2));
  });

  test('已是目标态 → 不发请求、不报事件', () async {
    final out = await store().toggle(14,
        current: const LikeView(liked: true, count: 1), wantLiked: true, tracking: _track);
    expect(out.status, LikeToggleStatus.ignoredNoChange);
    expect(repo.likeCalls, 0);
    expect(events, isEmpty);
  });

  test('埋点：事件名不变，只加 trigger；可选属性缺省时不带键', () async {
    await tap(15, view(15),
        tracking: const LikeTracking(source: 'detail', trigger: 'button'));
    final e = events.singleWhere((e) => e.$1 == 'post_like_tapped');
    expect(e.$2, {'liked': true, 'source': 'detail', 'trigger': 'button'});
  });

  test('写回只写已建的流：没建过的 Feed 不为写回而创建', () async {
    await tap(16, view(16));
    expect(c.exists(feedProvider), isFalse);
  });

  test('详情拉到比覆盖新的快照 → 采纳为覆盖，旧快照位置一起跟上；飞行中 / 覆盖更新则不动', () async {
    final feedEpoch = store().currentEpoch;
    await tap(18, view(18, epoch: feedEpoch)); // 覆盖：已赞 4
    final detailEpoch = store().currentEpoch; // 详情进入时读
    store().adoptFresherSnapshot(18, liked: true, count: 10, snapEpoch: detailEpoch);
    expect(view(18, epoch: feedEpoch).count, 10);
    // 更旧的快照不得覆盖更新的覆盖
    store().adoptFresherSnapshot(18, liked: false, count: 1, snapEpoch: 0);
    expect(view(18, epoch: feedEpoch).count, 10);
  });

  test('7. 换账号（invalidate）后 store 为空；版本号不归零', () async {
    await tap(17, view(17));
    final before = store().currentEpoch;
    c.invalidate(postLikeStoreProvider);
    expect(c.read(postLikeStoreProvider), isEmpty);
    expect(store().currentEpoch, before);
  });
}
