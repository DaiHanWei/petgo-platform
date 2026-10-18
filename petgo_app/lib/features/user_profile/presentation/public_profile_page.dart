import 'dart:math' as math;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/network/problem_detail.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../../shared/widgets/post_grid_tile.dart';
import '../../../shared/widgets/profile_stats_row.dart';
import '../../../shared/widgets/user_tag_row.dart';
import '../../auth/domain/auth_guard.dart';
import '../../auth/domain/auth_state.dart';
import '../../social/data/blocked_users_repository.dart';
import '../../social/domain/account_action_entry.dart';
import '../../social/domain/follow_list_item.dart';
import '../../social/domain/follow_state.dart';
import '../../social/presentation/follow_list_page.dart';
import '../../social/presentation/widgets/follow_button.dart';
import '../../me/data/me_counts_provider.dart';
import '../../me/presentation/profile_edit_sheet.dart';
import '../../social/presentation/account_report_sheet.dart';
import '../../profile/presentation/visitor_archive_view.dart';
import '../../profile/presentation/widgets/pet_avatar_item.dart';
import '../data/public_profile_pet_repository.dart';
import '../data/public_profile_repository.dart';
import 'public_user_posts_controller.dart';
import '../../profile/data/pet_recommendation_repository.dart';

/// 用户在主页上对目标用户做成了什么 —— 主页 pop 时回给调用方的**收尾信号**。
///
/// 🔴 为什么要有这个枚举：迷你卡是个弹层，`onBlocked` / `onReported` 两个回调可以
/// 直接闭包捕获调用方那一屏。换成整页之后，调用方与主页之间只剩 Navigator 的返回值，
/// 而**那两条收尾行为一字都不能丢**（AC4：拉黑后退出详情页、从列表移除该作者全部内容）。
enum ProfileActionOutcome {
  /// 拉黑成功（成功提示已由主页给过）。
  blocked,

  /// 举报成功。**静默** —— 提示会泄露「举报会隐藏内容」。
  reported,
}

/// `user_profile_viewed` 的 `from`（E-12，PRD 定死三值：avatar / mention / place）。
///
/// ⚠️ 与 [AccountActionEntry] 是**两个维度**：那个是拉黑 / 举报的发起入口（既有时间序列，
/// 枚举值不许动），这个只描述「主页是怎么被打开的」。故独立一个参数，不往那边加值。
enum ProfileViewFrom {
  /// 点头像 / 作者行 / 评论作者（缺省）。
  avatar('avatar'),

  /// 点正文或评论里的 @。
  mention('mention'),

  /// 场所详情里的标记人 / 场所评论作者。
  place('place');

  const ProfileViewFrom(this.wire);

  final String wire;
}

/// 进入某人的公开主页（V1.3.0 batch-b1 Story 2.1 · FR-118.1）。
///
/// 🔴 **这是 `showMiniProfile` 的替代品**：本 story 之后，App 里所有「点头像看这人是谁」
/// 的入口一律走这里，迷你卡组件零引用（AC2）。
///
/// [onBlocked] / [onReported] 的语义**与迷你卡逐字相同**（AC4）：
/// 仅成功路径触发，取消与失败都不触发；触发时机是主页已经收起、调用方那一屏回到前台。
/// 两者收尾动作一般相同，调用方通常传同一个回调。
///
/// [entry]：从哪儿点进来的，只用于埋点，不影响任何行为。
///
/// [viewFrom]：`user_profile_viewed` 的 `from`（E-12，bug 20260922-536）。缺省时由 [entry]
/// 推：`mention` → mention，其余 → avatar；场所两处入口显式传 [ProfileViewFrom.place]。
/// 🔴 本函数对「看自己」不分流（同一套页面，由服务端 `self` 切视角），所以自己点自己也照报 ——
/// 看板要排除时按 `target_user_id == distinct_id` 过滤。
Future<void> openUserProfile(
  BuildContext context,
  WidgetRef ref,
  int userId, {
  VoidCallback? onBlocked,
  VoidCallback? onReported,
  AccountActionEntry entry = AccountActionEntry.miniProfile,
  ProfileViewFrom? viewFrom,
}) async {
  final from = viewFrom ??
      (entry == AccountActionEntry.mention
          ? ProfileViewFrom.mention
          : ProfileViewFrom.avatar);
  // user_id 明文在 PostHog 豁免范围内（同 identifyUser）。
  Analytics.capture('user_profile_viewed', {
    'target_user_id': userId,
    'from': from.wire,
  });
  final outcome = await context.push<ProfileActionOutcome>(
    '${PublicProfilePage.routeBase}/$userId?entry=${entry.wire}',
  );
  switch (outcome) {
    case ProfileActionOutcome.blocked:
      onBlocked?.call();
    case ProfileActionOutcome.reported:
      onReported?.call();
    case null:
      break; // 只是看完返回 —— 什么都不做。
  }
}

