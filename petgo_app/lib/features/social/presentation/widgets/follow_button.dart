import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/network/problem_detail.dart';
import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_toast.dart';
import '../../../auth/domain/auth_guard.dart';
import '../../data/follow_repository.dart';
import '../../domain/follow_state.dart';

/// 关注按钮的外观（V1.3.4 follow）。
///
/// 本 story（1.3）只有 [profileFull]；`textInline`（Feed 卡 / 详情头部）由 Story 2.1、
/// `listCompact`（关注列表行）由 Story 2.2 追加 —— **只换外观，点击逻辑共用本组件内部一处**。
enum FollowButtonStyle {
  /// 主页整宽主按钮（UI 稿 A2 / A3 / A4 `.wbtn` / `.wbtn.ghost2`）。
  profileFull,
}

/// 关注 / 取关按钮（V1.3.4 follow · Story 1.3 · AC3 / AC5 / AC7）。
///
/// 显示状态 = `followStateProvider.effective(userId, initial)`：
/// `iFollow` →「Mengikuti」；否则 `followsMe` →「Ikuti Balik」；否则「Ikuti」。
///
/// 点击：
/// - 游客 → `requireLogin`（弹 `LoginHardDialog`），**不传 pendingAction / onAllowed**：登录后不自动关注
///   （FR-140.19，UI 稿 A9）；不发请求、不埋点。
/// - 已登录 → 乐观更新覆盖表 → 调接口 → 成功：以响应为准写回 + 埋点；失败：回滚到点击前 + toast。
/// - 取关**不弹二次确认**（PRD）；请求在途时再点直接忽略（不显示 loading，避免闪烁）。
class FollowButton extends ConsumerStatefulWidget {
  const FollowButton({
    super.key,
    required this.userId,
    required this.userToken,
    required this.initial,
    required this.style,
    required this.source,
    this.onNotFound,
  });

  /// 对方数字 userId（覆盖表的键 / 埋点 `target_user_id`）。
  final int userId;

  /// 对方不可枚举 token（关注接口路径用，D-2）。
  final String userToken;

  /// 服务端下发的初值。
  final FollowState initial;

  final FollowButtonStyle style;

  /// 埋点 `source`（`profile` / Story 2.x 的 `feed` / `detail` / `list` …）。
  final String source;

  /// 接口回 404（对方刚被注销 / 封号）时的额外动作（T-9：主页据此重取，显示「Pengguna tidak ditemukan」）。
  final VoidCallback? onNotFound;

  @override
  ConsumerState<FollowButton> createState() => _FollowButtonState();
}

class _FollowButtonState extends ConsumerState<FollowButton> {
  bool _inFlight = false;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    ref.watch(followStateProvider);
    final eff = ref.read(followStateProvider.notifier).effective(widget.userId, widget.initial);
    final label = eff.iFollow
        ? l10n.followActionFollowing
        : (eff.followsMe ? l10n.followActionFollowBack : l10n.followActionFollow);
    return switch (widget.style) {
      FollowButtonStyle.profileFull => _profileFull(label, eff.iFollow),
    };
  }

  Widget _profileFull(String label, bool following) {
    final text = Text(
      label,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
    );
    final shape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(12));
    const size = Size.fromHeight(40);
    return SizedBox(
      key: const ValueKey('followButton'),
      width: double.infinity,
      height: 40,
      child: following
          ? OutlinedButton(
              onPressed: _onTap,
              style: OutlinedButton.styleFrom(
                backgroundColor: AppColors.surface,
                foregroundColor: AppColors.ink,
                side: const BorderSide(color: AppColors.line, width: 1.5),
                shape: shape,
                minimumSize: size,
                padding: EdgeInsets.zero,
              ),
              child: text,
            )
          : FilledButton(
              onPressed: _onTap,
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                foregroundColor: Colors.white,
                shape: shape,
                minimumSize: size,
                padding: EdgeInsets.zero,
              ),
              child: text,
            ),
    );
  }

  Future<void> _onTap() async {
    if (_inFlight) return;
    // 游客：只弹登录，登录后不自动关注（FR-140.19）。
    if (!requireLogin(ref, context, entrySource: 'follow')) return;
    final l10n = AppLocalizations.of(context);
    final notifier = ref.read(followStateProvider.notifier);
    final userId = widget.userId;
    final before = ref.read(followStateProvider)[userId];
    final current = notifier.effective(userId, widget.initial);
    final wantFollow = !current.iFollow;
    _inFlight = true;
    // 乐观更新：followsMe 不变。
    notifier.set(userId, current.copyWith(iFollow: wantFollow));
    try {
      final repo = ref.read(followRepositoryProvider);
      final result =
          wantFollow ? await repo.follow(widget.userToken) : await repo.unfollow(widget.userToken);
      if (!mounted) return;
      ref.read(followStateProvider.notifier).set(userId, result.state);
      // D-6 过去式命名；成功后才报。is_follow_back = 点击前对方是否关注我（T-8，两个事件同口径）。
      Analytics.capture(wantFollow ? 'user_followed' : 'user_unfollowed', {
        'target_user_id': userId,
        'source': widget.source,
        'is_follow_back': current.followsMe,
      });
    } catch (e) {
      if (!mounted) return;
      // 回滚到点击前的覆盖值（点击前没有覆盖则丢弃，回到服务端初值）。
      final n = ref.read(followStateProvider.notifier);
      if (before == null) {
        n.forget(userId);
      } else {
        n.set(userId, before);
      }
      final problem = e is DioException ? ProblemDetail.fromDioException(e) : null;
      final status = problem?.status ?? (e is DioException ? e.response?.statusCode : null);
      if (problem?.typeSlug == 'rate-limited' || status == 429) {
        // UI 稿 A10：底部 toast，按钮不变。
        showAppToast(context, l10n.followRateLimited);
      } else {
        // UI 稿 A11：顶部 toast。409 follow-blocked / 404 等按网络失败处理（T-9）。
        showAppToast(context, wantFollow ? l10n.followFailed : l10n.followUnfollowFailed, top: true);
        if (status == 404) widget.onNotFound?.call();
      }
    } finally {
      _inFlight = false;
    }
  }
}
