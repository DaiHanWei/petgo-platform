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
      _controller.clear();
      ref.read(replyTargetProvider.notifier).clear();
      // 触发评论区重拉（正序末尾即时出现）。
      ref.read(commentsRefreshProvider.notifier).bump();
    } catch (_) {
      // 失败：保留输入，停止 loading（错误提示可由全局拦截器/banner 承载）。
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
                    border: OutlineInputBorder(borderRadius: AppRounded.lgRadius),
                  ),
                ),
              ),
              IconButton(
                key: const ValueKey('detailCommentSend'),
                onPressed: _sending ? null : () => _send(replyTarget?.parentId),
                icon: const Icon(Icons.send_rounded, color: AppColors.accentGrowth),
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