/// 用户公开主页（他人视角，V1.3.4 follow UI 稿 A2「居中名片」）。
///
/// ## 页面结构（V1.3.4 Story 1.3 · UI 稿 A2）
/// 头像 84 居中 → 昵称（运营标签）→ 签名 → **加入时间** → 四格（Postingan / Suka / Mengikuti / Pengikut）
/// → 整宽主按钮（他人：关注三态；自己：Edit Profil，D-11）→ Hewan 区（圆形头像一排，点进去看那只宠物的访客视图）
/// → Postingan 区（2 列裸网格，复用「我的」页那一格的公共组件）。
///
/// ## 🔴 FR-118.7 的「不放」清单（AC5 反向验收）
/// 主页上**没有**里程碑徽章墙、护照集章数、打卡足迹、主页级 H5 分享入口、
/// 访客记录、独立 bio 字段。这些不是"还没做"，是**明确不做** ——
/// 一条扫源码的测试钉着它们不出现（`test/user_profile/public_profile_posts_test.dart`）。
/// （「关注 / 粉丝」原在此清单，V1.3.4 FR-140 推翻。）
///
/// ## 同页两视角（UI 稿 C1 / C2 · Story 2.4）
/// 自己看自己的主页走**同一套页面**，由服务端下发的 `self` 切换两处：
/// - 顶栏**没有「···」**（对自己举报 / 拉黑没有意义）；
/// - 主按钮槽位是「Edit Profil」（不是关注按钮），跳**既有**的资料编辑抽屉。
///
/// 🔴 公开主页与「我的」Tab 仍是**两个页面、不合并**（我的页带订单入口、设置、宠物引导卡……，
/// 公开主页只是"别人眼里的我"）；我的页在 V1.3.4 Story 1.4 同样改为居中名片。
class PublicProfilePage extends ConsumerWidget {
  const PublicProfilePage({super.key, required this.userId, this.entry = AccountActionEntry.miniProfile});

  /// 路由前缀。拼 `'$routeBase/$userId'` 即为某人主页。
  static const String routeBase = '/users';

  /// go_router 的路由模板。
  static const String routePattern = '$routeBase/:userId';

  final int userId;

