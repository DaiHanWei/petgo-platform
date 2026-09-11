import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../../shared/widgets/letter_avatar.dart';
import '../../../shared/widgets/mini_profile_sheet.dart';
import '../../social/domain/account_action_entry.dart';
import '../data/detail_repository.dart';
import '../domain/comment.dart';
import 'author_moderation_callbacks.dart';
import 'detail_providers.dart';
import 'report_sheet.dart';
import '../../../shared/widgets/user_tag_row.dart';

/// 评论区（Story 3.3 只读 + Story 3.5 回复/删除入口）。一级时间正序首 10 + 「查看更多评论」；
/// 二级默认内嵌 3 条 + 「查看全部 X 条回复」展开。非自身滚动（嵌入详情页滚动）。
///
/// [currentUserId]/[isContentAuthor] 决定删除入口可见性（后端权威，前端仅体验）。
class CommentSection extends ConsumerStatefulWidget {
  const CommentSection({
    super.key,
    required this.postId,
    this.currentUserId,
    this.postAuthorId,
    this.isContentAuthor = false,
  });

  final int postId;
  final int? currentUserId;

  /// 帖子作者 id：评论区拉黑/举报的对象**恰好是帖主**时，除了刷 Feed 还要退出本详情页
  /// （停在服务端已 404 的详情页上是明显穿帮）。
  final int? postAuthorId;
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

  /// AC6 的置顶集合入口。**在 initState 就取好存下来** ——
  /// `dispose()` 里不能碰 `ref`（那时 BuildContext 已失效，Riverpod 会直接抛
  /// 「Using "ref" when a widget is about to or has been unmounted is unsafe」）。
  late final SessionPinnedCommentsNotifier _pinnedNotifier;

  @override
  void initState() {
    super.initState();
    _pinnedNotifier = ref.read(sessionPinnedCommentsProvider.notifier);
    _loadInitial();
  }

  @override
  void dispose() {
    // AC6：置顶只在本次会话有效 —— 离开详情页立刻失效，免得它变成「永久置顶自己的评论」。
    // 排到下一帧：dispose 期间直接改 provider 会在 widget 树拆解中通知监听者。
    Future.microtask(_pinnedNotifier.clear);
    super.dispose();
  }

