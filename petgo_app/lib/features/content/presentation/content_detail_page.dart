import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/rounded.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../features/auth/domain/auth_state.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../domain/content_detail.dart';
import 'comment_composer.dart';
import 'comment_section.dart';
import 'detail_providers.dart';
import 'like_button.dart';

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
          // 「···」菜单：举报[3.7] + 作者本人删除[3.6]，本 Story 占位 disabled。
          PopupMenuButton<String>(
            key: const ValueKey('detailMenu'),
            itemBuilder: (context) => [
              PopupMenuItem<String>(value: 'report', enabled: false, child: Text(l10n.detailMenuReport)),
              if (detail.isAuthor)
                PopupMenuItem<String>(value: 'delete', enabled: false, child: Text(l10n.detailMenuDelete)),
            ],
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(AppSpacing.screenEdge),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _authorRow(l10n),
                    const SizedBox(height: AppSpacing.md),
                    if (detail.body != null && detail.body!.isNotEmpty)
                      Text(detail.body!, style: AppTypography.body),
                    if (detail.imageUrls.isNotEmpty) ...[
                      const SizedBox(height: AppSpacing.md),
                      _ImageCarousel(urls: detail.imageUrls),
                    ],
                    const SizedBox(height: AppSpacing.md),
                    _interactionBar(),
                    const Divider(height: AppSpacing.xl, color: AppColors.divider),
                    CommentSection(
                      postId: postId,
                      currentUserId: currentUserId,
                      isContentAuthor: detail.isAuthor,
                    ),
                  ],
                ),
              ),
            ),
            CommentComposer(postId: postId),
          ],
        ),
      ),
    );
  }

  Widget _authorRow(AppLocalizations l10n) {
    final name = detail.authorDeleted ? l10n.feedDeletedUser : (detail.authorNickname ?? l10n.feedDeletedUser);
    final avatar = detail.authorDeleted ? null : detail.authorAvatarUrl;
    final row = Row(
      children: [
        CircleAvatar(
          radius: 16,
          backgroundColor: AppColors.border,
          backgroundImage: (avatar != null && avatar.isNotEmpty) ? NetworkImage(avatar) : null,
          child: (avatar == null || avatar.isEmpty)
              ? const Icon(Icons.person_rounded, size: 18, color: AppColors.textTertiary)
              : null,
        ),
        const SizedBox(width: AppSpacing.sm),
        Text(name, style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
      ],
    );
    // 作者点击触发迷你卡[3.8]：注销作者不可点（NFR-8）；本 Story 仅占位（不可点 = 不挂手势）。
    return row;
  }

  Widget _interactionBar() {
    // 点赞按钮（Story 3.4，乐观更新）+ 评论数读取展示。卡片不展示计数，详情互动栏展示。
    return Row(
      children: [
        LikeButton(
          postId: detail.id,
          initialLiked: detail.liked,
          initialCount: detail.likeCount,
        ),
        const SizedBox(width: AppSpacing.lg),
        const Icon(Icons.mode_comment_outlined, size: 20, color: AppColors.textSecondary),
        const SizedBox(width: AppSpacing.xs),
        Text('${detail.commentCount}', style: AppTypography.caption),
      ],
    );
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
      builder: (_) => Scaffold(
        backgroundColor: Colors.black,
        appBar: AppBar(backgroundColor: Colors.black),
        body: Center(
          child: InteractiveViewer(child: Image.network(widget.urls[index])),
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
                child: Image.network(
                  widget.urls[i],
                  fit: BoxFit.cover,
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