  /// 从哪儿进来的（仅埋点）。
  final AccountActionEntry entry;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    // V1.3.4 Story 1.3 · AC5.6：游客在本页登录（或换了账号）→ 三块数据按新身份重取，
    // 按钮显示新身份的真实关系（含 `self` 切换）。**不自动关注**（FR-140.19）。
    ref.listen<int?>(
      authControllerProvider.select((s) => s.isLoggedIn ? s.profile?.id : null),
      (prev, next) {
        if (prev == next) return;
        ref.invalidate(publicProfileProvider(userId));
        ref.invalidate(publicProfilePetsProvider(userId));
        ref.invalidate(publicUserPostsProvider(userId));
      },
    );
    final async = ref.watch(publicProfileProvider(userId));
    final profile = async.value;
    // 「···」只在**确定是他人、且账号还在**时渲染：自己没什么可举报可拉黑的，
    // 已注销的人也一样（NFR-3：连身份都不下发了，再挂一个举报入口是自相矛盾）。
    // 2026-10-13 决定：官方内容账号不可拉黑 / 举报（后端也只认普通用户，点了会 404）→ 不给「···」。
    final showMore = profile != null && !profile.self && !profile.isDeactivated && !profile.official;
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        backgroundColor: AppColors.base,
        scrolledUnderElevation: 0,
        actions: [
          if (showMore)
            IconButton(
              key: const ValueKey('profileMore'),
              icon: const Icon(Icons.more_horiz_rounded, color: AppColors.ink),
              onPressed: () => _openActionSheet(context, ref, l10n, profile),
            ),
          const SizedBox(width: AppSpacing.xs),
        ],
      ),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => _error(context, ref, l10n, e),
        data: (p) => p.isDeactivated
            // AC5：本 story 以通用空态兜底；完整「用户不存在」视觉由 Story 2.5 补。
            // ⚠️ 与「id 不存在」用**同一句文案**：两者可区分就等于确认了「这个 id 曾经有人」。
            ? _EmptyState(
                key: const ValueKey('profileDeactivated'),
                icon: Icons.person_off_outlined,
                message: l10n.profileNotFound,
              )
            : _identity(context, ref, p),
      ),
    );
  }

  Widget _error(BuildContext context, WidgetRef ref, AppLocalizations l10n, Object e) {
    final problem = e is DioException ? ProblemDetail.fromDioException(e) : null;
    // 🔴 「我拉黑了对方」是**独立分支**（服务端 403 + type .../blocked-user）：
    // 混进网络失败的话，用户会一直重试一个永远不会成功的动作。这里**不给重试按钮**。
    if (problem?.typeSlug == 'blocked-user') {
      return _EmptyState(
        key: const ValueKey('profileBlocked'),
        icon: Icons.block_rounded,
        message: l10n.profileBlockedEmpty,
      );
    }
    // ⚠️ **服务端当前不会为「id 不存在」发 404** —— 它与已注销合流成 200 +「什么都没有」的投影
    // （刻意不可区分，见 `PublicProfileController` 类注释）。这条分支是**防御性映射**：
    // 真收到 404（路由改了 / 网关拦了）时给「用户不存在」，比给「网络失败 + 重试」贴切得多 ——
    // 后者会让用户对着一个永远不会好的按钮点下去。
    if (problem?.status == 404) {
      return _EmptyState(
        key: const ValueKey('profileNotFound'),
        icon: Icons.person_off_outlined,
        message: l10n.profileNotFound,
      );
    }
    return _EmptyState(
      key: const ValueKey('profileLoadFailed'),
      icon: Icons.wifi_off_rounded,
      message: l10n.profileLoadFailed,
      action: TextButton(
        key: const ValueKey('profileRetry'),
        onPressed: () => ref.invalidate(publicProfileProvider(userId)),
        child: Text(l10n.commonRetry),
      ),
    );
  }

  /// 身份区「居中名片」（V1.3.4 Story 1.3 · UI 稿 A2）。
  ///
  /// ⚠️ **他人视角没有 email、没有相机角标**；自己视角主按钮槽位是「Edit Profil」
  /// （D-11）—— email 与相机角标是「我的」Tab 专属，这一页两种视角都不给。
  Widget _identity(BuildContext context, WidgetRef ref, PublicProfile p) {
    final l10n = AppLocalizations.of(context);
    final joinedAt = p.joinedAt;
    // AC5.3：Pengikut 显示值 = 服务端数 ± 本页加载后我改过的关系。覆盖与服务端一致时就是服务端数；
    // **不用**关注响应里的 followerCount 回写（两种来源混用会跳数；重进页面即取最新服务端值）。
    ref.watch(followStateProvider);
    final server = FollowState(iFollow: p.iFollow, followsMe: p.followsMe);
    final eff = ref
        .read(followStateProvider.notifier)
        .effective(userId, server, snapshotEpoch: p.followEpoch);
    final followers =
        math.max(0, p.followerCount + (eff.iFollow ? 1 : 0) - (p.iFollow ? 1 : 0));
    final token = p.userToken;
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      children: [
        Center(
          // UI 稿 A2 `.av.person`：无头像 = 白底 + 1px line 描边（border-box 84）+ 实心 person 52 muted。
          child: (p.avatarUrl == null || p.avatarUrl!.isEmpty)
              ? Container(
                  key: const ValueKey('profileAvatar'),
                  width: 84,
                  height: 84,
                  decoration: BoxDecoration(
                    color: Colors.white,
                    shape: BoxShape.circle,
                    border: Border.all(color: AppColors.line),
                  ),
                  child: const Icon(Icons.person_rounded, size: 52, color: AppColors.muted),
                )
              : CircleAvatar(
                  key: const ValueKey('profileAvatar'),
                  radius: 42,
                  backgroundColor: AppColors.border,
                  backgroundImage: AppImage.provider(p.avatarUrl, thumbWidth: 320),
                ),
        ),
        const SizedBox(height: 12),
        // 运营标签的第五处展示位（前四处：feed / detail / comment / mini_profile）。
        Center(
          child: UserTagRow(
            position: 'profile',
            name: p.nickname ?? '',
            nameStyle: AppTypography.title.copyWith(fontSize: 20, fontWeight: FontWeight.w700),
            tags: p.tags,
          ),
        ),
        if (p.hasSignature) ...[
          const SizedBox(height: 4),
          Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 280),
              child: Text(
                p.signature!.trim(),
                key: const ValueKey('profileSignature'),
                style: const TextStyle(fontSize: 13, color: AppColors.ink2),
                textAlign: TextAlign.center,
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ),
        ],
        if (joinedAt != null) ...[
          const SizedBox(height: 4),
          Text(
            // 到月不到日 —— 确切注册日期没必要外泄（见 `formatMonthAbbrYear`）。
            l10n.profileJoinedAt(formatMonthAbbrYear(context, joinedAt.toLocal())),
            key: const ValueKey('profileJoinedAt'),
            style: const TextStyle(fontSize: 11, color: AppColors.muted),
            textAlign: TextAlign.center,
          ),
        ],
        const SizedBox(height: AppSpacing.md),
        // 四格（AC4.2）。Story 2.2：Mengikuti / Pengikut 两格进关注 / 粉丝列表；Postingan / Suka 不可点。
        // 被拉黑方看拉黑者主页（A7）照常可点（列表接口对「对方拉黑了我」照常返回，D-10）。
        // 🔴 帖子 / 获赞：被拉黑方视角服务端已置 0（与「真没发过」逐字节一致）；
        //    关注 / 粉丝为真实值 —— 客户端不做任何「是否被拉黑」判断（FR-118.5）。
        ProfileStatsRow(
          postCount: p.postCount,
          likeCount: p.likeCount,
          followingCount: p.followingCount,
          followerCount: followers,
          // 官方号的关注 / 粉丝列表不对外（服务端 403）：数字照常显示，两格不可点。
          onFollowingTap: p.official
              ? null
              : () => context.push(FollowListPage.location(userId, FollowListTab.following)),
          onFollowersTap: p.official
              ? null
              : () => context.push(FollowListPage.location(userId, FollowListTab.followers)),
        ),
        const SizedBox(height: AppSpacing.md),
        // 主按钮槽位（AC4.5）：自己 → Edit Profil（D-11）；他人 → 关注三态；老后端无 token → 不渲染。
        if (p.self)
          _EditProfileButton(userId: userId)
        else if (token != null)
          FollowButton(
            userId: userId,
            userToken: token,
            initial: server,
            snapshotEpoch: p.followEpoch,
            surfaceKey: 'profile:$userId',
            style: FollowButtonStyle.profileFull,
            source: 'profile',
            // T-9：对方刚被注销 / 封号 → 重取主页，显示「Pengguna tidak ditemukan」。
            onNotFound: () => ref.invalidate(publicProfileProvider(userId)),
          ),
        // Hewan 区（Story 1.3 · AC6）。没建过档案的人这里整块不渲染（含小节标题）——
        // 「他还没养宠物」不需要一张空卡片来说明。
        _PetSection(userId: userId),
        // Postingan 区：小节标题 + 网格 / 空态（AC4.6）。
        _PostGrid(userId: userId, self: p.self),
      ],
    );
  }

  /// 操作抽屉（UI 稿 C3）：举报 / 拉黑两项 + 底部「取消」。
  ///
  /// 样式沿用「编辑资料」抽屉那一套（顶部圆角 24 + 手柄 + 上滑），
  /// **不是**迷你卡那个锚在右上角的浮层 —— 那个浮层是为了不遮住小卡片的内容，
  /// 整页之下这个理由不存在了。
  Future<void> _openActionSheet(
    BuildContext context,
    WidgetRef ref,
    AppLocalizations l10n,
    PublicProfile profile,
  ) async {
    final action = await showModalBottomSheet<_ProfileAction>(
      context: context,
      backgroundColor: AppColors.surface,
      // 手柄自己画：`showModalBottomSheet` 不收 `dragHandleColor`，而 Material 默认
      // 手柄色（onSurfaceVariant）比 UI 稿 C3 那条浅灰手柄深得多。尺寸沿用 Material
      // 默认（32×4，上下各 22 → 占 48 高），版面与原 `showDragHandle: true` 一致。
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (sheetContext) => SafeArea(
        child: Padding(
          // UI 稿 C3：两条分隔线左右各让出 lg，不贴抽屉边。
          padding: const EdgeInsets.fromLTRB(
              AppSpacing.lg, 0, AppSpacing.lg, AppSpacing.md),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: Container(
                  key: const ValueKey('profileMenuDragHandle'),
                  width: 32,
                  height: 4,
                  margin: const EdgeInsets.symmetric(vertical: 22),
                  decoration: BoxDecoration(
                    color: AppColors.border,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
              // 「举报」与「拉黑」**并列、不分主次**（与迷你卡菜单同一口径）。
              //
              // ⚠️ 已举报过 → 文案换成「已举报 / 点击可再次举报」并用品牌色，
              // **必须读起来像「还能再点」而不是禁用态**：再报一次是有意义的
              // （每次的类型独立留存，第一次报骚扰、第二次报仿冒正是问题在升级的证据）。
              _ActionTile(
                itemKey: const ValueKey('profileMenuReport'),
                emoji: profile.reported ? '📌' : '🚩',
                label: profile.reported ? l10n.accountReportedAction : l10n.accountReportAction,
                // UI 稿 C3：常态只有一行主文案；副标题**只在已举报时出现** ——
                // 它承担「还能再点」的提示，缺了它「已举报」会读成禁用态。
                subtitle: profile.reported ? l10n.accountReportedActionSub : null,
                labelColor: profile.reported ? AppColors.mint : AppColors.ink,
                onTap: () => Navigator.of(sheetContext).pop(_ProfileAction.report),
              ),
              // UI 稿 C3：两项之间一条细分隔线。
              const Divider(height: 1, thickness: 1, color: AppColors.divider),
              // ⚠️ 举报之后**拉黑项照常可点、不置灰不隐藏**：拉黑带来一个举报没有的效果 ——
              // 从此进不去对方主页。以「已举报」为由禁掉它是错的。
              // UI 稿 C3：「拉黑」是破坏性动作 → 文字用危险红（与 Pop 红同一 token）。
              _ActionTile(
                itemKey: const ValueKey('profileMenuBlock'),
                emoji: '🚫',
                label: l10n.blockUserAction,
                labelColor: AppColors.popRed,
                onTap: () => Navigator.of(sheetContext).pop(_ProfileAction.block),
              ),
              const Divider(height: 1, thickness: 1, color: AppColors.divider),
              const SizedBox(height: AppSpacing.xs),
              // UI 稿 C3 明确要一个**显式**的「取消」：点遮罩也能收起，但显式按钮更明确。
              // 取消是中性动作 → 次级文字色，**不用品牌紫**（紫色会读成「推荐你点这个」）。
              TextButton(
                key: const ValueKey('profileMenuCancel'),
                style: TextButton.styleFrom(foregroundColor: AppColors.textSecondary),
                onPressed: () => Navigator.of(sheetContext).pop(),
                child: Text(l10n.commonCancel,
                    style: const TextStyle(fontWeight: FontWeight.w600)),
              ),
            ],
          ),
        ),
      ),
    );
    if (!context.mounted) return;
    switch (action) {
      case _ProfileAction.report:
        await _startReport(context, ref, profile);
      case _ProfileAction.block:
        await _startBlock(context, ref, l10n, profile);
      case null:
        break;
    }
  }

  /// 举报 → 成功后收起主页并把 [ProfileActionOutcome.reported] 交回调用方。
  ///
  /// ⚠️ **全程静默**：不给任何成功提示 —— 提示会泄露「举报会隐藏内容」（AC4）。
  Future<void> _startReport(BuildContext context, WidgetRef ref, PublicProfile profile) async {
    // FR-0C：游客点社区动作 → 强登录引导，不发请求（与拉黑同一门控）。
    if (!requireLogin(ref, context, onAllowed: () {})) return;
    final submitted = await openAccountReport(
      context,
      ref,
      userId,
      // 「已举报」来自服务端标记，不是前端会话态。
      alreadyReported: profile.reported,
      entry: entry,
    );
    if (!submitted || !context.mounted) return;
    context.pop(ProfileActionOutcome.reported);
  }

  /// 拉黑二次确认 → 提交 → 成功收起主页 + 成功提示 / 失败**保持停在主页**。
  ///
  /// 成功收起、失败留下，两者行为相反是刻意的：失败不该让用户重新走一遍入口。
  Future<void> _startBlock(
    BuildContext context,
    WidgetRef ref,
    AppLocalizations l10n,
    PublicProfile profile,
  ) async {
    if (!requireLogin(ref, context, onAllowed: () {})) return;
    // toast 要在主页收起**之后**给，那时 context 已失效 → 先把 root Overlay 拿在手里。
    final overlay = Overlay.maybeOf(context, rootOverlay: true);
    final ok = await showConfirmSheet(
      context,
      title: l10n.blockUserTitle(profile.nickname ?? ''),
      // 只说「不再看到 TA 的内容和评论」——**刻意不提影子评论、不提「对方不会收到通知」**（A-A27）。
      message: l10n.blockUserMessage,
      confirmLabel: l10n.blockUserAction,
      cancelLabel: l10n.commonCancel,
      danger: true,
      // 头像重复出现：**确认拉黑谁比确认动作本身更重要**（C1，刻意的冗余）。
      leading: CircleAvatar(
        radius: 30,
        backgroundColor: AppColors.border,
        backgroundImage: AppImage.provider(profile.avatarUrl, thumbWidth: 200),
        child: (profile.avatarUrl == null || profile.avatarUrl!.isEmpty)
            ? const Icon(Icons.person_rounded, size: 28, color: AppColors.textTertiary)
            : null,
      ),
      confirmKey: const ValueKey('confirmBlockUser'),
      onConfirm: () async {
        try {
          await ref.read(blockedUsersRepositoryProvider).block(userId);
          // V1.3.4 Story 1.3 · AC5.7：服务端已同事务删除我对他的关注（AD-5）——
          // 残留的「已关注」覆盖会让其他页面误显示 Mengikuti。
          ref.read(followStateProvider.notifier).forget(userId);
          // V1.3.4 Story 1.4：拉黑联动取关会让我的关注数变化 → 我的页四格重取。
          ref.invalidate(meCountsProvider);
          // 「逛别人家的毛孩子」推荐位要跟着重算（Story 4.1 AC3 / 4.4）——
          // 「互相拉黑不互推」是服务端取数时算的，不重取就等于这条过滤对当前那份数据不生效。
          invalidatePetRecommendations(ref);
          // ⚠️ 埋点在**成功之后**（V1.1.2 的教训：门控前就上报会让指标系统性高估）。
          // 拉黑失败不上报，取消也不上报。
          Analytics.capture('social_user_hide_submitted', {
            'origin': 'BLOCK',
            'entry': entry.wire,
          });
          return true;
        } catch (_) {
          // 失败提示必须在这里给：此时确认抽屉仍然开着，`showConfirmSheet` 尚未返回。
          // `top: true` 与举报失败同口径：抽屉还开着时，toast 的默认底部位置正好压在按钮区上。
          if (overlay != null) {
            showAppToastOnOverlay(overlay, l10n.blockUserFailed, top: true);
          }
          return false;
        }
      },
    );
    if (!ok || !context.mounted) return;
    context.pop(ProfileActionOutcome.blocked);
    if (overlay != null) showAppToastOnOverlay(overlay, l10n.blockUserSuccess);
  }
}