  /// 渲染用的一级评论顺序：**本会话新发的排最前**，其余保持服务端的热度序。
  ///
  /// 🔴 这里只动**展示顺序**，`_nextCursor` 仍然来自服务端返回的那一页的最后一条
  /// —— 置顶项不参与游标计算，否则会制造重复/漏条（AC6）。
  List<Comment> get _orderedTopLevel {
    final pinned = ref.watch(sessionPinnedCommentsProvider);
    if (pinned.isEmpty) return _topLevel;

    final byId = {for (final c in _topLevel) c.id: c};
    // 服务端那份优先（赞数、审核态都更新）；服务端这一页没返回它时用本地存下的那份
    // —— 热度序下 0 赞的新评论很可能压根不在第一页，这一步才是「始终可见」的保证。
    final mine = [
      for (final id in pinned.keys) byId[id] ?? pinned[id]!,
    ];
    final rest = [
      for (final c in _topLevel)
        if (!pinned.containsKey(c.id)) c,
    ];
    return [...mine, ...rest];
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

  /// 评论区迷你卡拉黑/举报成功后的收尾（修复清单 #7）：
  /// ① 与首页/详情入口同一套 [onAuthorHidden]——乐观清掉 Feed 里该作者的全部卡片；
  ///    对象恰好是帖主时传 popContext 退出本详情页（那一页服务端已经 404 了）。
  /// ② 帖主之外的场景 bump [commentsRefreshProvider]——它同时驱动本区 _reload **与**详情页头部
  ///    「KOMENTAR (N)」计数失效。只调 _reload 的话列表变短、计数不动，正是 AD-13 要消灭的
  ///    「标 5 条只数得出 3 条」拉黑泄底破绽。
  VoidCallback _onCommentAuthorHidden(int authorId) {
    final bool isPostAuthor = widget.postAuthorId != null && authorId == widget.postAuthorId;
    return () {
      onAuthorHidden(ref, authorId, popContext: isPostAuthor ? context : null)();
      if (!isPostAuthor) {
        ref.read(commentsRefreshProvider.notifier).bump();
      }
    };
  }

  /// 评论点赞 / 取消（V1.3.0 Story 2.4 · AC3）。
  ///
  /// **乐观更新**：先本地翻转再发请求 —— 服务端端点不返回赞数（那是实时聚合值，
  /// 回来时可能已经变了），所以本地 ±1 是唯一能让按钮立刻有反馈的办法。
  ///
  /// 失败**回滚**并静默：点赞不是关键路径，为它弹一个错误提示比点不上还烦人。
  /// 服务端本身幂等（重复点赞不产生第二行、没赞过取消也成功），所以不必先查状态。
  Future<void> _toggleLike(Comment c) async {
    final wasLiked = c.liked;
    setState(() => _replaceComment(c.id, (x) => x.toggleLikedLocally()));
    try {
      if (wasLiked) {
        await _repo.unlikeComment(c.id);
      } else {
        await _repo.likeComment(c.id);
      }
    } catch (_) {
      if (!mounted) return;
      setState(() => _replaceComment(c.id, (x) => x.toggleLikedLocally()));
    }
  }

  /// 就地替换一条评论（可能在一级列表、内嵌回复、或已展开的回复里）。
  ///
  /// 三处都要找：同一条评论在不同位置是**同一个对象的不同副本**，只改一处会让
  /// 「收起再展开」时点赞态跳回去。
  void _replaceComment(int id, Comment Function(Comment) update) {
    for (var i = 0; i < _topLevel.length; i++) {
      final top = _topLevel[i];
      if (top.id == id) {
        _topLevel[i] = update(top);
        continue;
      }
      final inline = top.replies;
      if (inline != null) {
        for (var j = 0; j < inline.length; j++) {
          if (inline[j].id == id) {
            final copy = List<Comment>.of(inline)..[j] = update(inline[j]);
            _topLevel[i] = top.copyWith(replies: copy);
            break;
          }
        }
      }
    }
    for (final exp in _expanded.values) {
      for (var i = 0; i < exp.items.length; i++) {
        if (exp.items[i].id == id) {
          exp.items[i] = update(exp.items[i]);
          break;
        }
      }
    }
  }

  /// 长按评论的操作菜单（AC3）。样式参照详情页 `_showMoreSheet` 的列表行
  /// （把手 + 左对齐行 + 底分隔线 + Batal），不另造一套视觉。
  ///
  /// - 自己的评论：复制文字 / 回复 / **删除**（红字）
  /// - 他人的评论：复制文字 / 回复 / **举报**（红字）
  ///
  /// 🔴 **权限一点没放宽**（AC4）：删除项的可见性沿用既有 `_canDelete`，
  /// 点下去仍走既有 `_confirmDelete` 的二次确认；举报走既有 `openReport` 流程。
  /// 这个菜单只是**多一个入口**，不是多一条权限路径。
  void _showCommentActions(
      BuildContext context, AppLocalizations l10n, Comment c, String name) {
    final canDelete = _canDelete(c);
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
              _actionRow(
                sheetCtx,
                key: const ValueKey('commentActionCopy'),
                emoji: '📋',
                label: l10n.commentActionCopy,
                onTap: () async {
                  await Clipboard.setData(ClipboardData(text: c.body));
                  if (context.mounted) showAppToast(context, l10n.commonCopied);
                },
              ),
              _actionRow(
                sheetCtx,
                key: const ValueKey('commentActionReply'),
                emoji: '💬',
                label: l10n.detailReply,
                onTap: () => ref
                    .read(replyTargetProvider.notifier)
                    .set(ReplyTarget(parentId: c.id, toName: name)),
              ),
              if (canDelete)
                _actionRow(
                  sheetCtx,
                  key: const ValueKey('commentActionDelete'),
                  emoji: '🗑',
                  label: l10n.detailMenuDelete,
                  danger: true,
                  // 既有二次确认，一步不省。
                  onTap: () => _confirmDelete(c.id),
                )
              else
                _actionRow(
                  sheetCtx,
                  key: const ValueKey('commentActionReport'),
                  emoji: '🚩',
                  label: l10n.commentActionReport,
                  danger: true,
                  // ⚠️ 举报走既有 openReport（AC4 明确要求沿用）——
                  // 它举报的是**本帖**，后端目前没有「举报单条评论」的端点。
                  // 评论区骚扰的既有处置路径是「点作者 → 迷你卡 → 举报/拉黑该用户」，
                  // 那条路仍在（点昵称或头像）。此处的语义落差已写进 story 的 Completion Notes。
                  onTap: () => openReport(context, ref, widget.postId),
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

  /// 菜单里的一行。先关 sheet 再执行 —— 否则确认弹层会被 sheet 压在下面。
  Widget _actionRow(
    BuildContext sheetCtx, {
    required Key key,
    required String emoji,
    required String label,
    required VoidCallback onTap,
    bool danger = false,
  }) =>
      InkWell(
        key: key,
        onTap: () {
          Navigator.of(sheetCtx).pop();
          onTap();
        },
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 14),
          decoration: const BoxDecoration(
            border: Border(bottom: BorderSide(color: AppColors.line2)),
          ),
          child: Row(
            children: [
              Text(emoji, style: const TextStyle(fontSize: 16)),
              const SizedBox(width: 10),
              Text(
                label,
                style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: danger ? AppColors.popRed : AppColors.ink),
              ),
            ],
          ),
        ),
      );

