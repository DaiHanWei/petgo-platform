import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/detail_repository.dart';
import '../domain/comment.dart';
import 'detail_providers.dart';

/// 评论区（Story 3.3 只读 + Story 3.5 回复/删除入口）。一级时间正序首 10 + 「查看更多评论」；
/// 二级默认内嵌 3 条 + 「查看全部 X 条回复」展开。非自身滚动（嵌入详情页滚动）。
///
/// [currentUserId]/[isContentAuthor] 决定删除入口可见性（后端权威，前端仅体验）。
class CommentSection extends ConsumerStatefulWidget {
  const CommentSection({
    super.key,
    required this.postId,
    this.currentUserId,
    this.isContentAuthor = false,
  });

  final int postId;
  final int? currentUserId;
  final bool isContentAuthor;

  @override
  ConsumerState<CommentSection> createState() => _CommentSectionState();
}

class _ExpandedReplies {
  _ExpandedReplies(this.items, this.nextCursor, this.hasMore);
  List<Comment> items;
  String? nextCursor;
  bool hasMore;
}

class _CommentSectionState extends ConsumerState<CommentSection> {
  final List<Comment> _topLevel = [];
  String? _nextCursor;
  bool _hasMore = false;
  bool _loading = true;
  bool _loadingMore = false;

  /// 已展开全部回复的一级评论（parentId → 已加载二级）。
  final Map<int, _ExpandedReplies> _expanded = {};

  DetailRepository get _repo => ref.read(detailRepositoryProvider);

  @override
  void initState() {
    super.initState();
    _loadInitial();
  }

