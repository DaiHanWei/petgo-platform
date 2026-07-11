import 'package:flutter/material.dart';
import '../../../shared/widgets/app_toast.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/rounded.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../features/auth/domain/auth_state.dart';
import '../../../features/me/data/my_posts_repository.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/letter_avatar.dart';
import '../../../shared/widgets/mini_profile_sheet.dart';
import '../../profile/data/timeline_repository.dart';
import '../data/detail_repository.dart';
import '../domain/content_detail.dart';
import '../domain/content_type.dart';
import 'comment_composer.dart';
import 'comment_section.dart';
import 'detail_providers.dart';
import 'feed_controller.dart';
import 'like_button.dart';
import 'report_sheet.dart';

/// 内容详情页（Story 3.3，FR-28）。只读容器：正文 + 多图左右滑 + 互动栏占位 + 评论区 + 底部评论框。
///
/// 多态完整（UX-DR18）：404 失效页 / 403 无权限页 / 网络错误 / 加载骨架。
/// 「···」举报入口[3.7] + 作者删除入口[3.6]、点赞按钮行为[3.4]、作者点击迷你卡[3.8] 本 Story 仅占位。
class ContentDetailPage extends ConsumerWidget {
  const ContentDetailPage({super.key, required this.postId});

  final int postId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final detailAsync = ref.watch(detailProvider(postId));
    // 评论发表/删除后重拉详情（更新 commentCount）。
    ref.listen<int>(commentsRefreshProvider, (prev, next) => ref.invalidate(detailProvider(postId)));

    return detailAsync.when(
      loading: () => _shell(context, body: const Center(
          child: CircularProgressIndicator(color: AppColors.accentGrowth))),
      error: (err, _) => _shell(context, body: _errorBody(context, ref, l10n, err)),
      data: (d) => _DetailScaffold(postId: postId, detail: d),
    );
  }

  /// 加载/错误态的极简外壳（仅返回按钮，无「···」菜单）。
  Widget _shell(BuildContext context, {required Widget body}) {
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(backgroundColor: AppColors.base),
      body: body,
    );
  }

  Widget _errorBody(BuildContext context, WidgetRef ref, AppLocalizations l10n, Object err) {
    final kind = err is ContentLoadError ? err.kind : ContentLoadErrorKind.network;
    final String title;
    final IconData icon;
    switch (kind) {
      case ContentLoadErrorKind.gone:
        title = l10n.detailGoneTitle; // 统一文案，不暴露资源曾否存在
        icon = Icons.search_off_rounded;
      case ContentLoadErrorKind.forbidden:
        title = l10n.detailForbiddenTitle;
        icon = Icons.lock_outline_rounded;
      case ContentLoadErrorKind.network:
        title = l10n.detailNetworkError;
        icon = Icons.cloud_off_rounded;
    }
    return EmptyState(
      title: title,
      icon: icon,
      actionLabel: l10n.detailBackToFeed,
      onAction: () => Navigator.of(context).maybePop(),
    );
  }
}

class _DetailScaffold extends ConsumerWidget {
  const _DetailScaffold({required this.postId, required this.detail});

