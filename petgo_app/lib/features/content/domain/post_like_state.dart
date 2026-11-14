import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../data/like_repository.dart';
import '../presentation/feed_controller.dart';

/// 某帖在本会话里被点过赞之后的覆盖值（V1.5.0 FR-210）。写入时打全局点赞版本号。
class PostLikeOverride {
  const PostLikeOverride({
    required this.liked,
    required this.count,
    required this.inFlight,
    required this.epoch,
    this.bounceTick = 0,
  });

  final bool liked;
  final int count;

  /// 点赞 / 取消请求还在路上（乐观值）。飞行中的覆盖不论 epoch 都优先，且不得再发第二次请求。
  final bool inFlight;

  /// 写入时的全局点赞版本号（见 [_likeEpochCounter]）。
  final int epoch;

  /// 爱心按钮弹跳信号（只增不减，V1.5.0 FR-211 / 4.1 / 4.2 用）。本 story 只保留、不递增。
  final int bounceTick;
}

/// 有效点赞态（快照或覆盖二选一后的结果）。
class LikeView {
  const LikeView({required this.liked, required this.count, this.inFlight = false});

  final bool liked;
  final int count;
  final bool inFlight;
}

/// [PostLikeStore.toggle] 的结果。
enum LikeToggleStatus {
  /// 上一次请求还在路上：不发请求、不报事件。
  ignoredInFlight,

  /// 已是目标态（如已赞再点赞）：不发请求、不报事件。
  ignoredNoChange,

  /// 后端真值落定。
  settled,

  /// 请求失败，已回滚到点击前的有效值。
  failed,
}

class LikeToggleOutcome {
  const LikeToggleOutcome(this.status, {this.liked, this.count});

  final LikeToggleStatus status;

  /// [LikeToggleStatus.settled] 时的后端真值；其余为 null。
  final bool? liked;
  final int? count;
}

/// `post_like_tapped` 的埋点上下文（事件名不变，V1.5.0 只加 `trigger`）。
class LikeTracking {
  const LikeTracking({
    required this.source,
    required this.trigger,
    this.feedTab,
    this.rankMode,
    this.reason,
  });

  /// `feed` / `detail`。
  final String source;

  /// `button`（爱心按钮）/ `double_tap`（Feed 图片区双击，V1.5.0 FR-209）。
  final String trigger;
  final String? feedTab;
  final String? rankMode;
  final String? reason;

  Map<String, Object> props(bool liked) => {
        'liked': liked,
        'source': source,
        // V1.1.6 Story 16.5：首页点赞是 FR-95 效果归因的主要信号 —— feed_tab 与 rank_mode 都要有。
        'feed_tab': ?feedTab,
        'rank_mode': ?rankMode,
        'reason': ?reason,
        'trigger': trigger,
      };
}

/// 全局点赞版本号：放在 notifier 外，store 重建（换账号 invalidate）不归零 ——
/// 归零会让新写的覆盖 epoch 小于已有快照记下的版本号而失效（同 `follow_state.dart` 的 `_followEpochCounter`）。
int _likeEpochCounter = 0;

/// 全局点赞状态（V1.5.0 FR-210）：**本次会话里点过赞的帖**的覆盖表，按 postId。
///
/// 各处爱心按钮 / 图片双击都读「帖子快照 + 覆盖（按版本号判断是否生效）」：同一帖在顶置卡、发现、关注、
/// 话题页、详情页读到同一个值。每个数据源**发请求前**读 [currentEpoch] 记进快照的 `likeEpoch`；
/// 重拉后的快照版本号 ≥ 覆盖 → 以后端为准（下拉刷新不闪回）；飞行中的覆盖不论版本号都优先。
///
/// 🔴 已赞态是账号维度：换账号由 `resetUserScopedCaches` invalidate 本 provider（登出不清，与该函数约定一致）。
/// 🔴 登录门控不在这里（要 `BuildContext` 弹窗）：调用方先 `requireLogin` 再调 [toggle]。
class PostLikeStore extends Notifier<Map<int, PostLikeOverride>> {
  @override
  Map<int, PostLikeOverride> build() => const <int, PostLikeOverride>{};

  /// 当前全局版本号。数据源发请求前读一次，写进快照的 `likeEpoch`。
  int get currentEpoch => _likeEpochCounter;