  Future<void> _confirmDelete(int commentId) async {
    final l10n = AppLocalizations.of(context);
    final ok = await showConfirmSheet(
      context,
      title: l10n.detailMenuDelete,
      confirmLabel: l10n.detailMenuDelete,
      cancelLabel: l10n.commonCancel,
      icon: Icons.delete_outline_rounded,
      danger: true,
      confirmKey: const ValueKey('confirmDeleteComment'),
    );
    if (!ok) return;
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
      // AC5 空态：一枚 emoji + 引导语（改前只有一行纯文字）。
      // 底部固定输入框不受影响 —— 它在详情页的 Column 里，与本区域同级。
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.xl),
        child: Center(
          key: const ValueKey('commentEmptyState'),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('💬', style: TextStyle(fontSize: 34)),
              const SizedBox(height: AppSpacing.sm),
              Text(
                l10n.detailNoComments,
                textAlign: TextAlign.center,
                style: AppTypography.caption.copyWith(color: AppColors.textTertiary),
              ),
            ],
          ),
        ),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final c in _orderedTopLevel) _buildTopLevel(context, l10n, c),
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
      // V1.1.4 Story 1.6：点评论作者 → 迷你卡（举报 / 拉黑的入口）。
      //
      // ⚠️ 这是本版本最大的闭环缺口：影子评论 / R1 / R2 / 通知抑制**全是为评论区骚扰设计的**，
      // 而在此之前 `showMiniProfile` 全 App 只有 Feed 卡片作者与详情页作者两个触发点——
      // 一个只在评论区骚扰、从不发帖的账号，用户既举报不了也拉黑不了。
      //
      // ⚠️ 已注销 → 传 null，整体去掉点击手势（NFR-8，与首页/详情两处一致），且**不给任何 Toast**。
      // `showMiniProfile` 内部虽有第二道防线（isDeactivated 直接 return），但那要先走一次网络往返，
      // 用户看到的是「点了没反应」——与网络失败无法区分。
      onAuthorTap: c.authorDeleted
          ? null
          : () => showMiniProfile(context, ref, c.authorId,
              // 修复清单 #7：与首页/详情入口同一套收尾（onAuthorHidden = 乐观清 Feed 该作者
              // 全部卡片；对象是帖主时顺带退出本详情页），再刷本帖评论。只接 _reload 的话，
              // 用户回到首页会看见「我明明处理了，他的东西还在」。
              onBlocked: _onCommentAuthorHidden(c.authorId),
              onReported: _onCommentAuthorHidden(c.authorId),
              // 评论区这个入口的量单独可查（本版本的主场景就是评论区骚扰）。
              entry: AccountActionEntry.comment),
      // story 3：仅作者会收到 TAKEN_DOWN/REJECTED 行 → 渲染「仅你可见」灰标签（VISIBLE/UNDER_REVIEW 无标签，D-CM2）。
      takenDownLabel: c.isTakenDownForAuthor ? l10n.commentTakenDownSelfOnly : null,
      canDelete: _canDelete(c),
      onReply: () =>
          ref.read(replyTargetProvider.notifier).set(ReplyTarget(parentId: c.id, toName: name)),
      onDelete: () => _confirmDelete(c.id),
      // V1.3.0 Story 2.4：评论点赞。一级、二级共用同一端点（层级与点赞无关）。
      onToggleLike: () => _toggleLike(c),
      // V1.3.0 Story 2.5 · AC3：长按操作菜单。
      onLongPress: () => _showCommentActions(context, l10n, c, name),
      // AC2：客户端比两个已有 id —— 服务端不下发任何标记位。
      isPostAuthor: widget.postAuthorId != null && c.authorId == widget.postAuthorId,
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
    required this.onAuthorTap,
    required this.onToggleLike,
    required this.onLongPress,
    required this.isPostAuthor,
    this.takenDownLabel,
  });

  /// 长按弹操作菜单（AC3）。
  final VoidCallback onLongPress;

  /// 该评论的作者**就是本帖作者** → 昵称旁显示「作者 / Penulis」标签（AC2）。
  ///
  /// 🔴 判定在**客户端**完成：评论作者 id 与帖子作者 id **两个值详情页响应里都已经有了**，
  /// 直接比即可。服务端不下发额外字段、不加标记位 —— 为一个纯展示标签扩接口不值当。
  final bool isPostAuthor;

  /// 点赞 / 取消（V1.3.0 Story 2.4）。**乐观更新**：调用方先本地翻转再发请求。
  final VoidCallback onToggleLike;

  final Comment comment;
  final String name;
  final String replyLabel;
  final bool canDelete;
  final VoidCallback onReply;
  final VoidCallback onDelete;

  /// 点头像/作者名 → 迷你卡（Story 1.6）。**为 null = 已注销**，此时头像与名字都不可点，
  /// 整行只剩「点了回复」的既有行为。
  final VoidCallback? onAuthorTap;

  /// 非空 = 该评论被下架/移除、仅作者可见 → 渲染灰态提示标签（story 3）。
  final String? takenDownLabel;

  @override
  Widget build(BuildContext context) {
    // 点整条评论 = 回复它（FR-24 两级评论，与「回复」按钮同一入口）：一级→生成二级；
    // 二级→后端 /comments/{parentId}/replies 归并到其一级父（绝不产生三级）。用户反馈：点别人评论应弹出评论框。
    return GestureDetector(
      key: ValueKey('commentItem_${comment.id}'),
      behavior: HitTestBehavior.opaque,
      onTap: onReply,
      // AC3：长按弹操作菜单。与单击的「回复」并存 —— 长按不会误触发回复
      // （Flutter 的手势竞技场里 long-press 胜出后 tap 不再触发）。
      onLongPress: onLongPress,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
        // 头像 + 右侧文字块（UI 稿 A6）。头像是 2026-08-16 才补的：在此之前评论行只有
        // 「作者名 + 正文 + 操作」，`Comment.authorAvatarUrl` 有数据却从未渲染，
        // 而 Story 1.6 把这里变成了举报/拉黑的入口——只留一行 12.5px 的小字当热区太窄。
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 头像与作者名共用同一个手势（热区连成一片）；已注销时 onAuthorTap 为 null → 不可点。
            GestureDetector(
              key: ValueKey('commentAuthorAvatar_${comment.id}'),
              onTap: onAuthorTap,
              child: LetterAvatar(
                url: comment.authorDeleted ? null : comment.authorAvatarUrl,
                name: name,
                deleted: comment.authorDeleted,
                size: 30,
              ),
            ),
            const SizedBox(width: AppSpacing.sm),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // 作者名单独可点：外层整行的 onTap 是「回复」，内层这个 GestureDetector 命中优先，
                  // 所以点名字/头像弹卡、点正文回复，两者不会打架。
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: [
                      Flexible(
                        child: GestureDetector(
                          key: ValueKey('commentAuthor_${comment.id}'),
                          onTap: onAuthorTap,
                          // V1.1.6 Story 5.1：评论区昵称旁挂运营标签（四处展示位之一）。
                          // ⚠️ 一页评论可达数十条 —— 标签是随作者投影**整批**取回来的，这里没有任何逐条查询。
                          child: UserTagRow(
                            position: 'comment',
                            name: name,
                            nameStyle:
                                AppTypography.caption.copyWith(fontWeight: FontWeight.w600),
                            tags: comment.authorDeleted ? const [] : comment.authorTags,
                          ),
                        ),
                      ),
                      // AC2「作者 / Penulis」标签：**零接口变更**，客户端比两个已有 id 得出。
                      // 注销作者不挂（与 NFR-8 一致：不给注销账号任何身份线索）。
                      if (isPostAuthor && !comment.authorDeleted) ...[
                        const SizedBox(width: AppSpacing.xs),
                        Container(
                          key: ValueKey('commentAuthorBadge_${comment.id}'),
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                          decoration: BoxDecoration(
                            color: AppColors.cream2,
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            AppLocalizations.of(context).commentAuthorBadge,
                            style: AppTypography.micro
                                .copyWith(color: AppColors.ink2, fontWeight: FontWeight.w600),
                          ),
                        ),
                      ],
                    ],
                  ),
                  const SizedBox(height: AppSpacing.xxs),
                  Text(comment.body, style: AppTypography.body),
                  const SizedBox(height: AppSpacing.xxs),
                  // 时间走**与详情页同一个** formatPublishTime（AC1）：7 天内相对、超 7 天绝对日期。
                  Text(
                    formatPublishTime(context, AppLocalizations.of(context), comment.createdAt),
                    key: ValueKey('commentTime_${comment.id}'),
                    style: AppTypography.micro.copyWith(color: AppColors.textTertiary),
                  ),
                  if (takenDownLabel != null)
                    Padding(
                      padding: const EdgeInsets.only(top: AppSpacing.xxs),
                      child: Text(
                        takenDownLabel!,
                        key: ValueKey('commentTakenDown_${comment.id}'),
                        style: AppTypography.micro.copyWith(color: AppColors.textTertiary),
                      ),
                    ),
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
                      const Spacer(),
                      // 点赞：心 + 数字。0 赞时**不显示数字**（一排「0」会把评论区弄得很吵）。
                      // 数字与心形同步变色，与帖子点赞（FR-93）同一规则 ——
                      // 只有图标变红、数字仍是灰的，看上去像"没点上"。
                      GestureDetector(
                        key: ValueKey('likeComment_${comment.id}'),
                        behavior: HitTestBehavior.opaque,
                        onTap: onToggleLike,
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              comment.liked
                                  ? Icons.favorite_rounded
                                  : Icons.favorite_border_rounded,
                              size: 14,
                              color: comment.liked
                                  ? AppColors.likeHeart
                                  : AppColors.textTertiary,
                            ),
                            if (comment.likeCount > 0) ...[
                              const SizedBox(width: AppSpacing.xxs),
                              Text(
                                '${comment.likeCount}',
                                key: ValueKey('likeCount_${comment.id}'),
                                style: AppTypography.micro.copyWith(
                                  color: comment.liked
                                      ? AppColors.likeHeart
                                      : AppColors.textTertiary,
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
