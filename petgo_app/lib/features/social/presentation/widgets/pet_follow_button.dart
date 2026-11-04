import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/network/problem_detail.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_toast.dart';
import '../../../auth/domain/auth_guard.dart';
import '../../../auth/domain/auth_state.dart';
import '../../../profile/data/timeline_repository.dart';
import '../../../user_profile/data/public_profile_repository.dart';
import '../../data/pet_follow_repository.dart';
import '../../domain/pet_follow_state.dart';
import 'follow_button.dart';

/// 追更按钮的外观（V1.4.2 social-topic · Story 1.4 / 1.5）。外观与用户关注按钮共用（[followProfileFullButton] 等），
/// 保证两种按钮像素一致。
enum PetFollowButtonStyle {
  /// 宠物访客页信息卡内的整宽按钮（UI 稿 A1 / A2）：未追更「Ikuti」紫色实心、已追更「Mengikuti」白底灰框。
  profileFull,

  /// Hewan 列表 / 推荐卡行右侧的小按钮（Story 1.5）：外观同用户关注列表行 `.fbtn`，常驻两态。
  listCompact,
}

/// 追更 / 取消追更按钮（V1.4.2 social-topic · Story 1.4 · AC1 / AC6 · D-8）。
///
/// 显示状态 = `petFollowStateProvider` 覆盖（有则用）否则服务端快照 [initialFollow]。
///
/// 点击（照 `FollowButton._onTap`，覆盖表按 **petId**）：
/// - 游客 → `requireLogin`，**登录后不自动追更**；不发请求、不埋点。
/// - 已登录 → 乐观更新（追更态 + 人数 ±1）→ 调接口 → 成功：以响应 `followerCount` 写回 + 埋点 + 失效；
///   失败：回滚到点击前 + toast（超频底部、其余顶部）。
/// - 取消**不弹二次确认**；请求在途时再点直接忽略（不显示 loading，避免闪烁）。
class PetFollowButton extends ConsumerStatefulWidget {
  const PetFollowButton({
    super.key,
    required this.petId,
    required this.initialFollow,
    required this.style,
    required this.source,
    this.serverFollowerCount,
    this.followSnapshotEpoch = 0,
    this.countSnapshotEpoch = 0,
    this.ownerToken,
    this.iFollowOwner,
    this.onNotFound,
  });

  /// 宠物 id（覆盖表的键 / 接口路径 / 埋点 `pet_id`，与站内访客路由 `/pets/{petId}` 同一标识）。
  final int petId;

  /// 服务端快照：我是否已追更（访客 profile 的 `iFollow`；缺键按 false）。
  final bool initialFollow;

  /// 服务端快照：当前追更人数（stats 的 `followerCount`）。null = 未知 → 乐观更新不改人数。
  final int? serverFollowerCount;

  /// [initialFollow] 那份快照发请求前读到的追更版本号（`VisitorProfile.followEpoch`）：
  /// 只有比它新的覆盖才盖过快照（1.4 复审，仿 `FollowButton.snapshotEpoch`）。缺省 0 = 任何覆盖都生效。
  final int followSnapshotEpoch;

  /// [serverFollowerCount] 那份快照的追更版本号（`ArchiveStats.followEpoch`）。
  final int countSnapshotEpoch;

  /// 主人公开 token（R-2 埋点 `owner_id`）。null（旧后端）→ 埋点省略该属性。
  final String? ownerToken;

  /// 我是否关注了主人（R-2 埋点 `is_following_owner`）。null → 埋点省略该属性。
  final bool? iFollowOwner;

  final PetFollowButtonStyle style;

  /// 埋点 `source`：本 story `pet_visitor`；1.5 的 `follow_list` / `pet_recommendation`。
  final String source;

  /// 接口回 404（宠物 / 主人刚失效）时的额外动作：访客页据此重取档案 → 落 A14。
  final VoidCallback? onNotFound;

  @override
  ConsumerState<PetFollowButton> createState() => _PetFollowButtonState();
}