/// 内容区：2 列裸网格 + 「加载更多」（V1.3.0 batch-b1 Story 2.2 · AC1/AC4）。
///
/// 🔴 **这里不做任何可见范围过滤** —— 服务端只给 PUBLIC（NFR-2）。
/// 客户端过滤只是"看不见"，抓包照样拿得到；真要在这里加一行 `where`，
/// 反而会掩盖服务端漏过滤的 bug。
///
/// ⚠️ 网格用 `shrinkWrap + NeverScrollableScrollPhysics` 挂在外层 ListView 里 ——
/// 与「我的」页同一种嵌法，两层滚动不会互相抢手势。
class _PostGrid extends ConsumerWidget {
  const _PostGrid({
    required this.userId,
    required this.self,
  });

  final int userId;

  /// 自己视角。**只影响空态那一句文案**：「(对方的隐私设置)」写在自己的主页上是句废话。
  ///
  /// ⚠️ 在这里分一次是**安全的**，因为被拉黑的访客 `self` 永远是 false ——
  /// 也正因如此，**他人视角那一侧绝不能再分第三种文案**（AC2）。
  final bool self;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(publicUserPostsProvider(userId));
    return async.when(
      loading: () => const Padding(
        padding: EdgeInsets.all(AppSpacing.lg),
        child: Center(child: CircularProgressIndicator()),
      ),
      // 内容区取数失败**不接管整页**：身份区已经渲染出来了，把它换成一屏错误没有道理。
      // V1.3.4：有内容 / 空态 / 失败三种状态都显示「Postingan」小节标题（UI 稿 A6）。
      error: (_, _) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _header(l10n),
          Padding(
            padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
            child: Row(
              children: [
                Expanded(child: Text(l10n.profileLoadFailed, style: AppTypography.caption)),
                TextButton(
                  key: const ValueKey('profilePostsRetry'),
                  onPressed: () => ref.invalidate(publicUserPostsProvider(userId)),
                  child: Text(l10n.commonRetry),
                ),
              ],
            ),
          ),
        ],
      ),
      data: (page) {
        if (page.items.isEmpty) {
          // UI 稿 C4：内容区居中空态，上下留 xl。
          // 他人视角带一把锁（「这里的内容看不到」）；自己视角**不带** ——
          // 自己的主页上一把锁读起来像「你被限制了」，而事实只是还没发。
          // 🔴 锁图标只按 `self` 分，**与"是否被拉黑"无关**（客户端也无从得知）：
          // 被拉黑者与"真没发过"看到的是同一个组件、同一句文案、同一把锁（AC2）。
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _header(l10n),
              Padding(
                padding: const EdgeInsets.symmetric(vertical: AppSpacing.xl),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (!self) ...[
                      const Icon(Icons.lock_outline_rounded,
                          size: 32, color: AppColors.textTertiary),
                      const SizedBox(height: AppSpacing.sm),
                    ],
                    Text(
                  // 🔴 **一句文案吃两种服务端情况**（Story 2.5 · AC2）：
                  // ① 这个人真的没发过公开内容；② 这个人**拉黑了当前访客**（FR-118.5）。
                  // 服务端对这两种情况返回的是**逐字节一样**的响应（空 items + 两个计数归零），
                  // 客户端因此**压根没有"被拉黑"这个概念** —— 这里不是"忘了分支"，
                  // 是这条设计的全部要点：两者只要有任何视觉或文案差异，
                  // 用户一对比就能推断出自己被拉黑，而整条 FR-118.5 正是为了不让他确认这件事。
                  // ⚠️ 别给"被拉黑"写一句更贴切的文案，那会当场毁掉它。
                      self ? l10n.profilePostsEmptySelf : l10n.profilePostsEmpty,
                      key: const ValueKey('profilePostsEmpty'),
                      style: AppTypography.caption,
                      textAlign: TextAlign.center,
                    ),
                  ],
                ),
              ),
            ],
          );
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _header(l10n),
            GridView.count(
              crossAxisCount: 2,
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              mainAxisSpacing: 7,
              crossAxisSpacing: 7,
              children: [
                for (final post in page.items)
                  PostGridTile(
                    postId: post.id,
                    type: post.type,
                    firstImageUrl: post.firstImageUrl,
                    // 他人主页永远不会有私密内容（服务端就没给），这里不传。
                    onTap: () => context.push('/content/${post.id}'),
                  ),
              ],
            ),
            if (page.hasMore)
              TextButton(
                key: const ValueKey('profilePostsLoadMore'),
                onPressed: () => _loadMore(context, ref, l10n),
                child: Text(l10n.profilePostsLoadMore),
              ),
          ],
        );
      },
    );
  }

  /// 「Postingan」小节标题（V1.3.4 Story 1.3 · AC4.6，与 Hewan 同款）。
  ///
  /// 原右侧的两个聚合计数（`profileCounts`）已移到身份区四格。
  /// 🔴 有内容 / 空态 / 失败**都显示**：「真没发过」与「对方拉黑了我」服务端返回逐字节一样，
  /// 这里两种情况同样渲染、不区分（FR-118.5 / Story 2.5 AC2）。
  Widget _header(AppLocalizations l10n) => Padding(
        padding: const EdgeInsets.only(top: AppSpacing.lg, bottom: AppSpacing.sm),
        child: _SectionTitle(l10n.profilePostsTitle),
      );

  /// 追加下一页。失败只提示一声，**不动已加载的网格**。
  Future<void> _loadMore(BuildContext context, WidgetRef ref, AppLocalizations l10n) async {
    try {
      await ref.read(publicUserPostsProvider(userId).notifier).loadMore();
    } catch (_) {
      if (context.mounted) showAppToast(context, l10n.profileLoadFailed);
    }
  }
}

