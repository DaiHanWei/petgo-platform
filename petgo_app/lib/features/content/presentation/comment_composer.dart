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
import '../domain/content_detail.dart';
import '../domain/detail_bottom_bar.dart';
import '../data/detail_repository.dart';
import 'content_detail_page.dart';
import 'detail_providers.dart';
import 'like_button.dart';

/// 详情页**固定底栏**（Story 3.5 起为评论框；V1.3.0 Story 2.3 起合并互动栏，FR-114 · AD-A13）。
///
/// 未登录点击触发 FR-0C；登录态可编辑发表一级评论或回复（≤200 字实时计数，服务端权威）。
/// 发表成功后 bump 评论区刷新信号。
///
/// ## 🔴 右侧两态互斥（Story 2.3 · AC2）
/// - **未聚焦且无输入** → 点赞 + 分享（用户进页面看到的默认态）；
/// - **已聚焦或已开始输入** → 发送。
///
/// 判定规则在 [resolveBottomBarMode]（纯函数、L0 可测），**不要在 build 里就地写条件** ——
/// 写在 widget 里就没法钉住「没有第三种组合」这件事。
///
/// ## 评论数不在这里
/// 评论数只在评论区标题「KOMENTAR (N)」出现**一次**（AC3）。底栏不展示计数：
/// 输入框旁边再放一个数字，用户会以为那是「我打了几个字」。
class CommentComposer extends ConsumerStatefulWidget {
  const CommentComposer({super.key, required this.postId, this.detail});

  final int postId;

  /// 供底栏右侧的点赞 / 分享使用。为 null 时底栏退化成纯输入框（未接入互动栏的调用方）。
  final ContentDetail? detail;

  @override
  ConsumerState<CommentComposer> createState() => _CommentComposerState();
}

class _CommentComposerState extends ConsumerState<CommentComposer> {
  final TextEditingController _controller = TextEditingController();
  final FocusNode _focusNode = FocusNode();
  static const int _maxLen = 200;
  bool _sending = false;
  /// 右侧两态的输入端：焦点与「是否已开始输入」。两者任一为真即进 compose 态。
  bool _focused = false;
  bool _hasText = false;
  // 触达字数上限只提示一次（回落到 <上限再复位），避免满字后每敲一键连弹（bug 20260702-218）。
  bool _limitToasted = false;

  @override
  void initState() {
    super.initState();
    // 焦点与文本都要驱动两态切换，各挂一个监听。
    _focusNode.addListener(_onFocusChanged);
    _controller.addListener(_onTextChanged);
  }

  void _onFocusChanged() {
    if (_focused != _focusNode.hasFocus) {
      setState(() => _focused = _focusNode.hasFocus);
    }
  }

  void _onTextChanged() {
    // trim 后非空才算「开始输入」—— 只敲了几个空格不该把点赞分享顶掉。
    final has = _controller.text.trim().isNotEmpty;
    if (_hasText != has) {
      // AC3 第二种退出方式：**打过字又清空 = 退出回复态**。
      // 改前只有点 ✕ 一条路，而「全选删掉重写」是用户改主意时最自然的动作 ——
      // 删干净以后他以为自己在发新评论，实际还挂在别人的回复态上。
      //
      // 🔴 只在 true → false 这一跳上退出，不是「只要为空就退出」：
      // 刚点回复时输入框本来就是空的，那时退出会让回复态当场自毁。
      if (!has) {
        ref.read(replyTargetProvider.notifier).clear();
      }
      setState(() => _hasText = has);
    }
  }

