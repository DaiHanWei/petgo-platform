import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/network/problem_detail.dart';
import '../../../../core/theme/colors.dart';
import '../../../auth/domain/auth_state.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_toast.dart';
import '../../../auth/domain/auth_guard.dart';
import '../../../me/data/me_counts_provider.dart';
import '../../../user_profile/data/public_profile_repository.dart';
import '../../data/follow_repository.dart';
import '../../domain/follow_state.dart';

/// 关注按钮的外观（V1.3.4 follow）。
///
/// [profileFull] 由 Story 1.3、[textInline] 由 Story 2.1 交付；`listCompact`（关注列表行）由 Story 2.2 追加
/// —— **只换外观，点击逻辑共用本组件内部一处**。
enum FollowButtonStyle {
  /// 主页整宽主按钮（UI 稿 A2 / A3 / A4 `.wbtn` / `.wbtn.ghost2`）。**常驻**：始终三态。
  profileFull,

  /// Feed 卡 / 详情头部的文字按钮（UI 稿 C1 / C2 / C4 / C5 `.fc-btn .fbtn`）。**非常驻**：
  /// 已关注的作者不显示；点关注的那个按钮保留「Mengikuti」，同一用户其他位置立即隐藏（AD-7）。
  textInline,

  /// 关注 / 粉丝列表行右侧的小按钮（Story 2.2 · UI 稿 B1 / B3 / B7 `.fbtn`）：高 30、圆角 8、12 号字、最小宽 88；
  /// Ikuti / Ikuti Balik 浅紫底紫字，Mengikuti 白底灰框。常驻与否由 [FollowButton.persistent] 决定。
  listCompact,
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
    this.surfaceKey,
    this.snapshotEpoch = 0,
    this.persistent = false,
    this.idleBackground,
    this.idleBorderColor,
    this.filledIdle = false,
    this.onTapped,
  });

  /// 对方数字 userId（覆盖表的键 / 埋点 `target_user_id`）。
  final int userId;

  /// 对方不可枚举 token（关注接口路径用，D-2）。null（作者已注销 / 封号 / 老后端）→ 不渲染。
  final String? userToken;

  /// 服务端下发的初值。
  final FollowState initial;

  final FollowButtonStyle style;

  /// 埋点 `source`（`profile` / Story 2.x 的 `feed` / `detail` / `list` …）。
  final String source;

  /// 接口回 404（对方刚被注销 / 封号）时的额外动作（T-9：主页据此重取，显示「Pengguna tidak ditemukan」）。
  final VoidCallback? onNotFound;

  /// 本按钮实例的位置键（Story 2.1 AC3.7）：`feed:item:{postId}` / `feed:pinned:{postId}` /
  /// `detail:{postId}` / `profile:{userId}`……点击时写进覆盖的 `pinnedSurface`。
  /// 缺省按 `{style}:{userId}` 生成。
  final String? surfaceKey;

  /// 服务端快照（[initial]）发请求前读到的全局关注版本号（`FeedItem.followEpoch` 等）。
  final int snapshotEpoch;

  /// 仅 [FollowButtonStyle.listCompact]：是否常驻位置（AD-7）。我自己的 Mengikuti 列表 = 常驻：
  /// 始终三态（取关后该行保留、按钮变 Ikuti）；其余列表 = 非常驻：已关注不显示、本按钮保留 Mengikuti。
  final bool persistent;

  /// 仅 [FollowButtonStyle.listCompact]：「Ikuti / Ikuti Balik」态的底色（缺省浅紫 violet100）。
  /// V1.3.4 Story 3.4：通知中心未读行本身是浅紫底，按钮改用白底（UI 稿核对结论）。
  final Color? idleBackground;

  /// 仅 [FollowButtonStyle.listCompact]：「Ikuti / Ikuti Balik」态的 1.5px 描边色（缺省无描边）。
  final Color? idleBorderColor;

  /// 仅 [FollowButtonStyle.listCompact]：「Ikuti / Ikuti Balik」态改为实心品牌紫胶囊（高 36、圆角 18、白字 w700）。
  /// 只给 Following 空状态的推荐行用（UI 稿 A3）；其余用法保持浅紫小芯片。
  final bool filledIdle;

  /// 已登录用户点下按钮那一刻回调（参数 = 这次是关注还是取关），在请求之前。
  /// V1.3.4 Story 3.4：通知中心据此上报 `notification_follow_back_tapped`。游客点击（弹登录）不回调。
  final ValueChanged<bool>? onTapped;

  @override
  ConsumerState<FollowButton> createState() => _FollowButtonState();
}

