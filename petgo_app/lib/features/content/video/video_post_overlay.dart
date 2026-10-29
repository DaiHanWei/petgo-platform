import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/keyboard_safe_area.dart';
import '../../auth/domain/auth_state.dart';
import '../../mention/domain/mention_context.dart';
import '../../mention/presentation/mention_text.dart';
import '../../social/domain/account_action_entry.dart';
import '../../user_profile/presentation/public_profile_page.dart';
import '../domain/content_detail.dart';
import '../presentation/author_moderation_callbacks.dart';
import '../presentation/comment_composer.dart';
import '../presentation/comment_section.dart';
import '../presentation/content_detail_page.dart';
import '../presentation/detail_providers.dart';
import '../presentation/feed_controller.dart';
import 'video_like_action.dart';
import 'video_player_view.dart';

/// 播放页叠加层（专题线 video Story 2.5 · FR-16 · AD-10）：作者区、正文、点赞 / 点赞人、评论抽屉、分享、举报或删除。
///
/// 规则与图文帖详情页一致（V-13），组件复用：`FollowButton`、`CommentSection`、
/// `CommentComposer`、`DetailShareCardButton`、`showContentMoreSheet`、`openUserProfile`。
/// 点赞是竖排的 [VideoLikeAction]（行为同 `LikeButton`，排法不同，见该类）。游客的登录墙由这些组件自己处理。叠在画面之上、播放器控件（静音 / 进度条）之下。
class VideoPostOverlay extends ConsumerStatefulWidget {
  const VideoPostOverlay({super.key, required this.detail, this.focusComments = false, this.commentsOpen});

  final ContentDetail detail;

  /// 评论抽屉开合状态（播放页据此把画面收到上方）。
  final ValueNotifier<bool>? commentsOpen;

  /// `?focus=comments` 进来：首帧后自动打开一次评论抽屉。
  final bool focusComments;

  @override
  ConsumerState<VideoPostOverlay> createState() => _VideoPostOverlayState();
}

