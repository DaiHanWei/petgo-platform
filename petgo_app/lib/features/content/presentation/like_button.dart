import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../features/auth/domain/auth_guard.dart';
import '../data/like_repository.dart';
import 'post_likers_sheet.dart';

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
    required this.likersSource,
    this.feedTab,
    this.rankMode,
    this.onSettled,
    this.expandCountHitArea = true,
    this.onDark = false,
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

  /// 点数字打开点赞人抽屉时的埋点 `post_likers_opened.source`（V1.3.4）：
  /// `feed_card`（首页卡片）/ `content_detail`（详情页底栏）。必填，理由同 [source]。
  final String likersSource;

  /// 数字热区向右多出的透明命中宽度（视觉不变）。调用方据此把后面的间距减掉同样的量，
  /// 使可见间距与拆分前一致。
  static const double countTrailingHitExtent = 12;

  /// 数字热区上下各多出的透明命中高度：数字文本行高约 18，上下各 7 → 总高约 32dp。
  static const double countVerticalHitPadding = 7;

  /// 心形图标可见尺寸。
  static const double heartSize = 20;

  /// 详情页底栏（[expandCountHitArea] = false）爱心 / 数字热区的整高与爱心上下各补的透明命中高度：
  /// 把两块热区都撑到底栏 44dp 命中框的整高（外层 ConstrainedBox + Center 只居中、不扩大命中范围，
  /// 不补的话爱心只剩 20×20、数字只剩文字本身可点 —— OCR 审查 #3）。
  static const double barHitHeight = 44;
  static const double barVerticalHitPadding = (barHitHeight - heartSize) / 2;

  /// 数字热区撑高整行 ≈12dp（32 - 图标 20）；Feed 卡片操作行上下内边距各减去这个值的一半，
  /// 使卡片整体高度、爱心与数字的视觉位置都与拆分前一致。
  static const double countVerticalHitTrim = 6;

  /// 是否放大数字热区（右 12dp + 上下 6.9dp）。Feed 小卡上数字很小，需要放大（默认 true）；
  /// 详情页底栏已有 44dp 的外层命中框，传 false 以保持爱心 + 数字原有位置。
  final bool expandCountHitArea;

  /// 压在深色画面上（视频播放页）：未赞态用白色 + 阴影，已赞仍是红心。
  final bool onDark;

  /// 埋点 `feed_tab`（V1.1.6 Story 16.5）。只有从 Feed 卡片来时才有值。
  final String? feedTab;

  /// 埋点 `rank_mode`（V1.1.6 Story 16.5，服务端下发）。同上。
  ///
  /// 🛡 两个都做成可选：详情页的点赞没有"哪个 Tab、哪条排序路径"这回事，
  /// 硬填一个值会在看板上造出不存在的 Feed 会话。
  final String? rankMode;

  /// 点赞/取消以后端真值落定后的回调（bug 20260923-538）：详情页据此把点赞态写回 Feed 快照。
  /// 失败回滚不回调（UI 回到原值，快照本就没变）。
  final void Function(bool liked, int likeCount)? onSettled;

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
    Analytics.capture('post_like_tapped', {
      'liked': !_liked,
      'source': widget.source,
      // V1.1.6 Story 16.5：首页点赞是 FR-95 效果归因的主要信号 ——
      // 🔴 两个属性都要有。只有 feed_tab 不够：降级链级别 4 会让 ALL Tab 也走时间倒序，
      // 那时 feed_tab=all 但 rank_mode=chrono，把它算进推荐序的效果里就是错的。
      if (widget.feedTab != null) 'feed_tab': widget.feedTab!,
      if (widget.rankMode != null) 'rank_mode': widget.rankMode!,
    });
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
      widget.onSettled?.call(result.liked, result.likeCount);
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

  /// 点数字（V1.3.4 点赞人列表）：本地计数为 0 → 无反应（以乐观更新后的值判断）；
  /// 游客 → 登录引导（不打开抽屉）；否则弹点赞人抽屉。不触碰点赞状态。
  void _openLikers() {
    if (_count <= 0) return;
    if (!requireLogin(ref, context, entrySource: 'likers')) return;
    showPostLikersSheet(context, postId: widget.postId, source: widget.likersSource);
  }

  @override
  Widget build(BuildContext context) {
    final color = _liked ? AppColors.likeHeart : (widget.onDark ? Colors.white : AppColors.textSecondary);
    final shadows = widget.onDark ? const [Shadow(color: Color(0x66000000), blurRadius: 6)] : null;
    // V1.3.4：爱心与数字是**两个互不重叠**的热区 —— 爱心 = 点赞（行为与拆分前一致），
    // 数字 = 点赞人列表。数字热区吃掉原先图标与数字之间的空隙并向右多出
    // [LikeButton.countTrailingHitExtent]（Feed 小卡上数字很小，不放大点不准）。
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        // 🔴 key 必须带内容编号：本组件原先写死成详情页专用的名字，
        // 进了首页列表后一屏多张卡会**重复 key**（Flutter 里重复 key 会报错或错配状态）。
        _heartHitArea(InkWell(
          key: ValueKey('likeButton_${widget.postId}'),
          onTap: _toggle,
          child: Icon(
            _liked ? Icons.favorite_rounded : Icons.favorite_border_rounded,
            size: LikeButton.heartSize,
            // 点赞红心（设计稿）：激活态用暖红 likeHeart，与 Feed 卡片点赞一致。
            color: color,
            shadows: shadows,
          ),
        )),
        GestureDetector(
          key: ValueKey('likeCount_${widget.postId}'),
          behavior: HitTestBehavior.opaque,
          onTap: _openLikers,
          child: Padding(
            padding: widget.expandCountHitArea
                ? const EdgeInsets.fromLTRB(
                    AppSpacing.xs,
                    LikeButton.countVerticalHitPadding,
                    LikeButton.countTrailingHitExtent,
                    LikeButton.countVerticalHitPadding)
                : const EdgeInsets.only(left: AppSpacing.xs),
            // 数字与心形**同步变色**（FR-93）：改前数字恒为次要色，已赞时只有图标变红，
            // 看上去像"没点上"。
            child: _countHitHeight(Text('$_count', style: AppTypography.caption.copyWith(color: color, shadows: shadows))),
          ),
        ),
      ],
    );
  }

  /// 详情页底栏：数字热区固定撑满 [LikeButton.barHitHeight]（文字只有约 18dp 高）。Feed 卡片原样返回。
  Widget _countHitHeight(Widget text) => widget.expandCountHitArea
      ? text
      : SizedBox(height: LikeButton.barHitHeight, child: Center(widthFactor: 1, child: text));

  /// 详情页底栏：爱心外包一层透明命中区，上下撑满 44dp 底栏高度（不改可见位置、不放大水波纹）。
  /// Feed 卡片（[LikeButton.expandCountHitArea] = true）沿用原 20×20 命中区，避免改动卡片布局。
  Widget _heartHitArea(Widget heart) {
    if (widget.expandCountHitArea) return heart;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: _toggle,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: LikeButton.barVerticalHitPadding),
        child: heart,
      ),
    );
  }
}