  final int postId;
  final ContentDetail detail;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final currentUserId = ref.watch(authControllerProvider).profile?.id;
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        backgroundColor: AppColors.base,
        actions: [
          // 「···」更多：原型 detail.html 为底抽屉。按归属互斥（自己→删除[3.6] / 他人→举报[3.7]，
          // 游客点举报由 openReport 触发 FR-0C）。绝不同时出现举报与删除。
          IconButton(
            key: const ValueKey('detailMenu'),
            icon: const Icon(Icons.more_horiz, color: AppColors.ink),
            onPressed: () => _showMoreSheet(context, ref, detail, l10n),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              // 点空白 / 滚动 → 收起评论键盘（仅返回键收回的体验问题修复）。
              child: GestureDetector(
                behavior: HitTestBehavior.translucent,
                onTap: () => FocusScope.of(context).unfocus(),
                child: SingleChildScrollView(
                  keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
                  padding: const EdgeInsets.all(AppSpacing.screenEdge),
                  child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _authorRow(context, ref, l10n),
                    const SizedBox(height: AppSpacing.md),
                    if (detail.body != null && detail.body!.isNotEmpty)
                      Text(detail.body!, style: AppTypography.body),
                    if (detail.imageUrls.isNotEmpty) ...[
                      const SizedBox(height: AppSpacing.md),
                      _ImageCarousel(urls: detail.imageUrls),
                    ],
                    const SizedBox(height: AppSpacing.md),
                    _interactionBar(ref),
                    const Divider(height: AppSpacing.xl, color: AppColors.divider),
                    // KOMENTAR (n) 计数标题（detail.html）。
                    Text('${l10n.detailCommentsTitle.toUpperCase()} (${detail.commentCount})',
                        style: AppTypography.caption.copyWith(
                            fontWeight: FontWeight.w700,
                            letterSpacing: 0.5,
                            color: AppColors.ink2)),
                    const SizedBox(height: AppSpacing.sm),
                    CommentSection(
                      postId: postId,
                      currentUserId: currentUserId,
                      isContentAuthor: detail.isAuthor,
                    ),
                  ],
                ),
                ),
              ),
            ),
            CommentComposer(postId: postId),
          ],
        ),
      ),
    );
  }

  /// 发布相对时间（双语 l10n）：<1分→刚刚 / <1时→N分钟前 / <1天→N小时前 / 否则 N天前。
  static String _relativeTime(AppLocalizations l10n, DateTime t) {
    final d = DateTime.now().difference(t);
    if (d.inMinutes < 1) return l10n.timeJustNow;
    if (d.inHours < 1) return l10n.timeMinutesAgo(d.inMinutes);
    if (d.inDays < 1) return l10n.timeHoursAgo(d.inHours);
    return l10n.timeDaysAgo(d.inDays);
  }

  /// 内容类型徽章（detail.html 作者行右侧 chip）：Momen 绿 / Tips 黄 / Cerita 紫。
  static (String, Color, Color) _typeBadge(String type, AppLocalizations l10n) => switch (type) {
        'GROWTH_MOMENT' => (l10n.mePostTypeMomen, AppColors.momenBadgeText, AppColors.momenBadgeBg),
        'KNOWLEDGE' => (l10n.mePostTypeTips, AppColors.tipsBadgeText, AppColors.goldTint),
        _ => (l10n.mePostTypeCerita, AppColors.mint, AppColors.skyTint),
      };

  Widget _authorRow(BuildContext context, WidgetRef ref, AppLocalizations l10n) {
    final name = detail.authorDeleted ? l10n.feedDeletedUser : (detail.authorNickname ?? l10n.feedDeletedUser);
    final (badgeLabel, badgeFg, badgeBg) = _typeBadge(detail.type, l10n);
    final row = Row(
      children: [
        // 头像着色与列表卡片共用同一算法（LetterAvatar），保证同一用户两处颜色一致。
        LetterAvatar(
          url: detail.authorDeleted ? null : detail.authorAvatarUrl,
          name: name,
          deleted: detail.authorDeleted,
          size: 36,
        ),
        const SizedBox(width: AppSpacing.sm),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(name,
                  style: AppTypography.body.copyWith(fontWeight: FontWeight.w700),
                  maxLines: 1, overflow: TextOverflow.ellipsis),
              Text(_relativeTime(l10n, detail.createdAt),
                  style: AppTypography.caption.copyWith(color: AppColors.textTertiary)),
            ],
          ),
        ),
        const SizedBox(width: AppSpacing.sm),
        // 分类彩徽章。
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
          decoration: BoxDecoration(color: badgeBg, borderRadius: BorderRadius.circular(7)),
          child: Text(badgeLabel,
              style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: badgeFg)),
        ),
      ],
    );
    // 作者点击触发迷你卡（Story 3.8）：注销作者不可点（NFR-8）。
    if (detail.authorDeleted) return row;
    return GestureDetector(
      key: const ValueKey('detailAuthorRow'),
      onTap: () => showMiniProfile(context, ref, detail.authorId),
      child: row,
    );
  }

  Widget _interactionBar(WidgetRef ref) {
    // 点赞按钮（Story 3.4，乐观更新）+ 评论数读取展示。卡片不展示计数，详情互动栏展示。
    return Row(
      children: [
        LikeButton(
          postId: detail.id,
          initialLiked: detail.liked,
          initialCount: detail.likeCount,
        ),
        const SizedBox(width: AppSpacing.lg),
        // 点评论图标/计数 → 聚焦底部评论框弹键盘（游客转登录引导）。
        GestureDetector(
          key: const ValueKey('detailCommentIcon'),
          behavior: HitTestBehavior.opaque,
          onTap: () => ref.read(commentFocusProvider.notifier).requestFocus(),
          child: Row(
            children: [
              const Icon(Icons.mode_comment_outlined, size: 20, color: AppColors.textSecondary),
              const SizedBox(width: AppSpacing.xs),
              Text('${detail.commentCount}', style: AppTypography.caption),
            ],
          ),
        ),
      ],
    );
  }

  /// 「···」更多底抽屉（原型 detail.html `detail-more-sheet`）：把手 + 单一互斥动作（红字行）+ Batal。
  /// 自己内容 → 🗑 删除；他人内容 → 🚩 举报。
  void _showMoreSheet(
      BuildContext context, WidgetRef ref, ContentDetail detail, AppLocalizations l10n) {
    final isAuthor = detail.isAuthor;
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
              // 单一互斥动作行（红字 + emoji，左对齐，底分隔线）。
              InkWell(
                key: ValueKey(isAuthor ? 'detailMenuDelete' : 'detailMenuReport'),
                onTap: () {
                  Navigator.of(sheetCtx).pop();
                  if (isAuthor) {
                    _confirmDelete(context, ref, l10n);
                  } else {
                    openReport(context, ref, detail.id, onReported: () {
                      // cm-6 §6.1：详情页举报成功 → pop 回列表（该帖对本人已不存在，后端详情 404）+ 提示。
                      // 同步乐观移除 Feed 中的该卡片（若在列表；后端 §5.4 刷新亦已过滤）。
                      ref.read(feedProvider.notifier).removeItem(detail.id);
                      if (context.mounted) {
                        showAppToast(context, l10n.reportHiddenToast);
                        Navigator.of(context).maybePop();
                      }
                    });
                  }
                },
                child: Container(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  decoration: const BoxDecoration(
                    border: Border(bottom: BorderSide(color: AppColors.line2)),
                  ),
                  child: Row(
                    children: [
                      Text(isAuthor ? '🗑' : '🚩', style: const TextStyle(fontSize: 16)),
                      const SizedBox(width: 10),
                      Text(
                        isAuthor ? l10n.detailMoreDeleteContent : l10n.detailMoreReportContent,
                        style: const TextStyle(
                            fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.popRed),
                      ),
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

  /// 二次确认删除（Story 3.6 · 原型 detail.html `delete-confirm-sheet`）：底抽屉 ⚠️ + 标题 + 正文
  /// + 红 Hapus + Batal。确认 → 删除 → 刷新 Feed + 返回（该帖已不在列表，详情走 404）。
  Future<void> _confirmDelete(BuildContext context, WidgetRef ref, AppLocalizations l10n) async {
    final ok = await showConfirmSheet(
      context,
      title: l10n.contentDeleteTitle,
      message: l10n.contentDeleteConfirm,
      confirmLabel: l10n.detailMenuDelete,
      cancelLabel: l10n.commonCancel,
      icon: Icons.delete_outline_rounded,
      danger: true,
      confirmKey: const ValueKey('confirmDeleteContent'),
    );
    if (!ok) return;
    try {
      await ref.read(detailRepositoryProvider).deleteContent(detail.id);
      // Feed + me 页「我的发布」同步移除（重拉，软删帖 deleted_at 非空被过滤）。
      ref.invalidate(feedProvider);
      ref.invalidate(myPostsProvider);
      // 成长日历帖还出现在档案/时间线/日历视图，删后一并刷新（否则那些页仍显旧帖直到重启）。
      if (detail.type == ContentType.growthMoment.wire) {
        ref.invalidate(timelineFirstPageProvider);
        ref.invalidate(archiveStatsProvider);
        ref.invalidate(calendarMonthProvider);
        ref.invalidate(dayDetailProvider);
      }
      if (context.mounted) Navigator.of(context).maybePop();
    } catch (_) {
      if (context.mounted) {
        showAppToast(context, l10n.contentDeleteFailed);
      }
    }
  }
}

/// 多图左右滑 + 角标 x/y（UX-DR12）；点击全屏 lightbox。
class _ImageCarousel extends StatefulWidget {
  const _ImageCarousel({required this.urls});

  final List<String> urls;

  @override
  State<_ImageCarousel> createState() => _ImageCarouselState();
}

class _ImageCarouselState extends State<_ImageCarousel> {
  final PageController _controller = PageController();
  int _current = 0;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _openLightbox(int index) {
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (ctx) => Scaffold(
        backgroundColor: Colors.black,
        appBar: AppBar(backgroundColor: Colors.black),
        // 点击图片（或黑边）关闭大图（bug 20260701-192，对齐主流看图 App 交互）。
        body: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: () => Navigator.of(ctx).pop(),
          child: Center(
            child: InteractiveViewer(child: AppImage.widget(widget.urls[index], fit: BoxFit.contain)),
          ),
        ),
      ),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        ClipRRect(
          borderRadius: AppRounded.phoneRadius,
          child: AspectRatio(
            aspectRatio: 1,
            child: PageView.builder(
              controller: _controller,
              itemCount: widget.urls.length,
              onPageChanged: (i) => setState(() => _current = i),
              itemBuilder: (context, i) => GestureDetector(
                onTap: () => _openLightbox(i),
                child: AppImage.widget(
                  widget.urls[i],
                  fit: BoxFit.cover,
                  thumbWidth: 1080, // 详情方图：按手机全宽取缩略图（全屏放大走原图）
                  errorBuilder: (context, error, stack) =>
                      Container(color: AppColors.border),
                ),
              ),
            ),
          ),
        ),
        if (widget.urls.length > 1)
          Positioned(
            top: AppSpacing.sm,
            right: AppSpacing.sm,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: AppSpacing.sm, vertical: AppSpacing.xxs),
              decoration: BoxDecoration(
                color: Colors.black54,
                borderRadius: AppRounded.smRadius,
              ),
              child: Text(
                '${_current + 1}/${widget.urls.length}',
                style: AppTypography.micro.copyWith(color: AppColors.onAccent),
              ),
            ),
          ),
      ],
    );
  }
}
