import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import '../../../shared/widgets/app_toast.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/problem_detail.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/rounded.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../features/auth/domain/auth_guard.dart';
import '../../../features/auth/domain/auth_state.dart';
import '../../../l10n/app_localizations.dart';
import '../../mention/data/mention_candidate_repository.dart';
import '../../mention/domain/mention_draft.dart';
import '../../mention/presentation/mention_picker.dart';
import '../data/detail_repository.dart';
import 'detail_providers.dart';

/// 底部固定评论框（Story 3.5，FR-24）。未登录点击触发 FR-0C；登录态可编辑发表一级评论
/// 或回复（≤200 字实时计数，服务端权威）。发表成功后 bump 评论区刷新信号。
class CommentComposer extends ConsumerStatefulWidget {
  const CommentComposer({super.key, required this.postId});

  final int postId;

  @override
  ConsumerState<CommentComposer> createState() => _CommentComposerState();
}

class _CommentComposerState extends ConsumerState<CommentComposer> {
  final TextEditingController _controller = TextEditingController();
  final FocusNode _focusNode = FocusNode();
  static const int _maxLen = 200;
  bool _sending = false;
  // 触达字数上限只提示一次（回落到 <上限再复位），避免满字后每敲一键连弹（bug 20260702-218）。
  bool _limitToasted = false;

  /// V1.3.0 batch-b1 Story 3.2：这条评论里 @ 了谁（存 userId，不存昵称 —— AC4）。
  final MentionDraft _mentions = MentionDraft();

  /// 光标前正在打的那个 `@查询词`；非 null 即浮层可见（AC1）。
  MentionQuery? _mentionQuery;

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  /// 每次输入都重算「现在该不该弹 @ 浮层」（AC1：输入 @ 即弹）。
  void _syncMentionQuery() {
    final next = MentionDraft.queryAt(_controller.text, _controller.selection.baseOffset);
    if (next?.start != _mentionQuery?.start || next?.keyword != _mentionQuery?.keyword) {
      setState(() => _mentionQuery = next);
    }
  }

  /// 选中一个候选人：正文里插 `@昵称`，数据层记 userId（AC4）。
  void _onMentionSelected(MentionCandidate candidate) {
    final query = _mentionQuery;
    if (query == null) return;
    final l10n = AppLocalizations.of(context);
    // 🔴 插入是直接写 controller.value，**绕过 maxLength 的格式化器** ——
    // 不先自己拦一次，200 字的评论插一个长昵称就成了 218 字，服务端 @Size(max=200)
    // 必拒，而用户只看到通用的「发送失败，请重试」，重试永远不会成功。
    if (MentionDraft.textAfterInsert(_controller.text, query, candidate.nickname)
            .characters
            .length >
        _maxLen) {
      showAppToast(context, l10n.commentLimitReached);
      setState(() => _mentionQuery = null);
      return;
    }
    final inserted =
        _mentions.insert(_controller.text, query, candidate.userId, candidate.nickname);
    if (inserted == null) {
      // AC5：达上限不能再插入，并给出提示。
      showAppToast(context, l10n.mentionLimitReached);
      setState(() => _mentionQuery = null);
      return;
    }
    _controller.value = TextEditingValue(
      text: inserted.text,
      selection: TextSelection.collapsed(offset: inserted.cursor),
    );
    setState(() => _mentionQuery = null);
  }

