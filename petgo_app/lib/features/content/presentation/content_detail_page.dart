import 'package:flutter/material.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/user_tag_row.dart';
import '../../../shared/widgets/content_tag_chip.dart';
import '../domain/content_tag.dart';
import '../domain/detail_bottom_bar.dart';
import '../domain/detail_image_layout.dart';
import '../domain/feed_image_layout.dart';
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
import '../domain/content_type_badge.dart';
import '../domain/content_type.dart';
import 'comment_composer.dart';
import 'comment_section.dart';
import 'detail_providers.dart';
import 'author_moderation_callbacks.dart';
import 'feed_controller.dart';
import 'report_sheet.dart';
import 'share_card/open_share_card.dart';

/// 内容详情页（Story 3.3，FR-28）。只读容器：正文 + 多图左右滑 + 互动栏占位 + 评论区 + 底部评论框。
///
/// 多态完整（UX-DR18）：404 失效页 / 403 无权限页 / 网络错误 / 加载骨架。
/// 「···」举报入口[3.7] + 作者删除入口[3.6]、点赞按钮行为[3.4]、作者点击迷你卡[3.8] 本 Story 仅占位。
class ContentDetailPage extends ConsumerWidget {
  const ContentDetailPage({super.key, required this.postId, this.focusComments = false});

  final int postId;

  /// 进来就滚到评论区（V1.1.6 Story 3.2 · AC4）。
  ///
  /// 由路由的 `?focus=comments` 注入。⚠️ **这个参数名是既有的** ——
  /// 通知深链早就在产出它，只是详情页一直没消费。首页评论按钮沿用同一个名字，
  /// 满足「两侧必须同名」的要求，也顺带把通知深链的评论锚点接通了。
  final bool focusComments;

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
      data: (d) => _DetailScaffold(postId: postId, detail: d, focusComments: focusComments),
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
  const _DetailScaffold({
    required this.postId,
    required this.detail,
    this.focusComments = false,
  });