/// 主页小节标题（V1.3.4 Story 1.3）：12 / w700 / 大写 / letterSpacing 0.6 / `muted`。Hewan 与 Postingan 同款。
class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Text(
        text.toUpperCase(),
        style: const TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.6,
          color: AppColors.muted,
        ),
      );
}

/// 主页 Hewan 区（V1.3.4 Story 1.3 · AC6，UI 稿 A2 宠物方案 B）：小节标题 + 圆形头像一排。
///
/// 每项：头像 64（外圈 2px 白 + 2px 浅紫环）+ 右上角 diary 数角标，下方名字 +「物种 · 年龄」。
/// 点头像进那只宠物的**访客视图**。
///
/// ## 组件按列表做（V1.4.0 multi-pet Story 1.5 起是真列表）
/// 数据源 `GET /users/{userId}/pets`（全部宠物）；>4 只时横向滚动（J2）。
///
/// ## 🔴 点进去要登录（AC1），但头像本身游客也看得见
/// 站内访客接口**仅对登录用户开放**（AD-4 Rule 1），所以跳转前走 FR-0C 登录门控；
/// 而「看这人养了只什么」与「看这人是谁」同一档，不需要登录。两层边界不同是刻意的。
///
/// ## Tailsonality 角色小标（V1.3.2 Story 3.3）
/// 名字下方（T-7），放不下省略；佩戴 + 已解锁时才显示，没有就**什么都不渲染**。
class _PetSection extends ConsumerWidget {
  const _PetSection({required this.userId});