  Future<void> _loadInitial() async {
    try {
      final page = await _repo.getComments(widget.postId);
      if (!mounted) return;
      setState(() {
        _topLevel
          ..clear()
          ..addAll(page.items);
        _nextCursor = page.nextCursor;
        _hasMore = page.hasMore;
        _loading = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _loadMore() async {
    if (_loadingMore || !_hasMore || _nextCursor == null) return;
    setState(() => _loadingMore = true);
    try {
      final page = await _repo.getComments(widget.postId, cursor: _nextCursor);
      if (!mounted) return;
      setState(() {
        _topLevel.addAll(page.items);
        _nextCursor = page.nextCursor;
        _hasMore = page.hasMore;
        _loadingMore = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  Future<void> _expandReplies(int parentId) async {
    final existing = _expanded[parentId];
    if (existing != null && !existing.hasMore) return;
    try {
      final page = await _repo.getReplies(parentId, cursor: existing?.nextCursor);
      if (!mounted) return;
      setState(() {
        if (existing == null) {
          _expanded[parentId] = _ExpandedReplies(page.items, page.nextCursor, page.hasMore);
        } else {
          existing.items.addAll(page.items);
          existing.nextCursor = page.nextCursor;
          existing.hasMore = page.hasMore;
        }
      });
    } catch (_) {
      // 静默：保持已展示内容。
    }
  }

  /// 静默重拉首屏（发表/回复/删除后，不闪骨架）。
  Future<void> _reload() async {
    try {
      final page = await _repo.getComments(widget.postId);
      if (!mounted) return;
      setState(() {
        _topLevel
          ..clear()
          ..addAll(page.items);
        _nextCursor = page.nextCursor;
        _hasMore = page.hasMore;
        _expanded.clear();
      });
    } catch (_) {
      // 保持现状。
    }
  }

  Future<void> _confirmDelete(int commentId) async {
    final l10n = AppLocalizations.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        content: Text(l10n.detailMenuDelete),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: Text(l10n.commonCancel)),
          TextButton(
            key: const ValueKey('confirmDeleteComment'),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(l10n.detailMenuDelete),
          ),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await _repo.deleteComment(commentId);
      await _reload();
      // 详情计数随之变化：触发刷新信号（详情页可据此重拉）。
      ref.read(commentsRefreshProvider.notifier).bump();
    } catch (_) {
      // 后端权威（403 等）：保持现状。
    }
  }

  bool _canDelete(Comment c) {
    if (widget.isContentAuthor) return true; // 内容主可删任意
    return widget.currentUserId != null &&
        !c.authorDeleted &&
        c.authorId == widget.currentUserId; // 评论作者本人
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 发表/回复/删除后重拉（Story 3.5）。
    ref.listen<int>(commentsRefreshProvider, (prev, next) => _reload());
    if (_loading) {
      return const Padding(
        padding: EdgeInsets.all(AppSpacing.lg),
        child: Center(child: CircularProgressIndicator(color: AppColors.accentGrowth)),
      );
    }
    if (_topLevel.isEmpty) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.xl),
        child: Center(child: Text(l10n.detailNoComments, style: AppTypography.caption)),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final c in _topLevel) _buildTopLevel(context, l10n, c),
        if (_hasMore)
          TextButton(
            key: const ValueKey('viewMoreComments'),
            onPressed: _loadingMore ? null : _loadMore,
            child: Text(l10n.detailViewMoreComments),
          ),
      ],
    );
  }

  Widget _buildTopLevel(BuildContext context, AppLocalizations l10n, Comment c) {
    final expanded = _expanded[c.id];
    final List<Comment> shownReplies = expanded?.items ?? (c.replies ?? const []);
    final int replyCount = c.replyCount ?? 0;
    // 未展开且总数 > 内嵌数 → 显示「查看全部 X 条回复」。
    final bool showViewAll = expanded == null && replyCount > shownReplies.length;
    // 已展开但仍有下一页 → 继续加载。
    final bool showLoadMoreReplies = expanded != null && expanded.hasMore;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _tile(l10n, c),
          if (shownReplies.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(left: AppSpacing.xl, top: AppSpacing.xs),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [for (final r in shownReplies) _tile(l10n, r)],
              ),
            ),
          if (showViewAll || showLoadMoreReplies)
            Padding(
              padding: const EdgeInsets.only(left: AppSpacing.xl),
              child: TextButton(
                key: ValueKey('viewReplies_${c.id}'),
                onPressed: () => _expandReplies(c.id),
                child: Text(l10n.detailViewAllReplies(replyCount)),
              ),
            ),
        ],
      ),
    );
  }

  Widget _tile(AppLocalizations l10n, Comment c) {
    final name = c.authorDeleted ? l10n.feedDeletedUser : (c.authorNickname ?? l10n.feedDeletedUser);
    return _CommentTile(
      comment: c,
      name: name,
      replyLabel: l10n.detailReply,
      canDelete: _canDelete(c),
      onReply: () =>
          ref.read(replyTargetProvider.notifier).set(ReplyTarget(parentId: c.id, toName: name)),
      onDelete: () => _confirmDelete(c.id),
    );
  }
}

class _CommentTile extends StatelessWidget {
  const _CommentTile({
    required this.comment,
    required this.name,
    required this.replyLabel,
    required this.canDelete,
    required this.onReply,
    required this.onDelete,
  });

  final Comment comment;
  final String name;
  final String replyLabel;
  final bool canDelete;
  final VoidCallback onReply;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(name, style: AppTypography.caption.copyWith(fontWeight: FontWeight.w600)),
          const SizedBox(height: AppSpacing.xxs),
          Text(comment.body, style: AppTypography.body),
          Row(
            children: [
              GestureDetector(
                key: ValueKey('replyComment_${comment.id}'),
                onTap: onReply,
                child: Text(replyLabel, style: AppTypography.micro),
              ),
              if (canDelete) ...[
                const SizedBox(width: AppSpacing.md),
                GestureDetector(
                  key: ValueKey('deleteComment_${comment.id}'),
                  onTap: onDelete,
                  child: Icon(Icons.delete_outline_rounded,
                      size: 14, color: AppColors.textTertiary),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }
}