class _FollowButtonState extends ConsumerState<FollowButton> {
  bool _inFlight = false;

  String get _surfaceKey => widget.surfaceKey ?? '${widget.style.name}:${widget.userId}';

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final overrides = ref.watch(followStateProvider);
    final token = widget.userToken;
    if (token == null) return const SizedBox.shrink();
    switch (widget.style) {
      case FollowButtonStyle.profileFull:
        final eff = ref
            .read(followStateProvider.notifier)
            .effective(widget.userId, widget.initial, snapshotEpoch: widget.snapshotEpoch);
        final label = eff.iFollow
            ? l10n.followActionFollowing
            : (eff.followsMe ? l10n.followActionFollowBack : l10n.followActionFollow);
        return _profileFull(label, eff.iFollow);
      case FollowButtonStyle.listCompact:
        if (widget.persistent) {
          final eff = ref
              .read(followStateProvider.notifier)
              .effective(widget.userId, widget.initial, snapshotEpoch: widget.snapshotEpoch);
          return _listCompact(
              eff.iFollow
                  ? l10n.followActionFollowing
                  : (eff.followsMe ? l10n.followActionFollowBack : l10n.followActionFollow),
              following: eff.iFollow);
        }
        final listDisplay = resolveInlineFollow(
          override: overrides[widget.userId],
          server: widget.initial,
          snapshotEpoch: widget.snapshotEpoch,
          surfaceKey: _surfaceKey,
        );
        return switch (listDisplay) {
          InlineFollowDisplay.hidden => const SizedBox.shrink(),
          InlineFollowDisplay.following => _listCompact(l10n.followActionFollowing, following: true),
          InlineFollowDisplay.followBack => _listCompact(l10n.followActionFollowBack, following: false),
          InlineFollowDisplay.follow => _listCompact(l10n.followActionFollow, following: false),
        };
      case FollowButtonStyle.textInline:
        // 自己的帖子（C7）不显示。
        final me = ref.watch(authControllerProvider.select((s) => s.profile?.id));
        if (me != null && me == widget.userId) return const SizedBox.shrink();
        final display = resolveInlineFollow(
          override: overrides[widget.userId],
          server: widget.initial,
          snapshotEpoch: widget.snapshotEpoch,
          surfaceKey: _surfaceKey,
        );
        return switch (display) {
          InlineFollowDisplay.hidden => const SizedBox.shrink(),
          InlineFollowDisplay.following => _textInline(l10n.followActionFollowing, plus: false),
          InlineFollowDisplay.followBack => _textInline(l10n.followActionFollowBack, plus: true),
          InlineFollowDisplay.follow => _textInline(l10n.followActionFollow, plus: true),
        };
    }
  }

  /// UI 稿 `.fbtn`（列表行）：高 30、圆角 8、12 号字、最小宽 88。
  Widget _listCompact(String label, {required bool following}) {
    final filled = widget.filledIdle && !following;
    final h = filled ? 36.0 : 30.0;
    final shape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(filled ? 18 : 8));
    final text = Text(label,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: filled ? 13 : 12, fontWeight: filled ? FontWeight.w700 : FontWeight.w600));
    final constraints = BoxConstraints(minWidth: filled ? 78 : 88, minHeight: h, maxHeight: h);
    return ConstrainedBox(
      key: ValueKey('followList_$_surfaceKey'),
      constraints: constraints,
      child: following
          ? OutlinedButton(
              onPressed: _onTap,
              style: OutlinedButton.styleFrom(
                backgroundColor: AppColors.surface,
                foregroundColor: AppColors.ink2,
                side: const BorderSide(color: AppColors.line, width: 1.5),
                shape: shape,
                minimumSize: const Size(88, 30),
                padding: const EdgeInsets.symmetric(horizontal: 12),
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              child: text,
            )
          : FilledButton(
              onPressed: _onTap,
              style: FilledButton.styleFrom(
                backgroundColor: filled ? AppColors.mint : (widget.idleBackground ?? AppColors.violet100),
                foregroundColor: filled ? Colors.white : AppColors.mint,
                side: widget.idleBorderColor == null
                    ? null
                    : BorderSide(color: widget.idleBorderColor!, width: 1.5),
                shape: shape,
                minimumSize: Size(filled ? 78 : 88, h),
                padding: EdgeInsets.symmetric(horizontal: filled ? 16 : 12),
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              child: text,
            ),
    );
  }

  /// UI 稿 `.fc-btn .fbtn`：「+ Ikuti」紫 13 / w600（「+」w700、间隔 3，T-16 前缀单独渲染）；
  /// 「Mengikuti」muted w500、无「+」。无底色无边框；可点区域宽 ≥ 44、高 32，视觉尺寸不变。
  ///
  /// ⚠️ 高度只撑到 32（= Feed 卡头像高，详情头像 36 更高）而不是 44（code-review 2.1 #3）：
  /// 撑到 44 会让「有按钮」的作者行比「没按钮」的高一截 —— 点关注后同作者其他卡按钮隐藏，
  /// 卡片立刻变矮，列表在手指下方跳动。
  Widget _textInline(String label, {required bool plus}) {
    final color = plus ? AppColors.mint : AppColors.muted;
    return GestureDetector(
      key: ValueKey('followInline_$_surfaceKey'),
      // 🔴 自己吃掉点击：不冒泡到外层「进主页」/「进详情」。
      behavior: HitTestBehavior.opaque,
      onTap: _onTap,
      child: ConstrainedBox(
        constraints: const BoxConstraints(minWidth: 44, minHeight: 32),
        child: Center(
          widthFactor: 1,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (plus) ...[
                  Text('+',
                      style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: color)),
                  const SizedBox(width: 3),
                ],
                Text(
                  label,
                  maxLines: 1,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: plus ? FontWeight.w600 : FontWeight.w500,
                    color: color,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
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
    final token = widget.userToken;
    if (token == null) return;
    // 游客：只弹登录，登录后不自动关注（FR-140.19）。
    if (!requireLogin(ref, context, entrySource: 'follow')) return;
    final l10n = AppLocalizations.of(context);
    // ⚠️ 先把 notifier 拿在手里：请求期间页面可能被关掉（State 卸载后 ref 不可用），
    //    回滚仍必须执行，否则乐观写入的覆盖会一直留着、别处按钮显示错。
    final notifier = ref.read(followStateProvider.notifier);
    final repo = ref.read(followRepositoryProvider);
    // 同理：成功后要失效我的页四格，请求返回时页面可能已关（code-review 1.4 #3）→ 先拿 container。
    final container = ProviderScope.containerOf(context, listen: false);
    // 🔴 记下点击时的账号（code-review 1.3 F2）：请求在途时若 401 → 退游客 / 换账号，覆盖表已重建，
    //    这时再写会把旧账号的关系写进新表 —— 写入前比对，不一致就丢弃。
    final accountAtTap = container.read(authControllerProvider).profile?.id;
    bool sameAccount() => container.read(authControllerProvider).profile?.id == accountAtTap;
    final userId = widget.userId;
    final surface = _surfaceKey;
    final before = ref.read(followStateProvider)[userId];
    final current = notifier.effective(userId, widget.initial, snapshotEpoch: widget.snapshotEpoch);
    final wantFollow = !current.iFollow;
    widget.onTapped?.call(wantFollow);
    _inFlight = true;
    // 乐观更新：followsMe 不变；pinnedSurface = 本按钮，同一用户其他非常驻位置**立即**隐藏。
    notifier.set(userId, current.copyWith(iFollow: wantFollow), pinnedSurface: surface);
    try {
      final result = wantFollow ? await repo.follow(token) : await repo.unfollow(token);
      if (!sameAccount()) return;
      notifier.set(userId, result.state, pinnedSurface: surface);
      // D-6 过去式命名；成功后才报。is_follow_back = 点击前对方是否关注我（T-8，两个事件同口径）。
      Analytics.capture(wantFollow ? 'user_followed' : 'user_unfollowed', {
        'target_user_id': userId,
        'source': widget.source,
        'is_follow_back': current.followsMe,
      });
      // V1.3.4 Story 1.4（T-15）：我的关注数变了 → 我的页四格重取 `/me`（页面已关也要失效）。
      container.invalidate(meCountsProvider);
      // 我本人的公开主页（关注列表页在栈里时会一直持有它）同样带 Mengikuti 计数，不失效就停在旧值（验收 FL-30）。
      if (accountAtTap != null) container.invalidate(publicProfileProvider(accountAtTap));
    } catch (e) {
      // 回滚到点击前那一条覆盖（原样，含版本号；之前没有则删除）。账号已变 → 新表本就是空的，不写。
      if (!sameAccount()) return;
      notifier.restore(userId, before);
      if (!mounted) return;
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
