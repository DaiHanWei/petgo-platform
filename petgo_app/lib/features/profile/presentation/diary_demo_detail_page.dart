import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/router/route_intent.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/letter_avatar.dart';
import '../../../core/analytics/analytics.dart';
import '../../auth/domain/auth_guard.dart';
import '../domain/diary_demo_data.dart';
import '../domain/timeline_item.dart';

/// 示例内容详情（V1.1.2 Story 2.2 · AC8）。游客点示例时间线上**带图的三条**即进本页，**无需登录**。
///
/// 存在的理由：游客在种草页点了一条内容却被弹登录框，是最劝退的一种打断。让他先看完整一条
/// 「我也能有这样一条」的内容，再由主 CTA 承担引导。
///
/// 两条硬约束：
/// - **零网络**（NFR-5 延续到详情层）：图文全部来自 [DiaryDemoData] 内置常量。
/// - **不新增受控路由**：本页由游客页 `Navigator.push` **页内推入**。
///   不得挂在 `/profile/...` 下（AD-7 前缀受控，只精确放行 Diary 主页，挂进去会被 redirect 弹回），
///   也不得复用 `/content/:id`（示例条目无后端 ID，会 404）。
///
/// 互动栏（点赞 / 评论 / 举报）**照常展示**以保持真实详情页的完整观感，但点击任意一个都触发建档引导，
/// 不执行真实互动。
class DiaryDemoDetailPage extends ConsumerWidget {
  const DiaryDemoDetailPage({super.key, required this.item});

