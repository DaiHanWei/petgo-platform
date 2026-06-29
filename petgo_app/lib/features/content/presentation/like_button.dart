import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../features/auth/domain/auth_guard.dart';
import '../data/like_repository.dart';

/// 详情互动栏点赞按钮（Story 3.4，FR-23）。区域色心形 + 计数；乐观更新（失败回滚）。
///
/// 未登录点击触发 FR-0C（强登录弹窗）；登录态 toggle 调点赞/取消端点并以后端真值校正。
class LikeButton extends ConsumerStatefulWidget {
  const LikeButton({
    super.key,
    required this.postId,
    required this.initialLiked,
    required this.initialCount,
  });

  final int postId;
  final bool initialLiked;
  final int initialCount;

  @override
  ConsumerState<LikeButton> createState() => _LikeButtonState();
}

class _LikeButtonState extends ConsumerState<LikeButton> {
  late bool _liked = widget.initialLiked;
  late int _count = widget.initialCount;
  bool _inFlight = false;

  Future<void> _toggle() async {
    if (_inFlight) return;
    // 门控：未登录 → FR-0C，不发请求。
    final allowed = requireLogin(ref, context, onAllowed: () {});
    if (!allowed) return;

    Analytics.capture('post_like_tapped', {'liked': !_liked});
    final prevLiked = _liked;
    final prevCount = _count;
    // 乐观更新：先翻转 UI。
    setState(() {
      _liked = !prevLiked;
      _count = prevCount + (_liked ? 1 : -1);
      _inFlight = true;
    });
    try {
      final repo = ref.read(likeRepositoryProvider);
      final result = _liked ? await repo.like(widget.postId) : await repo.unlike(widget.postId);
      if (!mounted) return;
      // 以后端真值校正（防并发漂移）。
      setState(() {
        _liked = result.liked;
        _count = result.likeCount;
        _inFlight = false;
      });
    } catch (_) {
      if (!mounted) return;
      // 失败回滚。
      setState(() {
        _liked = prevLiked;
        _count = prevCount;
        _inFlight = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return InkWell(
      key: const ValueKey('detailLikeButton'),
      onTap: _toggle,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            _liked ? Icons.favorite_rounded : Icons.favorite_border_rounded,
            size: 20,
            // 点赞红心（设计稿）：激活态用暖红 likeHeart，与 Feed 卡片点赞一致。
            color: _liked ? AppColors.likeHeart : AppColors.textSecondary,
          ),
          const SizedBox(width: AppSpacing.xs),
          Text('$_count', style: AppTypography.caption),
        ],
      ),
    );
  }
}
