import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/domain/auth_state.dart';

/// 当前用户与某人之间的关注关系（V1.3.4 follow · Story 1.3 · AD-7）。
class FollowState {
  const FollowState({required this.iFollow, required this.followsMe});

  /// 互不关注（游客、缺字段一律取它）。
  static const FollowState none = FollowState(iFollow: false, followsMe: false);

  /// 我是否关注了对方。
  final bool iFollow;

  /// 对方是否关注了我（决定「Ikuti」还是「Ikuti Balik」）。
  final bool followsMe;

  /// 解析后端 `{iFollow, followsMe}`（Feed / 详情的 `authorFollowState`）。缺失 / null → [none]。
  static FollowState fromJson(Object? raw) {
    if (raw is! Map) return none;
    final iFollow = raw['iFollow'] == true;
    final followsMe = raw['followsMe'] == true;
    return iFollow || followsMe ? FollowState(iFollow: iFollow, followsMe: followsMe) : none;
  }

  FollowState copyWith({bool? iFollow, bool? followsMe}) => FollowState(
        iFollow: iFollow ?? this.iFollow,
        followsMe: followsMe ?? this.followsMe,
      );

  @override
  bool operator ==(Object other) =>
      other is FollowState && other.iFollow == iFollow && other.followsMe == followsMe;

  @override
  int get hashCode => Object.hash(iFollow, followsMe);

  @override
  String toString() => 'FollowState(iFollow: $iFollow, followsMe: $followsMe)';
}

/// 关注 / 取关接口的返回（`PUT|DELETE /api/v1/users/{token}/follow`）。
class FollowResult {
  const FollowResult({required this.state, required this.followerCount});

  final FollowState state;

  /// 对方最新粉丝数（服务端写入后重新读出）。
  final int followerCount;

  factory FollowResult.fromJson(Map<String, dynamic> json) => FollowResult(
        state: FollowState(
          iFollow: (json['iFollow'] ?? false) as bool,
          followsMe: (json['followsMe'] ?? false) as bool,
        ),
        followerCount: ((json['followerCount'] ?? 0) as num).toInt(),
      );
}

/// 覆盖表里的一条（V1.3.4 follow · Story 2.1 · AD-7）：关系值 + 「在哪个按钮上点的」+ 写入时的版本号。
///
/// 继承 [FollowState]：`==` 只比两个布尔（[pinnedSurface] / [epoch] 是元数据，不参与相等）。
class FollowOverride extends FollowState {
  const FollowOverride({
    required super.iFollow,
    required super.followsMe,
    required this.epoch,
    this.pinnedSurface,
  });

  /// 写入时的全局版本号（只增不减）。快照的 `followEpoch` ≥ 它 → 这条覆盖对该快照失效。
  final int epoch;

  /// 点关注 / 取关的那个按钮的 surfaceKey（如 `feed:item:42`、`detail:42`、`profile:7`）。
  /// 非常驻位置里**只有它**在已关注时保留「Mengikuti」，其余位置隐藏（AD-7）。
  final String? pinnedSurface;
}

/// 全局版本号计数器 —— 放在 notifier 外，**登录态重建时不归零**（Story 2.1 AC3.2）：
/// 否则换账号后从 0 重数，旧快照的版本号可能比新覆盖还大，新覆盖被误判失效。
int _followEpochCounter = 0;

/// 内联关注按钮（Feed 卡 / 详情头部）该显示什么（Story 2.1 AC3.4）。
enum InlineFollowDisplay { hidden, follow, followBack, following }