  final int userId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    // V1.4.0 multi-pet（Story 1.5 · AC6）：数据源换成该用户的全部宠物（J0 ~ J2）。
    // 🛡 **不显示只读锁标**：访客不该看出主人的名额状态。
    final pets = ref.watch(publicProfilePetsProvider(userId)).value;
    // 没有宠物 / 还在取 / 取失败 / 空数组 → 整块（含小节标题）不渲染（UI 稿 A5）。
    // 🛡 失败也不给错误态：这是主页上的一个装饰区块，为它摆一条红字会喧宾夺主。
    if (pets == null || pets.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: AppSpacing.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _SectionTitle(l10n.profilePetsSection),
          const SizedBox(height: AppSpacing.sm),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            // 角标向右上溢出 6 / 4，留出位置不被裁。
            padding: const EdgeInsets.only(top: 4, right: 6),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (var i = 0; i < pets.length; i++) _item(context, ref, l10n, pets[i], i),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// 单项外观抽到共享件 [PetAvatarItem]（bug 20261015-590：我的页「Hewanku」同款）。
  ///
  /// key：首只沿用 `profilePetCard` 等（既有测试在用），其余加序号（`profilePetCard-1` …）。
  Widget _item(BuildContext context, WidgetRef ref, AppLocalizations l10n, PublicProfilePet pet,
      int index) {
    Key k(String base) => ValueKey(index == 0 ? base : '$base-$index');
    return PetAvatarItem(
      key: k('profilePetCard'),
      name: pet.name,
      avatarUrl: pet.avatarUrl,
      petType: pet.petType,
      birthday: pet.birthday,
      tailsonalityBadge: pet.tailsonalityBadge,
      diaryCount: pet.diaryCount,
      diaryBadgeKey: k('profilePetDiaryBadge'),
      tailsonalityKey: k('profilePetTailsonality'),
      metaKey: k('profilePetMeta'),
      onTap: () => _open(context, ref, pet),
    );
  }

  /// FR-0C：游客点进访客视图 → 强登录引导，**不发请求**（站内访客接口仅登录可用）。
  ///
  /// E-17（bug 20260922-534）：`pet_card_tapped` 报在门控**之前**（点击就是点击，游客被拦也算），
  /// 跳转带 `?from=profile`，访客视图据此报 `diary_visitor_viewed`。
  void _open(BuildContext context, WidgetRef ref, PublicProfilePet pet) {
    Analytics.capture('pet_card_tapped', {'from': 'profile'});
    requireLogin(ref, context,
        onAllowed: () => context.push(
            '${VisitorArchiveView.inAppRouteBase}/${pet.petId}?from=profile'));
  }
}

/// 「Edit Profil」整宽按钮（自己看自己的主页，D-11 · UI 稿 `.wbtn.ghost`）：跳**既有**的资料编辑抽屉。
///
/// 外观与我的页（Story 1.4）同款：整宽、高 40、圆角 12、白底 + 1.5px 浅紫描边 + 紫字 14 / w700 ——
/// 两处是同一个按钮，用户不该在两个地方看到两种样子。
class _EditProfileButton extends ConsumerWidget {
  const _EditProfileButton({required this.userId});