  Future<void> _send(int? parentId) async {
    final text = _controller.text.trim();
    if (text.isEmpty || _sending) return;
    // 🔴 只发文本里还留着 `@昵称` 的那些 id（插完又删掉的不算，否则对方会收到一条
    //    点进去找不到自己的通知）。服务端还会再洗一遍（MentionSanitizer）。
    final mentionedUserIds = _mentions.userIdsIn(text);
    setState(() => _sending = true);
    try {
      final repo = ref.read(detailRepositoryProvider);
      if (parentId != null) {
        await repo.postReply(parentId, text, mentionedUserIds: mentionedUserIds);
      } else {
        await repo.postComment(widget.postId, text, mentionedUserIds: mentionedUserIds);
      }
      if (!mounted) return;
      // 仅成功后清空输入 + 收起键盘 + 退出回复态 + 刷新评论区（AC3）。
      _controller.clear();
      _mentions.clear();
      _mentionQuery = null;
      _limitToasted = false;
      FocusScope.of(context).unfocus();
      ref.read(replyTargetProvider.notifier).clear();
      ref.read(commentsRefreshProvider.notifier).bump();
    } catch (e) {
      if (!mounted) return;
      final l10n = AppLocalizations.of(context);
      // 回复态遇 404 → 父评论已被删除（评论从列表点出，加载后被删才会 404）：给专属提示，别再吞成通用「重试」。
      final problem = e is DioException ? ProblemDetail.fromDioException(e) : null;
      final status = problem?.status;
      if (parentId != null && status == 404) {
        _controller.clear();
        _mentions.clear(); // 输入被清空，@ 绑定跟着作废（留着会跟下一条评论串味）
        _mentionQuery = null;
        ref.read(replyTargetProvider.notifier).clear(); // 退出回复态（父已不存在，重试无意义）
        ref.read(commentsRefreshProvider.notifier).bump(); // 刷新评论区，让已删除的父评论从列表消失
        showAppToast(context, l10n.commentReplyTargetDeleted);
      } else if (problem?.typeSlug == 'comment-blocked') {
        // story 3（G1/F13）：内容审核拦截（L1 或风险 ≥0.8，422 COMMENT_BLOCKED）→ **保留输入与回复态**，
        // 提示修改后重试（区别于网络失败的 commentSendFailed；不区分 L1/≥0.8）。
        showAppToast(context, l10n.commentModerationBlocked);
      } else {
        // AC3（F13）：发送失败（网络/服务器/其他 422）→ 提示重试，**保留输入与回复态**，可直接重试。
        showAppToast(context, l10n.commentSendFailed);
      }
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final isGuest = ref.watch(authControllerProvider).status == AuthStatus.guest;
    final replyTarget = ref.watch(replyTargetProvider);

    // 点「回复」设置回复目标 → 自动弹键盘（游客无回复入口，无需判 guest）。
    ref.listen<ReplyTarget?>(replyTargetProvider, (prev, next) {
      if (next != null) _focusNode.requestFocus();
    });
    // 点互动栏评论图标 → 弹键盘；游客转登录引导（FR-0C）。
    ref.listen<int>(commentFocusProvider, (prev, next) {
      if (isGuest) {
        requireLogin(ref, context, onAllowed: () {});
      } else {
        _focusNode.requestFocus();
      }
    });

    // 游客：只读提示框，点击触发 FR-0C。
    if (isGuest) {
      return _bottomBar(
        child: GestureDetector(
          key: const ValueKey('detailCommentBox'),
          onTap: () => requireLogin(ref, context, onAllowed: () {}),
          child: _hintPill(l10n.detailCommentHint),
        ),
      );
    }

    // 登录态：可编辑发表 / 回复。
    return _bottomBar(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (replyTarget != null)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.xs),
              child: Row(
                children: [
                  Expanded(
                    child: Text('@${replyTarget.toName}',
                        style: AppTypography.micro, overflow: TextOverflow.ellipsis),
                  ),
                  GestureDetector(
                    key: const ValueKey('cancelReply'),
                    onTap: () => ref.read(replyTargetProvider.notifier).clear(),
                    child: const Icon(Icons.close_rounded, size: 16, color: AppColors.textTertiary),
                  ),
                ],
              ),
            ),
          // AC1：输入「@」弹出用户选择器浮层。贴着输入条上沿 —— 这一条本来就浮在
          // 键盘之上，浮层长在它上面即可，不必另起一层 Overlay。
          if (_mentionQuery != null) ...[
            MentionPicker(
              keyword: _mentionQuery!.keyword,
              onSelected: _onMentionSelected,
            ),
            const SizedBox(height: AppSpacing.xs),
          ],
          Row(
            children: [
              Expanded(
                child: TextField(
                  key: const ValueKey('detailCommentInput'),
                  controller: _controller,
                  focusNode: _focusNode,
                  maxLength: _maxLen,
                  // 触达 200 字上限给一次 toast（maxLength 静默硬截断本身无反馈，bug 20260702-218）。
                  // 用 characters（字素）计数，与 maxLength 的截断口径一致。
                  onChanged: (v) {
                    final atLimit = v.characters.length >= _maxLen;
                    if (atLimit && !_limitToasted) {
                      _limitToasted = true;
                      showAppToast(context, l10n.commentLimitReached);
                    } else if (!atLimit && _limitToasted) {
                      _limitToasted = false;
                    }
                    // Story 3.2 AC1：每次输入重算 @ 触发态。
                    _syncMentionQuery();
                  },
                  minLines: 1,
                  maxLines: 3,
                  style: AppTypography.body,
                  decoration: InputDecoration(
                    hintText: l10n.detailCommentHint,
                    counterText: '',
                    isDense: true,
                    filled: true,
                    fillColor: AppColors.cream2,
                    contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                    // 原型 pill 输入框：无边框圆角填充。
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(999),
                      borderSide: BorderSide.none,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              // 紫色实心圆发送钮（detail.html）。
              Material(
                color: AppColors.mint,
                shape: const CircleBorder(),
                child: InkWell(
                  key: const ValueKey('detailCommentSend'),
                  customBorder: const CircleBorder(),
                  onTap: _sending ? null : () => _send(replyTarget?.parentId),
                  child: const SizedBox(
                    width: 42,
                    height: 42,
                    child: Icon(Icons.send_rounded, size: 20, color: AppColors.onAccent),
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _bottomBar({required Widget child}) {
    return SafeArea(
      top: false,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg, vertical: AppSpacing.md),
        decoration: const BoxDecoration(
          color: AppColors.surface,
          border: Border(top: BorderSide(color: AppColors.border)),
        ),
        child: child,
      ),
    );
  }

  Widget _hintPill(String hint) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.sm),
      decoration: BoxDecoration(color: AppColors.base, borderRadius: AppRounded.lgRadius),
      child: Text(hint, style: AppTypography.caption),
    );
  }
}
