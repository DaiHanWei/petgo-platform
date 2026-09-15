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
import '../../../shared/widgets/user_tag_row.dart';
import '../../auth/domain/auth_guard.dart';
import '../../social/data/blocked_users_repository.dart';
import '../../social/domain/account_action_entry.dart';
import '../../me/presentation/profile_edit_sheet.dart';
import '../../social/presentation/account_report_sheet.dart';
import '../../profile/domain/pet_age.dart';
import '../../profile/presentation/visitor_archive_view.dart';
import '../data/public_profile_pet_repository.dart';
import '../data/public_profile_repository.dart';
import 'public_user_posts_controller.dart';

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
Future<void> openUserProfile(
  BuildContext context,
  WidgetRef ref,
  int userId, {
  VoidCallback? onBlocked,
  VoidCallback? onReported,
  AccountActionEntry entry = AccountActionEntry.miniProfile,
}) async {
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

/// 用户公开主页（他人视角，UI 稿 C1）。
///
/// ## 页面结构（UI 稿 C1）
/// 身份区（头像 / 昵称 / 运营标签 / **加入时间** / 签名 / 两个聚合计数）
/// → 宠物区（一张卡，点进去看那只宠物的访客视图）
/// → 内容区（2 列裸网格，复用「我的」页那一格的公共组件）。
///
/// ## 🔴 FR-118.7 的「不放」清单（AC5 反向验收）
/// 主页上**没有**里程碑徽章墙、护照集章数、打卡足迹、主页级 H5 分享入口、
/// 关注 / 粉丝、访客记录、独立 bio 字段。这些不是"还没做"，是**明确不做** ——
/// 一条扫源码的测试钉着它们不出现（`test/user_profile/public_profile_posts_test.dart`）。
///
/// ## 同页两视角（UI 稿 C1 / C2 · Story 2.4）
/// 自己看自己的主页走**同一套页面**，由服务端下发的 `self` 切换两处：
/// - 顶栏**没有「···」**（对自己举报 / 拉黑没有意义）；
/// - 身份行内多一个「编辑资料」按钮，跳**既有**的资料编辑抽屉。
///
/// 🔴 **「我的」Tab 一行不改**：那是另一个页面（带订单入口、设置、宠物引导卡……），
/// 公开主页只是"别人眼里的我"。两者刻意不合并。
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
    final async = ref.watch(publicProfileProvider(userId));
    final profile = async.value;
    // 「···」只在**确定是他人、且账号还在**时渲染：自己没什么可举报可拉黑的，
    // 已注销的人也一样（NFR-3：连身份都不下发了，再挂一个举报入口是自相矛盾）。
    final showMore = profile != null && !profile.self && !profile.isDeactivated;
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

  /// 身份区（UI 稿 C1 的 `.profhead`）。
  ///
  /// ⚠️ **他人视角没有 email、没有相机角标、没有「编辑资料」**。
  /// 自己视角（C2）只多出「编辑资料」一个按钮 —— email 与相机角标是
  /// 「我的」Tab 专属，这一页两种视角都不给。
  Widget _identity(BuildContext context, WidgetRef ref, PublicProfile p) {
    final l10n = AppLocalizations.of(context);
    final joinedAt = p.joinedAt;
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            CircleAvatar(
              key: const ValueKey('profileAvatar'),
              radius: 31,
              backgroundColor: AppColors.border,
              backgroundImage: AppImage.provider(p.avatarUrl, thumbWidth: 240),
              child: (p.avatarUrl == null || p.avatarUrl!.isEmpty)
                  ? const Icon(Icons.person_rounded, size: 31, color: AppColors.textTertiary)
                  : null,
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  // 运营标签的第五处展示位（前四处：feed / detail / comment / mini_profile）。
                  UserTagRow(
                    position: 'profile',
                    name: p.nickname ?? '',
                    nameStyle: AppTypography.title,
                    tags: p.tags,
                  ),
                  if (joinedAt != null) ...[
                    const SizedBox(height: 3),
                    Text(
                      // 到月不到日 —— 确切注册日期没必要外泄（见 `formatMonthAbbrYear`）。
                      l10n.profileJoinedAt(formatMonthAbbrYear(context, joinedAt.toLocal())),
                      key: const ValueKey('profileJoinedAt'),
                      style: AppTypography.caption,
                    ),
                  ],
                  if (p.hasSignature) ...[
                    const SizedBox(height: 3),
                    Text(
                      p.signature!.trim(),
                      key: const ValueKey('profileSignature'),
                      style: AppTypography.caption,
                      maxLines: 3,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                  const SizedBox(height: 3),
                  // 两个聚合计数（AC2）。发帖总数**复用既有统计**（没有重新实现），
                  // 获赞总数是本批次新补的；**两者都只算 PUBLIC**，与下面的网格同源 ——
                  // 对不上的话，那个差值就是「这人有几篇私密内容」。
                  Text(
                    l10n.profileCounts(p.postCount, p.likeCount),
                    key: const ValueKey('profileCounts'),
                    style: AppTypography.caption,
                  ),
                ],
              ),
            ),
            // AC3：「编辑资料」在**身份行内、与头像同一行**（对齐真实的 me_page）——
            // 🔴 **不塞进顶部 AppBar**：UI 稿 C2 明确标了位置，而 AppBar 那个位置
            // 在他人视角上是「···」，两种视角共用一个槽位会让人第一眼分不清自己在看谁的主页。
            if (p.self) ...[
              const SizedBox(width: AppSpacing.sm),
              _EditProfileButton(userId: userId),
            ],
          ],
        ),
        // 宠物区（Story 2.3 · AC3）。没建过档案的人这里整块不渲染 ——
        // 「他还没养宠物」不需要一张空卡片来说明。
        _PetSection(userId: userId),
        const SizedBox(height: AppSpacing.lg),
        Padding(
          padding: const EdgeInsets.only(left: AppSpacing.xs, bottom: AppSpacing.sm),
          child: Text(
            l10n.profilePostsTitle.toUpperCase(),
            style: AppTypography.caption
                .copyWith(letterSpacing: 0.6, fontWeight: FontWeight.w600),
          ),
        ),
        _PostGrid(userId: userId),
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
      showDragHandle: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (sheetContext) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(
              AppSpacing.md, 0, AppSpacing.md, AppSpacing.md),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 「举报」与「拉黑」**并列、不分主次**（与迷你卡菜单同一口径）。
              //
              // ⚠️ 已举报过 → 文案换成「已举报 / 点击可再次举报」并用品牌色，
              // **必须读起来像「还能再点」而不是禁用态**：再报一次是有意义的
              // （每次的类型独立留存，第一次报骚扰、第二次报仿冒正是问题在升级的证据）。
              _ActionTile(
                itemKey: const ValueKey('profileMenuReport'),
                emoji: profile.reported ? '📌' : '🚩',
                label: profile.reported ? l10n.accountReportedAction : l10n.accountReportAction,
                subtitle: profile.reported
                    ? l10n.accountReportedActionSub
                    : l10n.accountReportActionSub,
                labelColor: profile.reported ? AppColors.mint : AppColors.ink,
                onTap: () => Navigator.of(sheetContext).pop(_ProfileAction.report),
              ),
              // ⚠️ 举报之后**拉黑项照常可点、不置灰不隐藏**：拉黑带来一个举报没有的效果 ——
              // 从此进不去对方主页。以「已举报」为由禁掉它是错的。
              _ActionTile(
                itemKey: const ValueKey('profileMenuBlock'),
                emoji: '🚫',
                label: l10n.blockUserAction,
                subtitle: l10n.blockUserActionSub,
                onTap: () => Navigator.of(sheetContext).pop(_ProfileAction.block),
              ),
              const SizedBox(height: AppSpacing.xs),
              // UI 稿 C3 明确要一个**显式**的「取消」：点遮罩也能收起，但显式按钮更明确。
              TextButton(
                key: const ValueKey('profileMenuCancel'),
                onPressed: () => Navigator.of(sheetContext).pop(),
                child: Text(l10n.commonCancel),
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
  const _PostGrid({required this.userId});

  final int userId;

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
      error: (_, _) => Padding(
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
      data: (page) {
        if (page.items.isEmpty) {
          return Padding(
            padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
            child: Text(
              // ⚠️ 文案刻意**不提「私密」二字** —— 「他还有私密内容没给你看」同样是
              // 不该外泄的信息，而「暂时没有公开内容」对两种情况都成立。
              l10n.profilePostsEmpty,
              key: const ValueKey('profilePostsEmpty'),
              style: AppTypography.caption,
            ),
          );
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
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

  /// 追加下一页。失败只提示一声，**不动已加载的网格**。
  Future<void> _loadMore(BuildContext context, WidgetRef ref, AppLocalizations l10n) async {
    try {
      await ref.read(publicUserPostsProvider(userId).notifier).loadMore();
    } catch (_) {
      if (context.mounted) showAppToast(context, l10n.profileLoadFailed);
    }
  }
}

/// 主页宠物区（V1.3.0 batch-b1 Story 2.3 · AC3）：头像 / 名字 / 物种 · 年龄 · Diary 数
/// +「Lihat →」，点进去是那只宠物的**访客视图**。
///
/// 视觉照 `me_page.dart` 的 `petmini`（紫浅底圆角行），两边是同一种行。
///
/// ## 🔴 点进去要登录（AC1），但卡片本身游客也看得见
/// 站内访客接口**仅对登录用户开放**（AD-4 Rule 1），所以跳转前走 FR-0C 登录门控；
/// 而「看这人养了只什么」与「看这人是谁」同一档，不需要登录。两层边界不同是刻意的。
///
/// ## ⚠️ Tailsonality 角色小标位是**天然空状态**
/// FR-117 在批次 B2。这里**不做占位设计** —— 一个「敬请期待」的灰条比什么都没有更碍眼。
class _PetSection extends ConsumerWidget {
  const _PetSection({required this.userId});

  final int userId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final pet = ref.watch(publicProfilePetProvider(userId)).value;
    // 没有宠物 / 还在取 / 取失败 → 整块不渲染。
    // 🛡 失败也不给错误态：这是主页上的一个装饰区块，为它摆一条红字会喧宾夺主
    //    （身份区与内容区各自已经有自己的失败态）。
    if (pet == null) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: AppSpacing.md),
      child: GestureDetector(
        key: const ValueKey('profilePetCard'),
        onTap: () => _open(context, ref, pet),
        child: Container(
          padding: const EdgeInsets.all(11),
          decoration: BoxDecoration(
            color: AppColors.mintTint2, // 与「我的」页 petmini 同一个底色
            borderRadius: BorderRadius.circular(13),
          ),
          child: Row(
            children: [
              CircleAvatar(
                radius: 21,
                backgroundColor: AppColors.border,
                backgroundImage: AppImage.provider(pet.avatarUrl, thumbWidth: 160),
                child: (pet.avatarUrl == null || pet.avatarUrl!.isEmpty)
                    ? const Icon(Icons.pets, size: 20, color: AppColors.textTertiary)
                    : null,
              ),
              const SizedBox(width: 11),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      pet.name,
                      style: AppTypography.body.copyWith(fontWeight: FontWeight.w700),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 2),
                    Text(
                      _meta(l10n, pet),
                      key: const ValueKey('profilePetMeta'),
                      style: AppTypography.caption.copyWith(color: AppColors.textSecondary),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              Text(
                '${l10n.meViewArchive} →',
                style: const TextStyle(
                    fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.mint),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 「种类 · 年龄 · Diary 数」—— **逐项复用「我的」页那套出口**
  /// （物种文案 / `formatPetAge` / `meDiaryCount`），两处不另起口径。
  String _meta(AppLocalizations l10n, PublicProfilePet pet) {
    final species = switch (pet.petType) {
      'CAT' => l10n.petTypeCat,
      'DOG' => l10n.petTypeDog,
      'OTHER' => l10n.petTypeOther,
      _ => null,
    };
    return [
      ?species,
      // 不满 1 个月按天表达，避免「0th 0bln」（与档案页同一出口）。
      ?formatPetAge(l10n, pet.birthday),
      l10n.meDiaryCount(pet.diaryCount > 99 ? '99+' : '${pet.diaryCount}'),
    ].join(' · ');
  }

  /// FR-0C：游客点进访客视图 → 强登录引导，**不发请求**（站内访客接口仅登录可用）。
  void _open(BuildContext context, WidgetRef ref, PublicProfilePet pet) {
    requireLogin(ref, context,
        onAllowed: () => context.push('${VisitorArchiveView.inAppRouteBase}/${pet.petId}'));
  }
}

/// 「编辑资料」按钮（Story 2.4 · AC3）：跳**既有**的资料编辑抽屉，**不重画**。
///
/// 视觉照 `me_page.dart` 的 `editbtn`（圆角 8 + 1.5px 浅紫描边 + 紫字，无图标）——
/// 两处是同一个按钮，用户不该在两个地方看到两种样子。
class _EditProfileButton extends ConsumerWidget {
  const _EditProfileButton({required this.userId});

  final int userId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return OutlinedButton(
      key: const ValueKey('profileEditButton'),
      onPressed: () async {
        await openProfileEditSheet(context, ref);
        // 🔴 `context.mounted` 不可省：保存请求在途时用户按返回退出主页，
        // 这一行就会在**已销毁的 ref** 上调 invalidate —— riverpod 3 的
        // `_assertNotDisposed()` 是真抛 StateError（不是 assert，release 也抛），
        // 而这里没有 catch，出去就是一条未捕获异步异常（code-review 2026-09-15）。
        if (!context.mounted) return;
        // 改完昵称 / 签名 / 头像要让这一页跟上 —— 不刷的话用户改完回到自己的主页，
        // 看到的还是旧昵称，会以为没保存成功（而 authController 那份已经更新了，
        // 「我的」Tab 是对的，两处对不上更糟）。
        ref.invalidate(publicProfileProvider(userId));
      },
      style: OutlinedButton.styleFrom(
        foregroundColor: AppColors.accentGrowth,
        side: const BorderSide(color: AppColors.dashedViolet, width: 1.5),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        minimumSize: Size.zero,
        tapTargetSize: MaterialTapTargetSize.shrinkWrap,
      ),
      child: Text(
        l10n.meEditButton,
        style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
      ),
    );
  }
}

enum _ProfileAction { report, block }

/// 抽屉里的一项：emoji + 主文案 14/w600 + 副标题 12（规格取自迷你卡菜单，逐条对齐）。
class _ActionTile extends StatelessWidget {
  const _ActionTile({
    required this.itemKey,
    required this.emoji,
    required this.label,
    required this.subtitle,
    required this.onTap,
    this.labelColor = AppColors.ink,
  });

  final Key itemKey;
  final String emoji;
  final String label;
  final String subtitle;
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
                            fontSize: 14, fontWeight: FontWeight.w600, color: labelColor)),
                    const SizedBox(height: 2),
                    Text(subtitle,
                        style: const TextStyle(fontSize: 12, color: AppColors.textSecondary)),
                  ],
                ),
              ),
            ],
          ),
        ),
      );
}

/// 页面级空态 / 失败态（本 story 的通用兜底；完整视觉由 Story 2.5 补）。
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
              Icon(icon, size: 40, color: AppColors.textTertiary),
              const SizedBox(height: AppSpacing.sm),
              Text(message, style: AppTypography.caption, textAlign: TextAlign.center),
              if (action != null) ...[const SizedBox(height: AppSpacing.xs), action!],
            ],
          ),
        ),
      );
}