  /// 有效值：覆盖存在且（飞行中 或 `epoch > snapEpoch`）→ 用覆盖；否则用快照。
  LikeView effective(int postId, {required bool snapLiked, required int snapCount, required int snapEpoch}) =>
      resolve(state[postId], snapLiked: snapLiked, snapCount: snapCount, snapEpoch: snapEpoch);

  /// [effective] 的纯函数版（Widget 用 `select` 拿到单条覆盖后自己算）。
  static LikeView resolve(PostLikeOverride? o,
      {required bool snapLiked, required int snapCount, required int snapEpoch}) {
    if (o != null && (o.inFlight || o.epoch > snapEpoch)) {
      return LikeView(liked: o.liked, count: o.count, inFlight: o.inFlight);
    }
    return LikeView(liked: snapLiked, count: snapCount);
  }

  /// 唯一的点赞动作入口（爱心按钮与双击都调它）。
  ///
  /// [current] = 调用方此刻看到的有效值。飞行中 → 忽略；已是目标态 → 忽略；否则报埋点 → 乐观覆盖 →
  /// 调仓库 → 成功写后端真值并写回 Feed 两条流；失败写回点击前的有效值（不是删除覆盖：删除会让别处退回更旧的快照）。
  Future<LikeToggleOutcome> toggle(int postId,
      {required LikeView current, required bool wantLiked, required LikeTracking tracking}) async {
    // 飞行中：既看调用方的视图，也看 store 自己的覆盖（别处的按钮 / 双击可能刚发起）——只这一处判断。
    if (current.inFlight || (state[postId]?.inFlight ?? false)) {
      return const LikeToggleOutcome(LikeToggleStatus.ignoredInFlight);
    }
    if (current.liked == wantLiked) return const LikeToggleOutcome(LikeToggleStatus.ignoredNoChange);
    // 🛡 事件名不动（改名会切断已有的点赞历史序列）；只在真正发起请求时报。
    Analytics.capture('post_like_tapped', tracking.props(wantLiked));
    _write(postId, liked: wantLiked, count: current.count + (wantLiked ? 1 : -1), inFlight: true);
    try {
      final repo = ref.read(likeRepositoryProvider);
      final result = wantLiked ? await repo.like(postId) : await repo.unlike(postId);
      // store 已被换账号 invalidate：结果属于上一个账号，不当作落定（不回调 onSettled、不写回）。
      if (!ref.mounted) return const LikeToggleOutcome(LikeToggleStatus.failed);
      // 以后端真值校正（防并发漂移）。
      _write(postId, liked: result.liked, count: result.likeCount, inFlight: false);
      syncFeedCountsFromStore(ref, postId, liked: result.liked, likeCount: result.likeCount);
      return LikeToggleOutcome(LikeToggleStatus.settled, liked: result.liked, count: result.likeCount);
    } catch (_) {
      if (ref.mounted) {
        _write(postId, liked: current.liked, count: current.count, inFlight: false);
      }
      return const LikeToggleOutcome(LikeToggleStatus.failed);
    }
  }

  /// 某个位置拉到了**比覆盖更新**的快照（如详情页进入 / 评论后重拉）：把它的点赞值采纳为覆盖，
  /// 让还拿着旧快照的其它位置（顶置卡、话题页、Feed 卡）一起跟上，而不是继续显示更旧的覆盖值。
  /// 没有覆盖、覆盖在飞行中、或覆盖比该快照新 → 不动。
  void adoptFresherSnapshot(int postId, {required bool liked, required int count, required int snapEpoch}) {
    final o = state[postId];
    if (o == null || o.inFlight || o.epoch > snapEpoch) return;
    if (o.liked == liked && o.count == count) return;
    _write(postId, liked: liked, count: count, inFlight: false);
  }

  void _write(int postId, {required bool liked, required int count, required bool inFlight}) {
    _likeEpochCounter++;
    state = {
      ...state,
      postId: PostLikeOverride(
        liked: liked,
        count: count,
        inFlight: inFlight,
        epoch: _likeEpochCounter,
        bounceTick: state[postId]?.bounceTick ?? 0,
      ),
    };
  }
}

final postLikeStoreProvider =
    NotifierProvider<PostLikeStore, Map<int, PostLikeOverride>>(PostLikeStore.new);