  final TimelineItem item;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        backgroundColor: AppColors.base,
        actions: [
          IconButton(
            key: const ValueKey('demoDetailMenu'),
            icon: const Icon(Icons.more_horiz, color: AppColors.ink),
            onPressed: () => _showMoreSheet(context, ref, l10n),
          ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(AppSpacing.screenEdge),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _authorRow(context, l10n),
              const SizedBox(height: AppSpacing.md),
              Text(DiaryDemoData.detailBody(l10n, item), style: AppTypography.body),
              const SizedBox(height: AppSpacing.md),
              _photos(),
              const SizedBox(height: AppSpacing.md),
              _interactionBar(context, ref, l10n),
              const Divider(height: AppSpacing.xl, color: AppColors.divider),
              Text('${l10n.detailCommentsTitle.toUpperCase()} (${DiaryDemoData.detailCommentCount})',
                  style: AppTypography.caption.copyWith(
                      fontWeight: FontWeight.w700, letterSpacing: 0.5, color: AppColors.ink2)),
              const SizedBox(height: AppSpacing.md),
              // 示例不伪造评论内容：只保留计数与「参与进来」引导，点击同样走建档引导。
              _commentTeaser(context, ref, l10n),
            ],
          ),
        ),
      ),
    );
  }

  Widget _authorRow(BuildContext context, AppLocalizations l10n) {
    final name = l10n.diaryDemoOwnerName;
    return Row(
      children: [
        LetterAvatar(name: name, size: 36),
        const SizedBox(width: AppSpacing.sm),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(name, style: AppTypography.body.copyWith(fontWeight: FontWeight.w700)),
              Text(l10n.diaryDemoChip,
                  style: AppTypography.caption.copyWith(color: AppColors.textTertiary)),
            ],
          ),
        ),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
          decoration: BoxDecoration(
              color: AppColors.momenBadgeBg, borderRadius: BorderRadius.circular(7)),
          child: Text(l10n.mePostTypeMomen,
              style: const TextStyle(
                  fontSize: 11, fontWeight: FontWeight.w700, color: AppColors.momenBadgeText)),
        ),
      ],
    );
  }

  /// 内置配图（1~2 张）。多张时左右滑 + 页点，与真实详情页轮播观感一致。
  Widget _photos() {
    final urls = item.imageUrls;
    if (urls.isEmpty) return const SizedBox.shrink();
    if (urls.length == 1) return _photo(urls.first);
    return _DemoCarousel(urls: urls);
  }

  static Widget _photo(String url) => ClipRRect(
        borderRadius: BorderRadius.circular(14),
        child: AspectRatio(
          aspectRatio: 4 / 3,
          child: AppImage.widget(url, fit: BoxFit.cover),
        ),
      );

  Widget _interactionBar(BuildContext context, WidgetRef ref, AppLocalizations l10n) => Row(
        children: [
          _iconCount(
            key: const ValueKey('demoDetailLike'),
            icon: Icons.favorite_border,
            count: DiaryDemoData.detailLikeCount,
            onTap: () => _startCreateProfile(context, ref, source: 'demo_detail_interaction'),
          ),
          const SizedBox(width: AppSpacing.lg),
          _iconCount(
            key: const ValueKey('demoDetailComment'),
            icon: Icons.mode_comment_outlined,
            count: DiaryDemoData.detailCommentCount,
            onTap: () => _startCreateProfile(context, ref, source: 'demo_detail_interaction'),
          ),
        ],
      );

  static Widget _iconCount({
    required Key key,
    required IconData icon,
    required int count,
    required VoidCallback onTap,
  }) =>
      GestureDetector(
        key: key,
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Row(
          children: [
            Icon(icon, size: 20, color: AppColors.textSecondary),
            const SizedBox(width: AppSpacing.xs),
            Text('$count', style: AppTypography.caption),
          ],
        ),
      );

  Widget _commentTeaser(BuildContext context, WidgetRef ref, AppLocalizations l10n) =>
      GestureDetector(
        key: const ValueKey('demoDetailCommentTeaser'),
        behavior: HitTestBehavior.opaque,
        onTap: () => _startCreateProfile(context, ref, source: 'demo_detail_interaction'),
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: AppColors.cream2,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Text(l10n.diaryDemoCommentTeaser,
              style: const TextStyle(fontSize: 12.5, color: AppColors.ink2)),
        ),
      );

  /// 「···」举报入口（与真实详情页同形态的底抽屉，单一动作行）。点击 → 建档引导，不发举报。
  void _showMoreSheet(BuildContext context, WidgetRef ref, AppLocalizations l10n) {
    showModalBottomSheet<void>(
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
                key: const ValueKey('demoDetailMenuReport'),
                onTap: () {
                  Navigator.of(sheetCtx).pop();
                  _startCreateProfile(context, ref, source: 'demo_detail_interaction');
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
                      Text(l10n.detailMoreReportContent,
                          style: const TextStyle(
                              fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.popRed)),
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

  /// 与游客页同一个建档引导入口（单一门控入口 [requireLogin]），不另写一套跳转。
  /// 与游客页同一个事件名（T-4，Story 6.1）。本页四个互动点同属 `detail_interaction`
  /// —— 词表按 AC3 只到「入口类别」粒度，不细分到具体按钮。
  void _startCreateProfile(BuildContext context, WidgetRef ref, {required String source}) {
    Analytics.capture('diary_guest_create_profile_cta_tapped', {'source': source});
    requireLogin(
      ref,
      context,
      pendingAction: const RouteIntent(location: '/profile/create'),
      onAllowed: () => context.push('/profile/create'),
      entrySource: 'diary_cta',

    );
  }
}

/// 示例多图轮播（左右滑 + 页点）。零网络：图片全部为内置资源。
class _DemoCarousel extends StatefulWidget {
  const _DemoCarousel({required this.urls});

  final List<String> urls;

  @override
  State<_DemoCarousel> createState() => _DemoCarouselState();
}

class _DemoCarouselState extends State<_DemoCarousel> {
  int _page = 0;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        AspectRatio(
          aspectRatio: 4 / 3,
          child: PageView(
            key: const ValueKey('demoDetailCarousel'),
            onPageChanged: (i) => setState(() => _page = i),
            children: [
              for (final url in widget.urls) DiaryDemoDetailPage._photo(url),
            ],
          ),
        ),
        const SizedBox(height: 8),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            for (var i = 0; i < widget.urls.length; i++)
              Container(
                width: 6,
                height: 6,
                margin: const EdgeInsets.symmetric(horizontal: 3),
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: i == _page ? AppColors.mint : AppColors.line,
                ),
              ),
          ],
        ),
      ],
    );
  }
}
