import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/domain/auth_state.dart';

/// 追更 / 取消追更接口的返回（V1.4.2 social-topic · Story 1.1 `PetFollowResultResponse`）。
///
/// `POST|DELETE /api/v1/me/pet-follows/{petId}` → `{iFollow, followerCount}`。
class PetFollowResult {
  const PetFollowResult({required this.iFollow, required this.followerCount});

  /// 写入后我是否追更这只宠物（追更恒 true、取消恒 false）。
  final bool iFollow;

  /// 这只宠物最新的追更人数（服务端写入后重新读出）。
  final int followerCount;

  factory PetFollowResult.fromJson(Map<String, dynamic> json) => PetFollowResult(
        iFollow: json['iFollow'] == true,
        followerCount: (json['followerCount'] as num?)?.toInt() ?? 0,
      );
}

/// 覆盖表里的一条：本次会话里改过的追更关系 + 当时的追更人数 + 写入时的版本号（V1.4.2 social-topic · Story 1.4 · AC1.8）。
///
/// [followerCount] 为 null = 写入时不知道人数（统计还没到），显示处回落服务端快照。
/// `==` 只比 [iFollow] / [followerCount]（[epoch] 是元数据，同 `FollowOverride`）。
class PetFollowOverride {
  const PetFollowOverride({required this.iFollow, this.followerCount, this.epoch = 0});

  final bool iFollow;
  final int? followerCount;

  /// 写入时的全局版本号（只增不减）。服务端快照的 `followEpoch` ≥ 它 → 这条覆盖对该快照失效。
  final int epoch;

  @override
  bool operator ==(Object other) =>
      other is PetFollowOverride && other.iFollow == iFollow && other.followerCount == followerCount;

  @override
  int get hashCode => Object.hash(iFollow, followerCount);

  @override
  String toString() => 'PetFollowOverride(iFollow: $iFollow, followerCount: $followerCount)';
}

/// 全局版本号计数器 —— 放在 notifier 外，**登录态重建时不归零**（同 `_followEpochCounter`）：
/// 否则换账号后从 0 重数，旧快照的版本号可能比新覆盖还大，新覆盖被误判失效。
int _petFollowEpochCounter = 0;

/// 全局追更状态（V1.4.2 social-topic · Story 1.4 · AC1.8）：**本次会话里改过的追更关系**的覆盖表，按 petId。
///
/// 同一只宠物在多处出现（访客页按钮、1.5 的 Hewan 列表与推荐卡）读同一张表 → 状态一致。
/// 乐观写 → 成功以响应写回 → 失败 [restore] 回点击前那一条。
///
/// **「刷新」按版本号算**（仿 `FollowStateNotifier`，1.4 复审）：每个数据源（访客 profile / stats）发请求前读
/// [currentEpoch] 记进快照的 `followEpoch`；覆盖**只在比快照新**（`epoch > snapshotEpoch`）时生效 ——
/// 同会话再进宠物页、或成功后统计重取，拿到的服务端值（他端取消 / 别人追更后的人数）就会接管，
/// 不会被本会话早先的覆盖永久盖住。
///
/// 🔴 **登录态 / 账号切换即重建为空表**（同 `FollowStateNotifier`）：覆盖值是「当前账号」的追更关系，
/// 换账号还留着就会把 A 的追更态显示给 B。另在 `app.dart` 的 `resetUserScopedCaches` 登记。
class PetFollowStateNotifier extends Notifier<Map<int, PetFollowOverride>> {
  @override
  Map<int, PetFollowOverride> build() {
    ref.watch(authControllerProvider.select((s) => s.profile?.id));
    return const <int, PetFollowOverride>{};
  }

  /// 当前全局版本号。数据源**发请求前**读一次，写进快照的 `followEpoch`。
  int get currentEpoch => _petFollowEpochCounter;

  /// 记录与 [petId] 的最新追更关系（乐观更新或接口响应）。每写一条，版本号先 +1（[value] 的 epoch 被忽略）。
  void set(int petId, PetFollowOverride value) {
    _petFollowEpochCounter++;
    state = {
      ...state,
      petId: PetFollowOverride(
          iFollow: value.iFollow, followerCount: value.followerCount, epoch: _petFollowEpochCounter),
    };
  }

  /// 失败回滚：原样恢复成点击前那一条，之前没有则删除。
  void restore(int petId, PetFollowOverride? previous) {
    if (previous == null) {
      if (!state.containsKey(petId)) return;
      state = {...state}..remove(petId);
    } else {
      state = {...state, petId: previous};
    }
  }

  /// 有效的「我是否追更」：覆盖比快照新（`epoch > snapshotEpoch`）用覆盖，否则用服务端快照。
  /// [snapshotEpoch] 缺省 0 = 任何覆盖都生效（快照早于一切写入）。
  bool effectiveFollow(int petId, bool server, {int snapshotEpoch = 0}) =>
      resolvePetFollow(state[petId], server, snapshotEpoch: snapshotEpoch);

  /// 有效的追更人数：覆盖比快照新且带人数用覆盖，否则用服务端快照（可为 null = 未知）。
  int? effectiveCount(int petId, int? server, {int snapshotEpoch = 0}) =>
      resolvePetFollowerCount(state[petId], server, snapshotEpoch: snapshotEpoch);
}

final petFollowStateProvider =
    NotifierProvider<PetFollowStateNotifier, Map<int, PetFollowOverride>>(PetFollowStateNotifier.new);

/// 纯函数版 [PetFollowStateNotifier.effectiveFollow]（build 里配合 `ref.watch(petFollowStateProvider)` 用）。
bool resolvePetFollow(PetFollowOverride? o, bool server, {int snapshotEpoch = 0}) =>
    o != null && o.epoch > snapshotEpoch ? o.iFollow : server;

/// 纯函数版 [PetFollowStateNotifier.effectiveCount]。
int? resolvePetFollowerCount(PetFollowOverride? o, int? server, {int snapshotEpoch = 0}) =>
    o != null && o.epoch > snapshotEpoch && o.followerCount != null ? o.followerCount : server;