  final int postId;
  final ContentDetail detail;
  final bool focusComments;

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
              // 🔴 图片区的高度护栏要「滚动视口的实际高度」。在这里量最准：
              // AppBar 与常驻底栏（CommentComposer）都已被外层扣掉，护栏里不必再减一次
              // —— 重复扣就是 Feed 那次实机复核抓到的同类错误（AD-A11 / Story 2.2 · AC3）。
              child: LayoutBuilder(
                builder: (context, viewport) => GestureDetector(
                behavior: HitTestBehavior.translucent,
                onTap: () => FocusScope.of(context).unfocus(),
                child: SingleChildScrollView(
                  keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
                  // 🔴 横向 padding 从这里撤到各子块上：图片要**通栏全宽出血**（AC2），
                  // 留在这里会让图片两侧各缩进一个 screenEdge —— 那正是本 story 要消灭的「两侧留边」。
                  padding: const EdgeInsets.symmetric(vertical: AppSpacing.screenEdge),
                  child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
                      child: _authorRow(context, ref, l10n),
                    ),
                    // V1.3.0 Story 2.2 · AC1：元素顺序为 作者行 → **图片** → 文字 → 互动栏 → 评论区。
                    // 图片前置的理由：详情页是「看图」的页面，正文在图上面会把图挤到首屏之外。
                    if (detail.imageUrls.isNotEmpty) ...[
                      const SizedBox(height: AppSpacing.md),
                      // V1.1.6 Story 5.2：装饰标签叠在首图角落（有图时的位置，FR-75）。
                      // ⚠️ 刻意**不**包 Padding —— 通栏出血就是靠这一层缺席实现的。
                      _ImageCarousel(
                        urls: detail.imageUrls,
                        sizes: detail.imageSizes,
                        viewportHeight: viewport.maxHeight,
                        decorationTags: detail.decorationTags,
                      ),
                    ],
                    if (detail.body != null && detail.body!.isNotEmpty) ...[
                      const SizedBox(height: AppSpacing.md),
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
                        child: Text(detail.body!, style: AppTypography.body),
                      ),
                    ],
                    // 无图 → 装饰标签落在正文下方**单独一行**小胶囊（AC5 回归保护）。
                    if (detail.imageUrls.isEmpty && detail.decorationTags.isNotEmpty) ...[
                      const SizedBox(height: AppSpacing.sm),
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
                        child: Wrap(
                          spacing: AppSpacing.xs,
                          runSpacing: AppSpacing.xs,
                          children: [
                            for (final t in detail.decorationTags) ContentTagChip.inline(tag: t, position: 'detail'),
                          ],
                        ),
                      ),
                    ],
                    // 图片之下的所有内容仍按原来的横向留白排版，与改版前一致。
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          // V1.3.0 Story 2.3：互动栏已迁到固定底栏（与评论输入框合并）。
                          // 正文下方**不再单独存在互动栏** —— 点赞 / 分享始终悬浮可点，
                          // 用户不必为了点个赞把页面滚回图片下方。
                          const SizedBox(height: AppSpacing.md),
                          const Divider(height: AppSpacing.xl, color: AppColors.divider),
                          // KOMENTAR (n) 计数标题（detail.html）。带 ?focus=comments 进来时滚到这里。
                          _ScrollIntoViewOnMount(
                            enabled: focusComments,
                            child: Text(
                                '${l10n.detailCommentsTitle.toUpperCase()} (${detail.commentCount})',
                                key: const ValueKey('detailCommentsTitle'),
                                style: AppTypography.caption.copyWith(
                                    fontWeight: FontWeight.w700,
                                    letterSpacing: 0.5,
                                    color: AppColors.ink2)),
                          ),
                          const SizedBox(height: AppSpacing.sm),
                          CommentSection(
                            postId: postId,
                            currentUserId: currentUserId,
                            postAuthorId: detail.authorId,
                            isContentAuthor: detail.isAuthor,
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                ),
                ),
              ),
            ),
            // 固定底栏 = 评论输入框 + 互动栏（Story 2.3 · AC1）。
            // detail 传进去是为了底栏右侧的点赞 / 分享两个动作。
            CommentComposer(postId: postId, detail: detail),
          ],
        ),
      ),
    );
  }

  /// 发布时间（AC6）：7 天以内走相对时间，**超过 7 天改显示绝对日期**。
  ///
  /// 「173 天前」这种数字读者根本换算不过来，而详情页常有很久以前的内容。
  /// 绝对日期复用现成的 [formatDayMonthYear]（输出如「15 Jun 2025」，已按 locale 本地化），
  /// **不新写一套格式化** —— 那会让同一个日期在不同页面长得不一样。
  static String _publishTime(BuildContext context, AppLocalizations l10n, DateTime t) {
    final d = DateTime.now().difference(t);
    if (d.inMinutes < 1) return l10n.timeJustNow;
    if (d.inHours < 1) return l10n.timeMinutesAgo(d.inMinutes);
    if (d.inDays < 1) return l10n.timeHoursAgo(d.inHours);
    if (d.inDays > 7) return formatDayMonthYear(context, t);
    return l10n.timeDaysAgo(d.inDays);
  }


  Widget _authorRow(BuildContext context, WidgetRef ref, AppLocalizations l10n) {
    final name = detail.authorDeleted ? l10n.feedDeletedUser : (detail.authorNickname ?? l10n.feedDeletedUser);
    // 映射单一来源：分享卡也用它（见 ContentTypeBadge 的注释）。
    final badge = ContentTypeBadge.of(detail.type, l10n);
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
              // V1.1.6 Story 5.1：作者区挂运营标签（四处展示位之一）。
              UserTagRow(
                position: 'detail',
                name: name,
                nameStyle: AppTypography.body.copyWith(fontWeight: FontWeight.w700),
                tags: detail.authorDeleted ? const [] : detail.authorTags,
              ),
              Text(_publishTime(context, l10n, detail.createdAt),
                  style: AppTypography.caption.copyWith(color: AppColors.textTertiary)),
            ],
          ),
        ),
        const SizedBox(width: AppSpacing.sm),
        // 分类彩徽章。
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
          decoration: BoxDecoration(color: badge.bg, borderRadius: BorderRadius.circular(7)),
          child: Text(badge.label,
              style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: badge.fg)),
        ),
      ],
    );
    // 作者点击触发迷你卡（Story 3.8）：注销作者不可点（NFR-8）。
    if (detail.authorDeleted) return row;
    return GestureDetector(
      key: const ValueKey('detailAuthorRow'),
      onTap: () => showMiniProfile(
        context,
        ref,
        detail.authorId,
        // 详情页拉黑 / 举报成功 → 该帖对本人已不可见（服务端返回 404），停在这一页只会看到一个空壳，
        // 所以**先退回上一级列表**，再把他在列表里的卡片一并移除。举报侧一律静默。
        onBlocked: onAuthorHidden(ref, detail.authorId, popContext: context),
        onReported: onAuthorHidden(ref, detail.authorId, popContext: context),
      ),
      child: row,
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
  const _ImageCarousel({
    required this.urls,
    required this.sizes,
    required this.viewportHeight,
    this.decorationTags = const [],
  });

  final List<String> urls;

  /// 与 [urls] 同序等长的原始宽高；测不出来 / 存量内容为 null（走占位兜底）。
  final List<ImageSize?> sizes;

  /// 滚动视口实际高度，喂给高度护栏（由详情页的 `LayoutBuilder` 量得）。
  final double viewportHeight;

  /// V1.1.6 Story 5.2：装饰标签叠在**首图角落**（有图时的位置）。
  final List<ContentTag> decorationTags;

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
    // bug 20260727-372：灯箱可左右翻页（此前单图 InteractiveViewer 进灯箱后无法滑动看其余图）。
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => _Lightbox(urls: widget.urls, initialIndex: index),
    ));
  }

  @override
  Widget build(BuildContext context) {
    // 🔴 容器高度按**首图**锁定（AC4）：PageView 的每一页共用一个 AspectRatio，
    // 左右滑动时高度不变。按当前页算会让容器随翻页忽高忽低，正文跟着上下跳。
    final width = MediaQuery.sizeOf(context).width;
    final aspect = resolveDetailImageAspect(
      size: widget.sizes.isNotEmpty ? widget.sizes.first : null,
      width: width,
      viewportHeight: widget.viewportHeight,
    );
    return Stack(
      children: [
        // 通栏出血：**不再 ClipRRect 圆角**，图片两侧贴屏幕边（AC2）。
        // 圆角是「卡片」的语言，详情页的图不是卡片。
        AspectRatio(
          // ① 实际比例 → ② clamp 0.75~1.34 闭区间 → ③ 高度护栏，
          // 三步全在 resolveFeedImageAspect 里，与 Feed **同一个出口函数**（AC2）。
          // 详情页只负责把自己的护栏口径喂进去（见 detail_image_layout.dart）。
          aspectRatio: aspect,
          child: PageView.builder(
              controller: _controller,
              itemCount: widget.urls.length,
              onPageChanged: (i) => setState(() => _current = i),
              itemBuilder: (context, i) => GestureDetector(
                onTap: () => _openLightbox(i),
                child: AppImage.widget(
                  widget.urls[i],
                  // 容器比例已按原图算好，cover 在比例相符时不裁切；
                  // 仅当某张与首图比例不同（多图混排）才裁，这是容器锁首图的必然代价。
                  fit: BoxFit.cover,
                  thumbWidth: 1080, // 按手机全宽取缩略图（全屏放大走原图）
                  errorBuilder: (context, error, stack) =>
                      Container(color: AppColors.border),
                ),
              ),
            ),
        ),
        // 装饰标签：左下角，与右上角的页码角标分处两角、互不遮挡。
        if (widget.decorationTags.isNotEmpty)
          Positioned(
            left: AppSpacing.sm,
            bottom: AppSpacing.sm,
            right: AppSpacing.xl,
            child: Align(
              alignment: Alignment.centerLeft,
              child: ContentTagChip.overlay(tag: widget.decorationTags.first, position: 'detail'),
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

/// 全屏灯箱（bug 20260727-372）：PageView 承载多图左右翻页，每页可捏合缩放；
/// 顶栏显示页码，初始页为点击的那张。
class _Lightbox extends StatefulWidget {
  const _Lightbox({required this.urls, required this.initialIndex});

  final List<String> urls;
  final int initialIndex;

  @override
  State<_Lightbox> createState() => _LightboxState();
}

class _LightboxState extends State<_Lightbox> {
  late final PageController _controller = PageController(initialPage: widget.initialIndex);
  late int _current = widget.initialIndex;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        title: widget.urls.length > 1
            ? Text('${_current + 1}/${widget.urls.length}',
                style: AppTypography.body.copyWith(color: Colors.white))
            : null,
        centerTitle: true,
      ),
      body: PageView.builder(
        controller: _controller,
        itemCount: widget.urls.length,
        onPageChanged: (i) => setState(() => _current = i),
        // 点击图片（或黑边）关闭大图（bug 20260701-192，对齐主流看图 App 交互；与翻页/缩放手势不冲突）。
        itemBuilder: (context, i) => GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: () => Navigator.of(context).pop(),
          child: Center(
            child: InteractiveViewer(child: AppImage.widget(widget.urls[i], fit: BoxFit.contain)),
          ),
        ),
      ),
    );
  }
}

/// 挂载后把自己滚进视野（V1.1.6 Story 3.2 · AC4）。
///
/// 为什么做成一个小组件而不是给页面加滚动控制器：详情页是**单一滚动容器**，
/// `Scrollable.ensureVisible` 能自己找到外层的可滚动祖先 ——
/// 不需要为此把整个页面改成有状态、也不需要额外管理控制器的生命周期。
///
/// ⚠️ 必须等下一帧：挂载当时布局还没完成，立刻滚会滚不到正确位置。
class _ScrollIntoViewOnMount extends StatefulWidget {
  const _ScrollIntoViewOnMount({required this.enabled, required this.child});

  final bool enabled;
  final Widget child;

  @override
  State<_ScrollIntoViewOnMount> createState() => _ScrollIntoViewOnMountState();
}

class _ScrollIntoViewOnMountState extends State<_ScrollIntoViewOnMount> {
  @override
  void initState() {
    super.initState();
    if (!widget.enabled) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      Scrollable.ensureVisible(
        context,
        duration: const Duration(milliseconds: 250),
        curve: Curves.easeOut,
        // 让标题落在视野靠上的位置，评论列表跟着露出来。
        alignment: 0.05,
      );
    });
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

/// 互动栏第三个图标：生成分享卡（Story 9.3 · FR-73）。
///
/// 点击 → 取该条内容的对外分享链接（后端幂等，重复分享复用同一 token）
/// → 进预览页（9-2 的两套模板）→ 出图 → 系统分享菜单。
///
/// 🛡 **顶栏的「···」不受影响** —— 那是举报入口（合规入口，不能变难找）。
/// UI 稿 SH1 画的是顶栏右上角放分享，但那会把「···」挤掉；2026-08-14 产品决定分享让位。
/// V1.3.0 Story 2.3 把分享从正文下方的互动栏挪进**固定底栏**，顶栏仍然保持现状。
class DetailShareCardButton extends ConsumerStatefulWidget {
  const DetailShareCardButton({super.key, required this.detail});

  final ContentDetail detail;

  @override
  ConsumerState<DetailShareCardButton> createState() => _ShareCardButtonState();
}

class _ShareCardButtonState extends ConsumerState<DetailShareCardButton> {
  bool _busy = false;

  /// 打开分享卡预览。
  ///
  /// 🔴 实际逻辑在 [ShareCardEntry.openForDetail] —— **详情页与信息流共用同一处**
  /// （bug 20260826 给信息流也加了分享入口时抽出）。抽出来是因为 E-11 的事件名与三个属性
  /// 来自埋点清单 §3，两处各写一份迟早有一处漏属性，而看板维度发版后改不动。
  /// 本类只留「点击态」这一件事。
  Future<void> _open() async {
    setState(() => _busy = true);
    try {
      await ShareCardEntry.openForDetail(context, ref, widget.detail);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      key: const ValueKey('detailShareCardIcon'),
      behavior: HitTestBehavior.opaque,
      onTap: _busy ? null : _open,
      child: Icon(
        Icons.ios_share_rounded,
        size: DetailBarMetrics.iconSize,
        color: _busy ? AppColors.muted : AppColors.textSecondary,
      ),
    );
  }
}