  final int userId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return SizedBox(
      width: double.infinity,
      height: 40,
      child: OutlinedButton(
        key: const ValueKey('profileEditButton'),
        onPressed: () async {
          await openProfileEditSheet(context, ref);
          // 🔴 `context.mounted` 不可省：保存请求在途时用户按返回退出主页，
          // 这一行就会在**已销毁的 ref** 上调 invalidate —— riverpod 3 的
          // `_assertNotDisposed()` 是真抛 StateError（不是 assert，release 也抛），
          // 而这里没有 catch，出去就是一条未捕获异步异常（code-review 2026-09-15）。
          if (!context.mounted) return;
          // 改完昵称 / 签名 / 头像要让这一页跟上 —— 不刷的话用户改完回到自己的主页，
          // 看到的还是旧昵称，会以为没保存成功。
          ref.invalidate(publicProfileProvider(userId));
        },
        style: OutlinedButton.styleFrom(
          backgroundColor: AppColors.surface,
          foregroundColor: AppColors.mint,
          side: const BorderSide(color: AppColors.dashedViolet, width: 1.5),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          padding: EdgeInsets.zero,
        ),
        child: Text(
          l10n.meEditProfileTitle,
          style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
        ),
      ),
    );
  }
}

enum _ProfileAction { report, block }