/// 内联按钮的显示判断（纯函数，便于单测）。
///
/// 覆盖「生效」= 有覆盖且 `override.epoch > snapshotEpoch`。
/// - 生效且 `pinnedSurface == surfaceKey` → 本按钮保留：已关注「Mengikuti」，否则「+ Ikuti / + Ikuti Balik」；
/// - 其他 → 实际状态（生效用覆盖，否则用快照）：已关注**隐藏**，否则「+ Ikuti / + Ikuti Balik」。
InlineFollowDisplay resolveInlineFollow({
  required FollowOverride? override,
  required FollowState server,
  required int snapshotEpoch,
  required String surfaceKey,
}) {
  final active = override != null && override.epoch > snapshotEpoch;
  if (active && override.pinnedSurface == surfaceKey) {
    if (override.iFollow) return InlineFollowDisplay.following;
    return override.followsMe ? InlineFollowDisplay.followBack : InlineFollowDisplay.follow;
  }
  final FollowState eff = active ? override : server;
  if (eff.iFollow) return InlineFollowDisplay.hidden;
  return eff.followsMe ? InlineFollowDisplay.followBack : InlineFollowDisplay.follow;
}

/// 全局关注状态（AD-7）：**本次会话里改过的关系**的覆盖表，按 userId。
///
/// 各处按钮读「服务端快照 + 覆盖（按版本号判断是否生效）」：
/// - 常驻位置（他人主页、我的 Mengikuti 列表）始终三态 → [effective]；
/// - 非常驻位置（Feed 卡、详情头部…）→ [resolveInlineFollow]：点的那个按钮保留 Mengikuti，
///   同一用户其他位置立即隐藏。
///
/// **「刷新」按位置算**（Story 2.1 AC3.5）：每个数据源发请求前读 [currentEpoch] 记进快照；
/// 重拉后的快照版本号 ≥ 旧覆盖 → 旧覆盖对它自动失效。**不做**「刷新时清空整表」——
/// 那会让还没刷新的位置按旧快照重新冒出「Ikuti」。
///
/// 🔴 **登录态 / 账号切换即重建为空表**：覆盖值是「当前账号」与对方的关系，
/// 换账号后还留着就会把 A 的关注状态显示给 B（与 bug 20260730-421 同型的跨账号串数据）。
/// 版本号计数器不归零（见 [_followEpochCounter]）。
class FollowStateNotifier extends Notifier<Map<int, FollowOverride>> {
  @override
  Map<int, FollowOverride> build() {
    ref.watch(authControllerProvider.select((s) => s.profile?.id));
    return const <int, FollowOverride>{};
  }

  /// 当前全局版本号。数据源**发请求前**读一次，写进快照的 `followEpoch`。
  int get currentEpoch => _followEpochCounter;

  /// 记录与 [userId] 的最新关系（乐观更新或接口响应）。每写一条，版本号先 +1。
  void set(int userId, FollowState value, {String? pinnedSurface}) {
    _followEpochCounter++;
    state = {
      ...state,
      userId: FollowOverride(
        iFollow: value.iFollow,
        followsMe: value.followsMe,
        epoch: _followEpochCounter,
        pinnedSurface: pinnedSurface,
      ),
    };
  }

  /// 失败回滚：把 [userId] 的覆盖**原样**恢复成点击前那一条（含其版本号），之前没有则删除。
  void restore(int userId, FollowOverride? previous) {
    if (previous == null) {
      forget(userId);
    } else {
      state = {...state, userId: previous};
    }
  }

  /// 丢弃对 [userId] 的覆盖，回到服务端快照（拉黑成功等）。
  void forget(int userId) {
    if (!state.containsKey(userId)) return;
    state = {...state}..remove(userId);
  }

  /// 常驻位置的三态取值：覆盖对该快照生效（`epoch > snapshotEpoch`）用覆盖，否则用快照。
  ///
  /// [snapshotEpoch] 缺省 0 = 任何覆盖都生效（快照早于一切写入）。
  FollowState effective(int userId, FollowState server, {int snapshotEpoch = 0}) {
    final o = state[userId];
    return o != null && o.epoch > snapshotEpoch ? o : server;
  }
}

final followStateProvider =
    NotifierProvider<FollowStateNotifier, Map<int, FollowOverride>>(FollowStateNotifier.new);
