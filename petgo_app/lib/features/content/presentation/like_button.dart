import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../features/auth/domain/auth_guard.dart';
import '../domain/post_like_state.dart';
import 'post_likers_sheet.dart';

/// 点赞按钮（Story 3.4 FR-23；V1.1.6 Story 3.2 起首页也用）。心形 + 计数；乐观更新（失败回滚）。
///
/// 未登录点击触发 FR-0C（强登录弹窗）；登录态 toggle 调点赞/取消端点并以后端真值校正。
///
/// ⚠️ V1.5.0 FR-210 起状态已外提到 `postLikeStoreProvider`（`post_like_state.dart`）；本组件只负责外观、登录门控与触发。
/// 改点赞逻辑去改 store，不要在这里重新存状态。
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
    this.reason,
    this.onSettled,
    this.expandCountHitArea = true,
    this.snapshotEpoch = 0,
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

  /// 埋点 `feed_tab`（V1.1.6 Story 16.5）。只有从 Feed 卡片来时才有值。
  final String? feedTab;

  /// 埋点 `rank_mode`（V1.1.6 Story 16.5，服务端下发）。同上。
  ///
  /// 🛡 两个都做成可选：详情页的点赞没有"哪个 Tab、哪条排序路径"这回事，
  /// 硬填一个值会在看板上造出不存在的 Feed 会话。
  final String? rankMode;

  /// 埋点 `reason`（V1.4.2 social-topic Story 1.5 · AC5.5 · PRD §4）：只在关注流卡片上带
  /// （`followed_user` / `followed_pet`，服务端下发）；其余位置 null → 不带该属性。
  final String? reason;

  /// 点赞/取消以后端真值落定后的回调（bug 20260923-538）：详情页据此把点赞态写回 Feed 快照。
  /// 失败回滚不回调（UI 回到原值，快照本就没变）。
  final void Function(bool liked, int likeCount)? onSettled;

  /// 这份点赞快照（[initialLiked] / [initialCount]）发请求前读到的全局点赞版本号（V1.5.0 FR-210）：
  /// `postLikeStoreProvider` 的覆盖比它新才生效。默认 0 = 任何覆盖都比快照新。
  /// Feed 卡传 `item.likeEpoch`，详情底栏传 `detail.likeEpoch`。
  final int snapshotEpoch;

  @override
  ConsumerState<LikeButton> createState() => _LikeButtonState();
}

class _LikeButtonState extends ConsumerState<LikeButton> with SingleTickerProviderStateMixin {
  /// 爱心弹跳（V1.5.0 FR-211）：1.0 → 1.2 → 1.0 约 200ms，只缩放图标，不改热区与布局。
  /// 由 store 覆盖的 `bounceTick` 变化触发（点爱心点赞 / Feed 图片区双击点赞时 +1）。
  late final AnimationController _bounce =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 200));
  late final Animation<double> _bounceScale = TweenSequence<double>([
    TweenSequenceItem(tween: Tween(begin: 1.0, end: 1.2).chain(CurveTween(curve: Curves.easeOut)), weight: 50),
    TweenSequenceItem(tween: Tween(begin: 1.2, end: 1.0).chain(CurveTween(curve: Curves.easeIn)), weight: 50),
  ]).animate(_bounce);

  @override
  void dispose() {
    _bounce.dispose();
    super.dispose();
  }

  void _onBounceTick(int? prev, int next) {
    if (next <= (prev ?? 0)) return;
    // 减少动态效果：不弹跳，直接变红 +1（震动照常，由发起方调）。
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) return;
    _bounce.forward(from: 0);
  }

  /// 有效值 = 快照或 store 覆盖（覆盖比快照新、或飞行中才生效）。
  ///
  /// 🔴 原来 State 里存 `_liked/_count/_inFlight` 并在 `didUpdateWidget` 跟随外部值 —— V1.5.0 FR-210 起
  /// 全部交给 store：下拉刷新的新快照版本号 ≥ 覆盖 → 自然以新快照为准；飞行中的乐观值不会被盖回去。
  LikeView _view(PostLikeOverride? o) => PostLikeStore.resolve(o,
      snapLiked: widget.initialLiked, snapCount: widget.initialCount, snapEpoch: widget.snapshotEpoch);

  LikeView _current() => _view(ref.read(postLikeStoreProvider)[widget.postId]);

  Future<void> _toggle() async {
    final view = _current();
    if (view.inFlight) return;
    // 门控：未登录 → FR-0C，不发请求。
    final allowed = requireLogin(ref, context, onAllowed: () {});
    if (!allowed) return;
    // V1.5.0 FR-211：点爱心**点赞**（未赞 → 已赞）时轻震 + 弹跳；取消不弹不震；点爱心不出大红心。
    final liking = !view.liked;
    if (liking) HapticFeedback.lightImpact();
    final out = await ref.read(postLikeStoreProvider.notifier).toggle(
          widget.postId,
          current: view,
          wantLiked: liking,
          bounce: liking,
          tracking: LikeTracking(
            source: widget.source,
            trigger: 'button',
            feedTab: widget.feedTab,
            rankMode: widget.rankMode,
            reason: widget.reason,
          ),
        );
    if (!mounted) return;
    if (out.status == LikeToggleStatus.settled) {
      widget.onSettled?.call(out.liked!, out.count!);
    }
  }

  /// 点数字（V1.3.4 点赞人列表）：本地计数为 0 → 无反应（以乐观更新后的值判断）；
  /// 游客 → 登录引导（不打开抽屉）；否则弹点赞人抽屉。不触碰点赞状态。
  void _openLikers() {
    if (_current().count <= 0) return;
    if (!requireLogin(ref, context, entrySource: 'likers')) return;
    showPostLikersSheet(context, postId: widget.postId, source: widget.likersSource);
  }

  @override
  Widget build(BuildContext context) {
    final view = _view(ref.watch(postLikeStoreProvider.select((m) => m[widget.postId])));
    ref.listen<int>(postLikeStoreProvider.select((m) => m[widget.postId]?.bounceTick ?? 0), _onBounceTick);
    final color = view.liked ? AppColors.likeHeart : AppColors.textSecondary;
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
          child: ScaleTransition(
            scale: _bounceScale,
            child: Icon(
              view.liked ? Icons.favorite_rounded : Icons.favorite_border_rounded,
              size: LikeButton.heartSize,
              // 点赞红心（设计稿）：激活态用暖红 likeHeart，与 Feed 卡片点赞一致。
              color: color,
            ),
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
            child: _countHitHeight(Text('${view.count}', style: AppTypography.caption.copyWith(color: color))),
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
