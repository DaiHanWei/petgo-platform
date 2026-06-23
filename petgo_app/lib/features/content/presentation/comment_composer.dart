import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/rounded.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../features/auth/domain/auth_guard.dart';
import '../../../features/auth/domain/auth_state.dart';
import '../../../l10n/app_localizations.dart';
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
  static const int _maxLen = 200;
  bool _sending = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _send(int? parentId) async {
    final text = _controller.text.trim();
    if (text.isEmpty || _sending) return;
    setState(() => _sending = true);
    try {
      final repo = ref.read(detailRepositoryProvider);
      if (parentId != null) {
        await repo.postReply(parentId, text);
      } else {
        await repo.postComment(widget.postId, text);
      }
      if (!mounted) return;
      // 仅成功后清空输入 + 收起键盘 + 退出回复态 + 刷新评论区（AC3）。
      _controller.clear();
      FocusScope.of(context).unfocus();
      ref.read(replyTargetProvider.notifier).clear();
      ref.read(commentsRefreshProvider.notifier).bump();
    } catch (_) {
      // AC3（F13）：发送失败（网络/服务器/422）→ 提示重试，**保留输入与回复态**，可直接重试。
      if (!mounted) return;
      final l10n = AppLocalizations.of(context);
      ScaffoldMessenger.of(context)
        ..clearSnackBars()
        ..showSnackBar(SnackBar(content: Text(l10n.commentSendFailed)));
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final isGuest = ref.watch(authControllerProvider).status == AuthStatus.guest;
    final replyTarget = ref.watch(replyTargetProvider);

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
          Row(
            children: [
              Expanded(
                child: TextField(
                  key: const ValueKey('detailCommentInput'),
                  controller: _controller,
                  maxLength: _maxLen,
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