  @override
  void dispose() {
    _focusNode.removeListener(_onFocusChanged);
    _controller.removeListener(_onTextChanged);
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  Future<void> _send(int? parentId) async {
    final text = _controller.text.trim();
    if (text.isEmpty || _sending) return;
    setState(() => _sending = true);
    try {
      final repo = ref.read(detailRepositoryProvider);
      if (parentId != null) {
        final created = await repo.postReply(parentId, text);
        // 🔴 AC5：登记落点，让评论区重拉完成后**展开这条父评论并滚动过去**。
        // 二级默认只内嵌 3 条，新回复按时间正序排在最后 —— 不登记的话，
        // 回复超过 3 条的评论时，用户发完屏幕上什么都没变。
        // 带上新回复的 id：回复区不止一页时，评论区要靠它知道翻到哪儿才算到位。
        // 真正的展开/滚动在 CommentSection 里做（那边才知道刷新什么时候结束）。
        ref
            .read(replyLandingProvider.notifier)
            .request(parentId: parentId, replyId: created.id);
      } else {
        final created = await repo.postComment(widget.postId, text);
        // 🔴 记下刚发的这条，让评论区把它置顶（Story 2.5 · AC6）。
        // 热度序下 0 赞的新评论会排到第一页之外 —— 不记的话用户发完找不到自己的评论。
        // 只对**一级**评论做：二级回复挂在父评论下，位置由父决定，不存在找不到的问题。
        ref.read(sessionPinnedCommentsProvider.notifier).add(created);
      }
      if (!mounted) return;
      // 仅成功后清空输入 + 收起键盘 + 退出回复态 + 刷新评论区（AC3）。
      _controller.clear();
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
    //
    // 🔴 右侧动作**照常渲染**：游客不能评论，但点赞（转登录引导）与分享（本就允许）
    // 都必须在。Story 2.3 把分享从正文下方挪进底栏，若这里省掉动作，
    // 游客就彻底**没有分享入口**了 —— 那是把一个既有能力做没了。
    if (isGuest) {
      return _bottomBar(
        child: Row(
          children: [
            Expanded(
              child: GestureDetector(
                key: const ValueKey('detailCommentBox'),
                onTap: () => requireLogin(ref, context, onAllowed: () {}),
                child: _hintPill(l10n.detailCommentHint),
              ),
            ),
            const SizedBox(width: AppSpacing.sm),
            // 游客永远停在 actions 态：他没有输入框可聚焦，也没有草稿。
            _actions(),
          ],
        ),
      );
    }

    // 登录态：可编辑发表 / 回复。
    return _bottomBar(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // AC1：回复态指示改**胶囊**（原为一行纯文本）。有底色、有轮廓，
          // 用户一眼能看出「我现在处在一个特殊状态里」，而不是以为那只是一行说明文字。
          if (replyTarget != null)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.xs),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Container(
                  key: const ValueKey('replyingToPill'),
                  padding: const EdgeInsets.symmetric(
                      horizontal: AppSpacing.sm, vertical: AppSpacing.xxs),
                  decoration: BoxDecoration(
                    color: AppColors.cream2,
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Flexible(
                        child: Text(
                          l10n.commentReplyingTo(replyTarget.toName),
                          style: AppTypography.micro.copyWith(color: AppColors.ink2),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      const SizedBox(width: AppSpacing.xs),
                      // 图标缩到 14 是为了配胶囊的密度；命中框靠 padding + opaque 补回来
                      // （同 Story 2.3 · AC4 的做法：扩热区、不放大图标）。
                      GestureDetector(
                        key: const ValueKey('cancelReply'),
                        behavior: HitTestBehavior.opaque,
                        onTap: () => ref.read(replyTargetProvider.notifier).clear(),
                        child: const Padding(
                          padding: EdgeInsets.all(AppSpacing.xs),
                          child: Icon(Icons.close_rounded,
                              size: 14, color: AppColors.textTertiary),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
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
                  },
                  minLines: 1,
                  maxLines: 3,
                  style: AppTypography.body,
                  decoration: InputDecoration(
                    // AC2：占位随回复态切换。改前恒为通用文案 ——
                    // 那正是用户分不清「我这条是发新评论还是回复某人」的直接原因。
                    hintText: replyTarget == null
                        ? l10n.detailCommentHint
                        : l10n.commentReplyHint(replyTarget.toName),
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
              // 🔴 右侧两态（AC2）：未聚焦且无输入 → 点赞 + 分享；否则 → 发送。
              // 判定走 resolveBottomBarMode，**别在这里就地写 if** —— 枚举只有两个值，
              // 「没有第三种组合」这件事因此在类型上成立。
              switch (resolveBottomBarMode(focused: _focused, hasText: _hasText)) {
                DetailBottomBarMode.compose => _sendButton(replyTarget?.parentId),
                DetailBottomBarMode.actions => _actions(),
              },
            ],
          ),
        ],
      ),
    );
  }

  /// 紫色实心圆发送钮（detail.html）。
  Widget _sendButton(int? parentId) => Material(
        color: AppColors.mint,
        shape: const CircleBorder(),
        child: InkWell(
          key: const ValueKey('detailCommentSend'),
          customBorder: const CircleBorder(),
          onTap: _sending ? null : () => _send(parentId),
          child: const SizedBox(
            width: 42,
            height: 42,
            child: Icon(Icons.send_rounded, size: 20, color: AppColors.onAccent),
          ),
        ),
      );

  /// 默认态：点赞 + 分享，始终悬浮可点（AC1）。
  ///
  /// detail 为 null（未接入互动栏的调用方）时退化成空占位，底栏仍是一条纯输入框。
  Widget _actions() {
    final detail = widget.detail;
    if (detail == null) return const SizedBox.shrink();
    return Row(
      key: const ValueKey('detailBottomActions'),
      mainAxisSize: MainAxisSize.min,
      children: [
        // 🔴 热区**隐性扩展**到 44×44，可见图标仍是 19（AC4）：19px 直接当按钮手指点不准，
        // 但把图标画大会改变设计稿的视觉密度。所以扩的是命中框，不是图标。
        _tapTarget(LikeButton(
          postId: detail.id,
          initialLiked: detail.liked,
          initialCount: detail.likeCount,
          // 🛡 两个挂载点都必须传来源，否则「首页点赞是净增还是前移」这个对比失效。
          source: 'detail',
        )),
        const SizedBox(width: DetailBarMetrics.iconGap),
        _tapTarget(DetailShareCardButton(detail: detail)),
      ],
    );
  }

  /// 把一个小图标包进 44×44 的透明命中框（可见大小不变）。
  Widget _tapTarget(Widget child) => ConstrainedBox(
        constraints: const BoxConstraints(
          minWidth: DetailBarMetrics.minTapTarget,
          minHeight: DetailBarMetrics.minTapTarget,
        ),
        child: Center(widthFactor: 1, heightFactor: 1, child: child),
      );

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