class _PetFollowButtonState extends ConsumerState<PetFollowButton> {
  bool _inFlight = false;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final following = resolvePetFollow(ref.watch(petFollowStateProvider)[widget.petId], widget.initialFollow,
        snapshotEpoch: widget.followSnapshotEpoch);
    final label = following ? l10n.followActionFollowing : l10n.followActionFollow;
    return switch (widget.style) {
      PetFollowButtonStyle.profileFull => followProfileFullButton(
          key: const ValueKey('petFollowButton'),
          label: label,
          following: following,
          onPressed: _onTap,
        ),
      PetFollowButtonStyle.listCompact => followListCompactButton(
          key: ValueKey('petFollowList_${widget.petId}'),
          label: label,
          following: following,
          onPressed: _onTap,
        ),
    };
  }

  Future<void> _onTap() async {
    if (_inFlight) return;
    // 游客：只弹登录，登录后不自动追更（同 FR-140.19）。
    if (!requireLogin(ref, context, entrySource: 'pet_follow')) return;
    final l10n = AppLocalizations.of(context);
    // ⚠️ 先把 notifier / repo / container 拿在手里：请求期间页面可能被关掉（State 卸载后 ref 不可用），
    //    回滚与失效仍必须执行（同 `FollowButton._onTap`）。
    final notifier = ref.read(petFollowStateProvider.notifier);
    final repo = ref.read(petFollowRepositoryProvider);
    final container = ProviderScope.containerOf(context, listen: false);
    // 记下点击时的账号：请求在途换号 → 覆盖表已重建，再写会把旧账号的关系写进新表。
    final accountAtTap = container.read(authControllerProvider).profile?.id;
    bool sameAccount() => container.read(authControllerProvider).profile?.id == accountAtTap;
    final petId = widget.petId;
    final before = ref.read(petFollowStateProvider)[petId];
    final current = notifier.effectiveFollow(petId, widget.initialFollow, snapshotEpoch: widget.followSnapshotEpoch);
    final countBefore =
        notifier.effectiveCount(petId, widget.serverFollowerCount, snapshotEpoch: widget.countSnapshotEpoch);
    final wantFollow = !current;
    final optimisticCount = countBefore == null
        ? null
        : (wantFollow ? countBefore + 1 : (countBefore > 0 ? countBefore - 1 : 0));
    _inFlight = true;
    notifier.set(petId, PetFollowOverride(iFollow: wantFollow, followerCount: optimisticCount));
    try {
      final result = wantFollow ? await repo.follow(petId) : await repo.unfollow(petId);
      if (!sameAccount()) return;
      notifier.set(petId, PetFollowOverride(iFollow: result.iFollow, followerCount: result.followerCount));
      // PRD §4 定死事件名；**服务端确认成功后**才报（失败、游客拦截不报）。
      // R-2：owner_id = 主人公开 token（不报自增 id）；token / iFollowOwner 缺失时省略该属性，不报 null。
      final props = <String, Object>{
        'pet_id': petId,
        if (widget.ownerToken != null) 'owner_id': widget.ownerToken!,
        'source': widget.source,
        if (widget.iFollowOwner != null) 'is_following_owner': widget.iFollowOwner!,
      };
      // P-9：两处字面量各写一次 capture（v112 守卫的正则只认 `Analytics.capture('名字'`，三元式扫不到）。
      if (wantFollow) {
        Analytics.capture('pet_follow', props);
      } else {
        Analytics.capture('pet_unfollow', props);
      }
      // AC1.7：本宠物 Pengikut 数（整族失效，访客页两种作用域都覆盖）+ 我自己公开主页的「Hewan N」胶囊。
      container.invalidate(visitorStatsProvider);
      if (accountAtTap != null) container.invalidate(publicProfileProvider(accountAtTap));
      // TODO(v1.4.2 Story 1.5): 我的追更宠物列表 provider 建好后在此 `container.invalidate(...)`。
    } catch (e) {
      if (!sameAccount()) return;
      notifier.restore(petId, before);
      if (!mounted) return;
      final problem = e is DioException ? ProblemDetail.fromDioException(e) : null;
      final status = problem?.status ?? (e is DioException ? e.response?.statusCode : null);
      if (problem?.typeSlug == 'rate-limited' || status == 429) {
        // UI 稿 A3（超频态）：底部 toast，按钮回滚。
        showAppToast(context, l10n.followRateLimited);
      } else {
        // UI 稿 A3：顶部 toast。403 blocked-user / 404 / 422 等按普通失败处理。
        showAppToast(context, wantFollow ? l10n.followFailed : l10n.followUnfollowFailed, top: true);
        if (status == 404) widget.onNotFound?.call();
      }
    } finally {
      _inFlight = false;
    }
  }
}
