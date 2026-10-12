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

/// 全局关注状态（AD-7）：**本次会话里改过的关系**的覆盖表，按 userId。
///
/// 各处按钮读「服务端初值 + 覆盖」（[effective]）。本 story（1.3）只建最小形态：
/// [set] / [forget] / [effective] + 登录态切换重建；`pinnedSurface`、「非常驻位置已关注即隐藏」、
/// 「刷新后清除覆盖」由 Story 2.1 扩展。
///
/// 🔴 **登录态 / 账号切换即重建为空表**：覆盖值是「当前账号」与对方的关系，
/// 换账号后还留着就会把 A 的关注状态显示给 B（与 bug 20260730-421 同型的跨账号串数据）。
class FollowStateNotifier extends Notifier<Map<int, FollowState>> {
  @override
  Map<int, FollowState> build() {
    ref.watch(authControllerProvider.select((s) => s.profile?.id));
    return const <int, FollowState>{};
  }

  /// 记录与 [userId] 的最新关系（乐观更新或接口响应）。
  void set(int userId, FollowState value) {
    state = {...state, userId: value};
  }

  /// 丢弃对 [userId] 的覆盖，回到服务端初值（失败回滚 / 拉黑成功）。
  void forget(int userId) {
    if (!state.containsKey(userId)) return;
    state = {...state}..remove(userId);
  }

  /// 有覆盖用覆盖，否则用服务端初值。
  FollowState effective(int userId, FollowState server) => state[userId] ?? server;
}

final followStateProvider =
    NotifierProvider<FollowStateNotifier, Map<int, FollowState>>(FollowStateNotifier.new);
