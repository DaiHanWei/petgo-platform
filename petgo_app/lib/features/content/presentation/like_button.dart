import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../features/auth/domain/auth_guard.dart';
import '../data/like_repository.dart';

/// 点赞按钮（Story 3.4 FR-23；V1.1.6 Story 3.2 起首页也用）。心形 + 计数；乐观更新（失败回滚）。
///
/// 未登录点击触发 FR-0C（强登录弹窗）；登录态 toggle 调点赞/取消端点并以后端真值校正。
///
/// ⚠️ 本组件是 V1.1.6 Story 3.2 要求**直接复用、不得重写**的那个 —— 状态机
/// （乐观更新 / 后端真值校正 / 失败回滚 / 未登录门控）是既有资产。改它之前先想清楚。
class LikeButton extends ConsumerStatefulWidget {
  const LikeButton({
    super.key,
    required this.postId,
    required this.initialLiked,
    required this.initialCount,
    required this.source,
  });

  final int postId;
  final bool initialLiked;
  final int initialCount;

  /// 埋点来源：`feed`（首页）/ `detail`（详情页）。
  ///
  /// 🛡 **两个挂载点都必须传**（V1.1.6 Story 3.2 · AC6）——
  /// 只改一侧的话，「开放首页点赞到底是净增长还是把详情页的点赞前移了」这个对比
  /// 就**彻底失效**，而这正是本版本要回答的问题。故设为必填参数：漏传编译不过。
  final String source;

  @override
  ConsumerState<LikeButton> createState() => _LikeButtonState();
}

class _LikeButtonState extends ConsumerState<LikeButton> {
  late bool _liked = widget.initialLiked;
  late int _count = widget.initialCount;
  bool _inFlight = false;

  /// 🔴 跟随外部数据更新（V1.1.6 Story 3.2）。
  ///
  /// 本组件原来只在创建时读一次初始值 —— 详情页只有一个实例、进来即销毁，没问题。
  /// 但**进了首页列表**之后，下拉刷新会带回新的点赞态，而 Flutter 会**复用同一个
  /// State 对象**（同类型同位置）：不同步的话，用户刷新后看到的还是旧的点赞态与计数。
  ///
  /// ⚠️ 只在「换了另一条内容」或「请求不在飞行中」时才跟随 ——
  /// 否则会把用户刚点出来的乐观更新给盖回去。
  @override
  void didUpdateWidget(covariant LikeButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    final switchedPost = oldWidget.postId != widget.postId;
    if (switchedPost) {
      _liked = widget.initialLiked;
      _count = widget.initialCount;
      _inFlight = false;
      return;
    }
    if (!_inFlight &&
        (oldWidget.initialLiked != widget.initialLiked ||
            oldWidget.initialCount != widget.initialCount)) {
      _liked = widget.initialLiked;
      _count = widget.initialCount;
    }
  }

  Future<void> _toggle() async {
    if (_inFlight) return;
    // 门控：未登录 → FR-0C，不发请求。
    final allowed = requireLogin(ref, context, onAllowed: () {});
    if (!allowed) return;

    // 🛡 只加 source 属性，**事件名不动** —— 改名会切断已有的点赞历史序列
    // （埋点清单 E-22 与命名护栏测试都盯着这一点）。
    Analytics.capture('post_like_tapped', {'liked': !_liked, 'source': widget.source});
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
    // 🔴 key 必须带内容编号：本组件原先写死成详情页专用的名字，
    // 进了首页列表后一屏多张卡会**重复 key**（Flutter 里重复 key 会报错或错配状态）。
    return InkWell(
      key: ValueKey('likeButton_${widget.postId}'),
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
          // 数字与心形**同步变色**（FR-93）：改前数字恒为次要色，已赞时只有图标变红，
          // 看上去像"没点上"。
          Text(
            '$_count',
            style: AppTypography.caption.copyWith(
              color: _liked ? AppColors.likeHeart : AppColors.textSecondary,
            ),
          ),
        ],
      ),
    );
  }
}
