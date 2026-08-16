import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../../shared/widgets/letter_avatar.dart';
import '../data/blocked_users_repository.dart';
import '../domain/account_action_entry.dart';
import '../domain/blocked_user.dart';
import 'account_report_sheet.dart';
import 'blocked_list_skeleton.dart';

/// 黑名单管理页（Story 1.5，FR-94 · UI 稿 D 泳道）。
///
/// 「我主动拉黑了谁」的清单 + 逐个解除。**举报产生的隐藏不在这里**（它没有解除入口，
/// 混进来只会给用户一个他解除不掉的条目）。
///
/// 三个容易做反的点：
/// 1. **不用任何滑动手势**——右滑撞系统返回，左滑会压在「解除拉黑」上误触。站内本来也没有滑动删除的列表。
/// 2. **加载失败必须显式报错 + 重试**，绝不把请求错误静默画成空态（bug 20260625-088 的血泪教训）。
/// 3. **注销者的「解除拉黑」按钮不许禁用**——头像昵称去点击态，但用户仍需能清理名单。
class BlockedUsersPage extends ConsumerStatefulWidget {
  const BlockedUsersPage({super.key});

  @override
  ConsumerState<BlockedUsersPage> createState() => _BlockedUsersPageState();
}

class _BlockedUsersPageState extends ConsumerState<BlockedUsersPage> {
  /// 本地已解除的 id：解除成功后立刻把行抹掉，不必等一次网络往返。
  ///
  /// 为什么不直接 `ref.invalidate`：那会把 provider 打回 loading，整页闪一下骨架屏——
  /// 用户刚点完一个按钮，看到的却是整页重载。下次进页会强制刷新，权威数据以服务端为准。
  final Set<int> _removed = <int>{};

  /// 本次会话里刚举报过的人：用来立刻点亮「已举报」标签。
  ///
  /// 不走 `ref.invalidate` 重拉列表 —— 那会让整页闪一下骨架屏，而用户只是给某一行加了个标签。
  /// 下次进页会强制刷新，权威数据以服务端为准（标签本身是服务端持久化的，Story 2.1 AC8）。
  final Set<int> _justReported = <int>{};

