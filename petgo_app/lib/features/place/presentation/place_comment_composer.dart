import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/network/problem_detail.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/rounded.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../auth/domain/auth_guard.dart';
import '../../auth/domain/auth_state.dart';
import '../data/place_repository.dart';
import '../domain/place_comment.dart';
import 'place_comments_controller.dart';

/// 场所详情页底部的评论输入条（V1.3.0 batch-b1 Story 1.7 · AC3/AC4 · UI 稿 A4 · B1-D3）。
///
/// <h2>🔴 AC4：👍/👎 在**展开态**里，绝不与发送键并列</h2>
/// UI 稿原先把两个圆钮摆在输入条右侧、紧挨发送键 —— 那样看起来像「给这个场所点赞/点踩」
/// 的两个**独立动作**。而 PRD 定的是**评论自带的态度**：<b>不写评论就不能表态</b>。
/// 2026-09-11 的 B1-D3 因此把它们收进了输入框获焦后才出现的那一行
/// （「Sikapmu (opsional)」）。
/// <p>⚠️ 把这一行挪回与发送键同排，就是把那条决策改掉了 ——
/// `place_comment_composer_test.dart` 有一条测试钉着"未获焦时态度行不在场"。
///
/// <h2>🔴 态度是可选的（AC3），但正文不是</h2>
/// 只选了态度、没写正文 → **发送键仍然是灰的**（服务端也会拒）。
/// 这不是校验从严，这就是「不写评论不能表态」的实现。
class PlaceCommentComposer extends ConsumerStatefulWidget {
  const PlaceCommentComposer({super.key, required this.token});

  final String token;

  @override
  ConsumerState<PlaceCommentComposer> createState() => _PlaceCommentComposerState();
}

class _PlaceCommentComposerState extends ConsumerState<PlaceCommentComposer> {
  final TextEditingController _controller = TextEditingController();
  final FocusNode _focusNode = FocusNode();

  /// 与服务端同一个上限（服务端权威，这里只是提前告知）。
  static const int _maxLen = 200;

  PlaceCommentAttitude? _attitude;
  bool _sending = false;
  bool _expanded = false;

  /// 触达上限只提示一次（回落到 <上限再复位），避免满字后每敲一键连弹
  /// （bug 20260702-218，内容评论踩过）。
  bool _limitToasted = false;

