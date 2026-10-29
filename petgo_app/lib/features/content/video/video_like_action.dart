import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../auth/domain/auth_guard.dart';
import '../data/like_repository.dart';
import '../presentation/post_likers_sheet.dart';

/// 播放页右侧竖栏的点赞（专题线 video · L2 走查遗留）：爱心在上、数字在下，与评论按钮同一排法。
///
/// 🔴 **不复用 [LikeButton]**（2026-10-17 用户拍板）：那是 Feed / 详情页的横排组件，改它会波及其它页面。
/// 行为与它逐项一致：乐观更新 + 后端真值校正 + 失败回滚、游客登录墙、点数字打开点赞人列表、
/// 落定后经 [onSettled] 回写 Feed 计数；埋点事件名与属性同详情页（`source=detail` /
/// `likersSource=content_detail`），播放页的点赞在看板上与详情页同口径。
class VideoLikeAction extends ConsumerStatefulWidget {
  const VideoLikeAction({
    super.key,
    required this.postId,
    required this.initialLiked,
    required this.initialCount,
    this.onSettled,
  });

  final int postId;
  final bool initialLiked;
  final int initialCount;

  /// 点赞 / 取消以后端真值落定后的回调；失败回滚不回调。
  final void Function(bool liked, int likeCount)? onSettled;

  @override
  ConsumerState<VideoLikeAction> createState() => _VideoLikeActionState();
}

class _VideoLikeActionState extends ConsumerState<VideoLikeAction> {
  late bool _liked = widget.initialLiked;
  late int _count = widget.initialCount;
  bool _inFlight = false;

  /// 详情重拉（发评论后）会带回新值：请求不在飞行中才跟随，免得盖掉刚点出来的乐观更新。
  @override
  void didUpdateWidget(covariant VideoLikeAction oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.postId != widget.postId) {
      _liked = widget.initialLiked;
      _count = widget.initialCount;
      _inFlight = false;
      return;
    }
    if (!_inFlight &&
        (oldWidget.initialLiked != widget.initialLiked || oldWidget.initialCount != widget.initialCount)) {
      _liked = widget.initialLiked;
      _count = widget.initialCount;
    }
  }

  Future<void> _toggle() async {
    if (_inFlight) return;
    if (!requireLogin(ref, context, onAllowed: () {})) return;
    Analytics.capture('post_like_tapped', {'liked': !_liked, 'source': 'detail'});
    final prevLiked = _liked;
    final prevCount = _count;
    setState(() {
      _liked = !prevLiked;
      _count = prevCount + (_liked ? 1 : -1);
      _inFlight = true;
    });
    try {
      final repo = ref.read(likeRepositoryProvider);
      final result = _liked ? await repo.like(widget.postId) : await repo.unlike(widget.postId);
      if (!mounted) return;
      setState(() {
        _liked = result.liked;
        _count = result.likeCount;
        _inFlight = false;
      });
      widget.onSettled?.call(result.liked, result.likeCount);
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _liked = prevLiked;
        _count = prevCount;
        _inFlight = false;
      });
    }
  }

  /// 点数字：计数为 0 无反应；游客走登录引导；否则弹点赞人抽屉。
  void _openLikers() {
    if (_count <= 0) return;
    if (!requireLogin(ref, context, entrySource: 'likers')) return;
    showPostLikersSheet(context, postId: widget.postId, source: 'content_detail');
  }

  @override
  Widget build(BuildContext context) {
    final color = _liked ? AppColors.likeHeart : Colors.white;
    // 爱心与数字是两块互不重叠的热区（与 LikeButton 同规则）：爱心 = 点赞，数字 = 点赞人列表。
    // 尺寸与评论按钮对齐：图标 22、数字 11、四周 6 的内边距。
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        InkWell(
          key: ValueKey('likeButton_${widget.postId}'),
          customBorder: const CircleBorder(),
          onTap: _toggle,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(6, 6, 6, 0),
            child: Icon(
              _liked ? Icons.favorite_rounded : Icons.favorite_border_rounded,
              size: 22,
              color: color,
              shadows: _shadow,
            ),
          ),
        ),
        GestureDetector(
          key: ValueKey('likeCount_${widget.postId}'),
          behavior: HitTestBehavior.opaque,
          onTap: _openLikers,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(10, 0, 10, 6),
            child: Text('$_count', style: TextStyle(fontSize: 11, color: color, shadows: _shadow)),
          ),
        ),
      ],
    );
  }
}

const List<Shadow> _shadow = [Shadow(color: Color(0x66000000), blurRadius: 6)];