  @override
  void initState() {
    super.initState();
    // 进页强制刷新：黑名单会在页外被改（迷你卡里随时能拉黑人），不能沿用上次的缓存。
    // microtask 避开 initState 期改 provider 的限制；失败不在这里处理，走 build 的 error 分支。
    Future.microtask(() {
      if (mounted) ref.invalidate(blockedUsersProvider);
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(blockedUsersProvider);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        backgroundColor: AppColors.surface,
        title: Text(l10n.blockedListTitle),
      ),
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: () => ref.refresh(blockedUsersProvider.future),
          child: async.when(
            loading: () => const BlockedListSkeleton(),
            error: (_, _) => _error(l10n),
            data: (items) {
              final visible =
                  items.where((b) => !_removed.contains(b.userId)).toList(growable: false);
              return visible.isEmpty ? _empty(l10n) : _list(l10n, visible);
            },
          ),
        ),
      ),
    );
  }

  Widget _list(AppLocalizations l10n, List<BlockedUser> items) => ListView.separated(
        padding: const EdgeInsets.all(AppSpacing.screenEdge),
        itemCount: items.length + 1,
        separatorBuilder: (_, index) => index == 0
            ? const SizedBox.shrink()
            : const Divider(height: 1, thickness: 1, color: AppColors.line2),
        itemBuilder: (context, index) {
          if (index == 0) {
            // 「他们不会知道你拉黑了他们」——用户点解除时最想确认的就是这件事。
            return Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.md),
              child: Text(l10n.blockedListHint,
                  style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
            );
          }
          return _row(l10n, items[index - 1]);
        },
      );

  Widget _row(AppLocalizations l10n, BlockedUser b) {
    // 注销者：匿名态 + 默认头像。⚠️ 这是「他自己注销了」，不是「被平台封号」——
    // 封号的人后端根本不下发标记，在这里就该跟正常人一模一样。
    final name = b.deleted ? l10n.feedDeletedUser : (b.nickname ?? l10n.feedDeletedUser);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        children: [
          LetterAvatar(
            url: b.deleted ? null : b.avatarUrl,
            name: name,
            deleted: b.deleted,
            size: 40,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Flexible(
                      child: Text(name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
                    ),
                    if (b.reported || _justReported.contains(b.userId)) ...[
                      const SizedBox(width: 6),
                      _reportedTag(l10n),
                    ],
                  ],
                ),
                const SizedBox(height: 2),
                Text(l10n.blockedListBlockedAt(formatDayMonthYear(context, b.blockedAt)),
                    style: AppTypography.caption.copyWith(color: AppColors.textSecondary)),
              ],
            ),
          ),
          const SizedBox(width: 8),
          // 主动作常驻、一眼可见（不藏进菜单、不做滑动手势）。
          // ⚠️ 注销者这个按钮**照样可用**——去点击态只作用于头像与昵称，用户仍需能清理名单。
          OutlinedButton(
            key: ValueKey('blockedUnblock_${b.userId}'),
            onPressed: () => _confirmUnblock(l10n, b),
            style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.ink,
              side: const BorderSide(color: AppColors.line, width: 1.5),
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
            ),
            child: Text(l10n.blockedListUnblock,
                style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
          ),
          // 行内「⋯」→ 举报（Story 2.4）。位置是 Story 1.5 预留好的，间距未动。
          //
          // ⚠️ **这个入口堵的是一条真实的死路**：拉黑之后就进不去对方的迷你主页了，
          // 而迷你主页是举报的**唯一**入口 —— 两条一叠，拉黑等于永久放弃举报他的能力。
          // 而骚扰者恰恰是最容易先被拉黑的那类账号（被骚扰的第一反应是拉黑图清净，
          // 冷静下来想举报时路已经没了），结果就是**越恶劣的账号越不容易被举报到运营手里**。
          SizedBox(
            width: 36,
            child: IconButton(
              key: ValueKey('blockedMore_${b.userId}'),
              icon: const Icon(Icons.more_horiz_rounded, size: 20, color: AppColors.muted),
              padding: EdgeInsets.zero,
              constraints: const BoxConstraints.tightFor(width: 36, height: 36),
              onPressed: () => _showRowMenu(l10n, b),
            ),
          ),
        ],
      ),
    );
  }

  /// 行内「⋯」菜单（Story 2.4，D9）。
  ///
  /// ⚠️ **底部抽屉即可**，不必照搬迷你卡那个「向上弹的浮层」—— 那是因为迷你卡本身就是个底部抽屉、
  /// 向下弹会溢出屏幕底部（C-76）；黑名单页是普通列表页，没有这个约束。
  /// ⚠️ **不用任何滑动手势**：右滑撞系统返回（本页是从设置 push 进来的），
  /// 左滑那片像素正好压在「解除拉黑」上——滑得短是点击、滑得长才是露出，必然误触。
  Future<void> _showRowMenu(AppLocalizations l10n, BlockedUser b) async {
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: AppColors.surface,
      shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
      builder: (sheetCtx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: Container(
                  width: 36,
                  height: 4,
                  margin: const EdgeInsets.only(bottom: 14),
                  decoration: BoxDecoration(
                      color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
                ),
              ),
              InkWell(
                key: ValueKey('blockedMenuReport_${b.userId}'),
                // 已举报过也**照常可点**（允许重复举报——每次的类型独立留存）。
                onTap: () {
                  Navigator.of(sheetCtx).pop();
                  _startReport(b);
                },
                child: Container(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  decoration: const BoxDecoration(
                    border: Border(bottom: BorderSide(color: AppColors.line2)),
                  ),
                  child: Row(
                    children: [
                      const Text('🚩', style: TextStyle(fontSize: 16)),
                      const SizedBox(width: 10),
                      Text(l10n.accountReportAction,
                          style: const TextStyle(
                              fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.ink)),
                    ],
                  ),
                ),
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton(
                  onPressed: () => Navigator.of(sheetCtx).pop(),
                  style: TextButton.styleFrom(
                      foregroundColor: AppColors.textSecondary,
                      padding: const EdgeInsets.symmetric(vertical: 12)),
                  child: Text(l10n.commonCancel),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 从黑名单页发起举报 —— 复用 Story 2.2 的同一套抽屉（同样五类、同样的「提交后无法撤销」）。
  ///
  /// ⚠️ 成功后**不移除该行**：这一行出现在本页是因为它含 `BLOCK`，而举报只是**另外**加一条
  /// `REPORT` 关系、碰都不碰 `BLOCK` 行。移除它会让用户以为拉黑也一起解除了。
  /// 位置同样不变（列表按 `BLOCK` 的拉黑时间排序）。举报成功只是多一个「已举报」标签。
  Future<void> _startReport(BlockedUser b) async {
    final submitted = await openAccountReport(
      context,
      ref,
      b.userId,
      alreadyReported: b.reported || _justReported.contains(b.userId),
      entry: AccountActionEntry.blocklist,
    );
    // 只有真的提交成功（用户点了成功态的「关闭」）才点亮标签；取消与失败都不算。
    if (submitted && mounted) {
      setState(() => _justReported.add(b.userId));
    }
  }

  Widget _reportedTag(AppLocalizations l10n) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
        decoration: BoxDecoration(
          color: AppColors.cream2,
          borderRadius: BorderRadius.circular(6),
        ),
        child: Text(l10n.blockedListReportedTag,
            style: const TextStyle(
                fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.mint)),
      );

  /// 解除二次确认 → 解除 → 移除条目 + Toast。
  ///
  /// ⚠️ 主按钮用**品牌紫**（`danger: false`）：解除是恢复性动作，与拉黑确认的危险红形成对照。
  Future<void> _confirmUnblock(AppLocalizations l10n, BlockedUser b) async {
    final name = b.deleted ? l10n.feedDeletedUser : (b.nickname ?? l10n.feedDeletedUser);
    final overlay = Overlay.maybeOf(context, rootOverlay: true);
    final ok = await showConfirmSheet(
      context,
      title: l10n.unblockConfirmTitle(name),
      // 也被举报过的人：解除拉黑并不会让他的内容回来，必须在动手前就说清楚。
      // ⚠️ 这是全产品唯一会向用户揭示「举报会隐藏内容」的地方——举报成功态刻意不提。
      // 两处口径不同是有意为之，复盘时别当成漏了。
      message: b.reported ? l10n.unblockConfirmBodyReported : l10n.unblockConfirmBody,
      confirmLabel: l10n.blockedListUnblock,
      cancelLabel: l10n.commonCancel,
      icon: Icons.lock_open_rounded,
      confirmKey: const ValueKey('confirmUnblockUser'),
      onConfirm: () async {
        try {
          await ref.read(blockedUsersRepositoryProvider).unblock(b.userId);
          return true;
        } catch (_) {
          if (overlay != null) showAppToastOnOverlay(overlay, l10n.unblockFailed);
          return false; // 抽屉保持打开，用户可直接再点
        }
      },
    );
    if (!ok || !mounted) return;
    setState(() => _removed.add(b.userId)); // 两种情况条目都移除（拉黑关系确实解除了）
    // 「刷新后」不是「立即」：措辞给后续带序列缓存的推荐算法留了余地。
    showAppToast(context, b.reported ? l10n.unblockSuccessReported : l10n.unblockSuccess);
  }

  /// 空态：给出下一步指引（去哪儿拉黑），不是白屏。
  /// 外层必须是可滚组件，否则 `RefreshIndicator` 下拉刷不动。
  Widget _empty(AppLocalizations l10n) => ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
        children: [
          const SizedBox(height: 120),
          const Icon(Icons.block_rounded, size: 48, color: AppColors.textTertiary),
          const SizedBox(height: AppSpacing.md),
          Text(l10n.blockedListEmptyTitle,
              textAlign: TextAlign.center, style: AppTypography.title),
          const SizedBox(height: AppSpacing.sm),
          Text(l10n.blockedListEmptyBody,
              textAlign: TextAlign.center,
              style: AppTypography.body.copyWith(color: AppColors.textSecondary)),
        ],
      );

  /// 失败态：**必须给恢复路径**。绝不把请求错误画成「暂无数据」——
  /// 401/500/超时都会走到这里，画成空态等于告诉用户「你没拉黑过谁」。
  Widget _error(AppLocalizations l10n) => ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
        children: [
          const SizedBox(height: 120),
          Text(l10n.blockedListLoadFailed,
              textAlign: TextAlign.center, style: AppTypography.title),
          const SizedBox(height: AppSpacing.sm),
          Text(l10n.blockedListLoadFailedBody,
              textAlign: TextAlign.center,
              style: AppTypography.body.copyWith(color: AppColors.textSecondary)),
          const SizedBox(height: AppSpacing.lg),
          Center(
            child: OutlinedButton(
              key: const ValueKey('blockedListRetry'),
              onPressed: () => ref.invalidate(blockedUsersProvider),
              child: Text(l10n.feedRetry),
            ),
          ),
        ],
      );
}