/// 抽屉里的一项：emoji + 主文案 14/w400 +（仅需要提示时）副标题 12（UI 稿 C3）。
class _ActionTile extends StatelessWidget {
  const _ActionTile({
    required this.itemKey,
    required this.emoji,
    required this.label,
    this.subtitle,
    required this.onTap,
    this.labelColor = AppColors.ink,
  });

  final Key itemKey;
  final String emoji;
  final String label;

  /// null → 不渲染副标题（常态）。
  final String? subtitle;
  final VoidCallback onTap;
  final Color labelColor;

  @override
  Widget build(BuildContext context) => InkWell(
        key: itemKey,
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(emoji, style: const TextStyle(fontSize: 16)),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(label,
                        style: TextStyle(
                            fontSize: 14, fontWeight: FontWeight.w400, color: labelColor)),
                    if (subtitle != null) ...[
                      const SizedBox(height: 2),
                      Text(subtitle!,
                          style: const TextStyle(fontSize: 12, color: AppColors.textSecondary)),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ),
      );
}

/// 页面级空态 / 失败态（「用户不存在」/ 已注销 / 我拉黑了对方 / 加载失败共用，UI 稿 C5）。
///
/// 视觉：56 图标 + body w700 ink 主文案 —— 整页只剩这一句话时，它就是这一屏的标题。
class _EmptyState extends StatelessWidget {
  const _EmptyState({super.key, required this.icon, required this.message, this.action});

  final IconData icon;
  final String message;
  final Widget? action;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 56, color: AppColors.textTertiary),
              const SizedBox(height: AppSpacing.md),
              Text(
                message,
                style: AppTypography.body
                    .copyWith(fontWeight: FontWeight.w700, color: AppColors.ink),
                textAlign: TextAlign.center,
              ),
              if (action != null) ...[const SizedBox(height: AppSpacing.xs), action!],
            ],
          ),
        ),
      );
}
