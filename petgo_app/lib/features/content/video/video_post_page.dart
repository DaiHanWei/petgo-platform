import 'package:flutter/material.dart';

import '../domain/content_detail.dart';
import 'video_nav.dart';
import 'video_post_overlay.dart';
import 'video_player_view.dart';

/// 视频帖的全屏播放页（专题线 video Story 2.4 · FR-15 · AD-10）：`/content/:id` 拉到详情后按 `mediaType` 在页内渲染。
///
/// 黑底、App 保持竖屏（全局已锁）。作者区 / 正文 / 互动叠加层由 Story 2.5 通过 [overlayBuilder] 挂上。
class VideoPostPage extends StatefulWidget {
  const VideoPostPage({
    super.key,
    required this.detail,
    required this.source,
    this.focusComments = false,
    this.overlayBuilder,
  });

  final ContentDetail detail;
  final VideoSource source;

  /// `?focus=comments` 进来：自动打开评论抽屉（Story 2.5 消费）。
  final bool focusComments;

  /// 叠加层；缺省为 [VideoPostOverlay]（Story 2.5：作者区、正文、互动）。测试可替换。
  final Widget Function(BuildContext context, ContentDetail detail)? overlayBuilder;

  @override
  State<VideoPostPage> createState() => _VideoPostPageState();
}

class _VideoPostPageState extends State<VideoPostPage> {
  /// 评论抽屉是否打开：打开时画面收到上方，不被抽屉挡住。
  final _commentsOpen = ValueNotifier<bool>(false);

  @override
  void dispose() {
    _commentsOpen.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final detail = widget.detail;
    final overlayBuilder = widget.overlayBuilder;
    final video = detail.video!;
    return Scaffold(
      key: const ValueKey('videoPostPage'),
      backgroundColor: Colors.black,
      // 评论抽屉弹键盘时不压缩播放区（画面不跳）；抽屉自己做键盘避让（KeyboardInset）。
      resizeToAvoidBottomInset: false,
      body: VideoPlayerView(
        postId: detail.id,
        url: video.url,
        durationMs: video.durationMs,
        source: widget.source,
        compact: _commentsOpen,
        coverUrl: detail.imageUrls.isEmpty ? null : detail.imageUrls.first,
        overlay: Stack(children: [
          overlayBuilder != null
              ? overlayBuilder(context, detail)
              : VideoPostOverlay(detail: detail, focusComments: widget.focusComments, commentsOpen: _commentsOpen),
          const VideoBackButton(),
        ]),
      ),
    );
  }
}

/// 视频页左上角返回（白色，压在黑底上）。
class VideoBackButton extends StatelessWidget {
  const VideoBackButton({super.key});

  @override
  Widget build(BuildContext context) => Positioned(
        left: 4,
        top: MediaQuery.paddingOf(context).top + 4,
        child: IconButton(
          key: const ValueKey('videoBack'),
          icon: const Icon(Icons.arrow_back_rounded, color: Colors.white),
          onPressed: () => Navigator.of(context).maybePop(),
        ),
      );
}

/// 已知是视频帖、详情还没拉到时的加载态（从列表进来跳过图文详情的白底加载骨架）。
class VideoPostLoading extends StatelessWidget {
  const VideoPostLoading({super.key});

  @override
  Widget build(BuildContext context) => const Scaffold(
        key: ValueKey('videoPostLoading'),
        backgroundColor: Colors.black,
        body: Stack(children: [
          Center(child: CircularProgressIndicator(color: Colors.white)),
          VideoBackButton(),
        ]),
      );
}