  @override
  void initState() {
    super.initState();
    // 获焦即展开（AC4：态度行在输入的展开态里）。失焦**不收起** ——
    // 用户点 👍 的那一刻输入框会失焦，收起的话那一行会在他手指底下消失。
    _focusNode.addListener(() {
      if (_focusNode.hasFocus && !_expanded) {
        setState(() => _expanded = true);
      }
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  bool get _canSend => _controller.text.trim().isNotEmpty && !_sending;

  Future<void> _send() async {
    final text = _controller.text.trim();
    // 🔴 空正文直接不发 —— 即使选了态度（B1-D3：不写评论不能表态）。
    if (text.isEmpty || _sending) return;
    setState(() => _sending = true);
    final l10n = AppLocalizations.of(context);
    try {
      await ref
          .read(placeRepositoryProvider)
          .createComment(widget.token, text, attitude: _attitude);
      // E-6（bug 20260922-528）：服务端接住才报；放在 mounted 之前，发完即走的也算。
      // ⚠️ 只有 token —— 评论正文 / 态度都不进埋点。
      Analytics.capture('place_comment_posted', {'place_id': widget.token});
      if (!mounted) return;
      // 仅成功后清空 + 收键盘 + 复位态度 + 重拉评论区。
      _controller.clear();
      _limitToasted = false;
      setState(() {
        _attitude = null;
        _expanded = false;
      });
      FocusScope.of(context).unfocus();
      await ref.read(placeCommentsProvider(widget.token).notifier).reload();
      // 🔴 详情的计数行也要跟着变：评论数在**详情响应**里（`commentCount`），
      // 只刷评论区的话，同一屏上「💬 3」和标题「评论 (4)」会对不上（code-review 2026-09-15）。
      invalidatePlaceDetail(ref, widget.token);
    } catch (e) {
      if (!mounted) return;
      final problem = e is DioException ? ProblemDetail.fromDioException(e) : null;
      if (problem?.typeSlug == 'comment-blocked') {
        // 审核硬拦截（L1 词库）→ **保留输入**，提示修改后重试。
        // 与网络失败分开提示：让用户知道"重试没用，得改内容"。
        showAppToast(context, l10n.commentModerationBlocked);
      } else if (problem?.status == 404) {
        // 场所在他写评论的这段时间里被下架了。保留输入没有意义（发给谁？）——
        // 但也不要清空：他可能想把这段文字复制走。
        showAppToast(context, l10n.placeDetailGoneTitle);
      } else {
        // F13：网络/服务器失败 → 提示重试，**保留输入与已选态度**，可直接再点一次。
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

    // 🔒 游客：只读提示框，点击走登录引导（FR-0C）。
    // ⚠️ 评论**列表**对游客是开放的（后端 GET 放行），登录墙只在"要发言"这一刻出现。
    if (isGuest) {
      return _bottomBar(
        child: GestureDetector(
          key: const ValueKey('placeCommentBox'),
          onTap: () => requireLogin(ref, context, onAllowed: () {}),
          child: Container(
            padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.md, vertical: AppSpacing.sm),
            decoration:
                BoxDecoration(color: AppColors.base, borderRadius: AppRounded.lgRadius),
            child: Text(l10n.placeCommentHint, style: AppTypography.caption),
          ),
        ),
      );
    }

    return _bottomBar(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 🔴 AC4：态度行只在展开态出现，且**在输入行之上**，不与发送键并列。
          if (_expanded) ...[
            Row(
              children: [
                Text(l10n.placeAttitudeLabel, style: AppTypography.micro),
                const SizedBox(width: AppSpacing.sm),
                _AttitudeChip(
                  key: const ValueKey('placeAttitudeRecommend'),
                  label: '👍 ${l10n.placeAttitudeRecommend}',
                  selected: _attitude == PlaceCommentAttitude.recommend,
                  onTap: () => _toggle(PlaceCommentAttitude.recommend),
                ),
                const SizedBox(width: AppSpacing.xs),
                _AttitudeChip(
                  key: const ValueKey('placeAttitudeNotRecommend'),
                  label: '👎 ${l10n.placeAttitudeNotRecommend}',
                  selected: _attitude == PlaceCommentAttitude.notRecommend,
                  onTap: () => _toggle(PlaceCommentAttitude.notRecommend),
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.sm),
          ],
          Row(
            children: [
              Expanded(
                child: TextField(
                  key: const ValueKey('placeCommentInput'),
                  controller: _controller,
                  focusNode: _focusNode,
                  maxLength: _maxLen,
                  minLines: 1,
                  maxLines: 3,
                  style: AppTypography.body,
                  // 发送键的可用态跟着正文走 —— 不 setState 的话用户打完字按钮还是灰的。
                  onChanged: (v) {
                    final atLimit = v.characters.length >= _maxLen;
                    if (atLimit && !_limitToasted) {
                      _limitToasted = true;
                      showAppToast(context, l10n.commentLimitReached);
                    } else if (!atLimit && _limitToasted) {
                      _limitToasted = false;
                    }
                    setState(() {});
                  },
                  decoration: InputDecoration(
                    hintText: l10n.placeCommentHint,
                    counterText: '',
                    isDense: true,
                    filled: true,
                    fillColor: AppColors.cream2,
                    contentPadding:
                        const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(999),
                      borderSide: BorderSide.none,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              Material(
                color: _canSend ? AppColors.mint : AppColors.line2,
                shape: const CircleBorder(),
                child: InkWell(
                  key: const ValueKey('placeCommentSend'),
                  customBorder: const CircleBorder(),
                  // 🔴 正文为空 → 不可点，**即使选了态度**（B1-D3）。
                  onTap: _canSend ? _send : null,
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

  /// 再点一次已选中的那个 = 取消表态（AC3 可以不选）。
  ///
  /// 没有这条的话，手滑点了 👎 就再也回不到"不表态"——只能在推荐和不推荐之间二选一，
  /// 而"可以不表态"正是 AC3 的原话。
  void _toggle(PlaceCommentAttitude v) {
    setState(() => _attitude = _attitude == v ? null : v);
  }

  /// ⚠️ 本条**不自己处理 viewInsets**：键盘避让由宿主负责（`place_detail_page.dart` 把它放在
  /// body 的 Column 底部，靠 Scaffold `resizeToAvoidBottomInset` 上移，bug 512）。
  /// 这里再补 `viewInsets.bottom` 会与宿主双算，输入条会飘到键盘上方一整个键盘高。
  /// `SafeArea(top: false)` 在键盘弹起时底部内边距自动归 0（padding = viewPadding − viewInsets）。
  Widget _bottomBar({required Widget child}) {
    return SafeArea(
      top: false,
      child: Container(
        padding: const EdgeInsets.symmetric(
            horizontal: AppSpacing.lg, vertical: AppSpacing.md),
        decoration: const BoxDecoration(
          color: AppColors.surface,
          border: Border(top: BorderSide(color: AppColors.border)),
        ),
        child: child,
      ),
    );
  }
}

class _AttitudeChip extends StatelessWidget {
  const _AttitudeChip({super.key, required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        // 竖向 10 + 文本 ≈ 足够高（UX-DR16 热区）。
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: 10),
        decoration: BoxDecoration(
          color: selected ? AppColors.mintTint : Colors.transparent,
          border: Border.all(color: selected ? AppColors.mint : AppColors.lineViolet),
          borderRadius: BorderRadius.circular(999),
        ),
        child: Text(label,
            style: AppTypography.micro.copyWith(
                color: selected ? AppColors.mint700 : AppColors.textSecondary,
                fontWeight: selected ? FontWeight.w700 : FontWeight.w400)),
      ),
    );
  }
}