class _VideoPostOverlayState extends ConsumerState<VideoPostOverlay> {
  @override
  void initState() {
    super.initState();
    if (widget.focusComments) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) showVideoCommentsSheet(context, widget.detail, openState: widget.commentsOpen);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final d = widget.detail;
    final bottomInset = MediaQuery.paddingOf(context).bottom;
    return Stack(
      children: [
        // 底部渐变遮罩：让白字在任何画面上都可读（亮画面也一样）。不吃点击（单击暂停仍落到画面）。
        const Positioned(
          left: 0,
          right: 0,
          bottom: 0,
          height: 340,
          child: IgnorePointer(
            child: DecoratedBox(
              key: ValueKey('videoBottomScrim'),
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [Color(0x00000000), Color(0x80000000), Color(0xD9000000)],
                  stops: [0, 0.45, 1],
                ),
              ),
            ),
          ),
        ),
        // 左下：作者区 + 正文（进度条在最底，留出它的高度）。
        Positioned(
          left: 16,
          right: 88,
          bottom: bottomInset + 56,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              // 作者区与图文详情共用同一个组件（规则只有一份，V-13）。
              ContentAuthorRow(detail: d, onDark: true),
              if ((d.body ?? '').isNotEmpty) ...[
                const SizedBox(height: 8),
                ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 120),
                  child: SingleChildScrollView(
                    child: MentionText(
                      text: d.body!,
                      mentions: d.mentions,
                      mentionContext: MentionContext.post,
                      style: const TextStyle(color: Colors.white, fontSize: 14, height: 1.4, shadows: _textShadow),
                      onTapUser: (userId) => openUserProfile(context, ref, userId,
                          entry: AccountActionEntry.mention,
                          onBlocked: () {
                            onAuthorHidden(ref, userId)();
                            ref.read(commentsRefreshProvider.notifier).bump();
                          },
                          onReported: () {
                            onAuthorHidden(ref, userId)();
                            ref.read(commentsRefreshProvider.notifier).bump();
                          }),
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
        // 右侧竖排：点赞 / 评论 / 分享 / 更多。白色图标 + 阴影直接压在画面上（不放浅色底板，
        // 黑底播放页上底板突兀且会挡竖屏画面）；复用组件走 onDark 配色。
        Positioned(
          right: 12,
          bottom: bottomInset + 64,
          child: Padding(
            key: const ValueKey('videoActions'),
            padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 6),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // 竖排点赞（图标在上、数字在下），与下面的评论同一排法；不复用横排的 LikeButton。
                VideoLikeAction(
                  postId: d.id,
                  initialLiked: d.liked,
                  initialCount: d.likeCount,
                  onSettled: (liked, count) => syncFeedCounts(ref, d.id, liked: liked, likeCount: count),
                ),
                const SizedBox(height: 12),
                InkWell(
                  key: const ValueKey('videoCommentButton'),
                  customBorder: const CircleBorder(),
                  onTap: () => showVideoCommentsSheet(context, d, openState: widget.commentsOpen),
                  child: Padding(
                    padding: const EdgeInsets.all(6),
                    child: Column(children: [
                      const Icon(Icons.chat_bubble_outline_rounded, size: 22, color: Colors.white, shadows: _textShadow),
                      Text('${d.commentCount}',
                          style: const TextStyle(fontSize: 11, color: Colors.white, shadows: _textShadow)),
                    ]),
                  ),
                ),
                const SizedBox(height: 12),
                DetailShareCardButton(detail: d, onDark: true),
                const SizedBox(height: 12),
                IconButton(
                  key: const ValueKey('videoMoreButton'),
                  icon: const Icon(Icons.more_horiz, color: Colors.white, shadows: _textShadow),
                  onPressed: () => showContentMoreSheet(context, ref, d, l10n),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

/// 白字 / 白图标压在画面上的统一阴影。
const List<Shadow> _textShadow = [Shadow(color: Color(0x66000000), blurRadius: 6)];

/// 评论抽屉（专题线 video Story 2.5 · FR-16）：评论列表 + 输入框。**视频继续播放**（不暂停）。
/// 键盘避让：`isScrollControlled` + 固定高度 sheet 套 [KeyboardInset]（petgo_app/CLAUDE.md「底部弹层」）。
/// [openState]：抽屉开着时置 true，播放页据此把画面收到上方（抽屉不挡画面）。
Future<void> showVideoCommentsSheet(BuildContext context, ContentDetail detail,
    {ValueNotifier<bool>? openState}) async {
  openState?.value = true;
  try {
    await _showCommentsSheet(context, detail);
  } finally {
    openState?.value = false;
  }
}

Future<void> _showCommentsSheet(BuildContext context, ContentDetail detail) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.surface,
    barrierColor: Colors.black26,
    shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
    builder: (sheetCtx) => KeyboardInset(
      child: FractionallySizedBox(
        heightFactor: 1 - kVideoCompactTopFraction,
        child: Consumer(
          builder: (ctx, ref, _) {
            final currentUserId = ref.watch(authControllerProvider).profile?.id;
            return Column(
              key: const ValueKey('videoCommentsSheet'),
              children: [
                Container(
                  width: 36,
                  height: 4,
                  margin: const EdgeInsets.symmetric(vertical: 10),
                  decoration: BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
                ),
                Expanded(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: CommentSection(
                      postId: detail.id,
                      currentUserId: currentUserId,
                      postAuthorId: detail.authorId,
                      isContentAuthor: detail.isAuthor,
                      // 在抽屉里拉黑 / 举报了帖主：先关抽屉，再退出播放页（本帖对本人已不可见），
                      // 与图文详情页「退回列表 + 移除卡片」一致（code review）。
                      onPostAuthorHidden: (authorId) {
                        Navigator.of(sheetCtx).pop();
                        onAuthorHidden(ref, authorId, popContext: context.mounted ? context : null)();
                      },
                    ),
                  ),
                ),
                // 只要输入框：点赞 / 分享已在播放页右侧（detail 不传 → 底栏退化为纯输入框）。
                CommentComposer(postId: detail.id),
              ],
            );
          },
        ),
      ),
    ),
  );
}
