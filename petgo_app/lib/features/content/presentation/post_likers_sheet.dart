import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../social/domain/follow_list_item.dart';
import '../../social/presentation/widgets/follow_user_row.dart';
import 'post_likers_controller.dart';

/// 半屏 / 全屏两档（spec §2.2）。
const double _kHalf = 0.5;
const double _kFull = 1.0;

/// 松手时低于这个比例 → 关闭（半屏再下拉一段即关；只拖一点点则弹回半屏）。
const double _kCloseBelow = 0.42;

/// 打开帖子点赞人抽屉（V1.3.4）。[source] = 埋点 `post_likers_opened.source`
/// （`feed_card` / `content_detail`）。
///
/// 抽屉默认盖住半屏；上滑全屏、全屏下拉回半屏、半屏下拉 / 点阴影 / 返回键关闭。
/// 列表直接用抽屉的 scrollController —— 全屏时列表滚到顶后继续下拉即回半屏（Instagram 手感）。
Future<void> showPostLikersSheet(BuildContext context,
    {required int postId, required String source}) {
  Analytics.capture('post_likers_opened', {'source': source});
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    isDismissible: true,
    useSafeArea: true,
    // 盖住底部 Tab Bar；点行进主页（顶层路由）会压在抽屉之上，返回仍回到抽屉。
    useRootNavigator: true,
    backgroundColor: Colors.transparent,
    builder: (_) => PostLikersSheet(postId: postId),
  );
}

class PostLikersSheet extends ConsumerStatefulWidget {
  const PostLikersSheet({super.key, required this.postId});

  final int postId;

  @override
  ConsumerState<PostLikersSheet> createState() => _PostLikersSheetState();
}

class _PostLikersSheetState extends ConsumerState<PostLikersSheet> {
  final _controller = DraggableScrollableController();

  /// 只关一次：自己的「松手低于阈值」与「拖到底」两条路径可能同时满足，
  /// 多 pop 一次会把底下的页面也关掉。
  bool _closing = false;

  /// 路由的 isCurrent 变化会触发本方法（ModalRoute.of 建立了依赖）：
  /// 被别的页面盖住期间已收到 404，等它退出、本抽屉重新成为栈顶后补关，
  /// 否则返回后会留下一个空白抽屉。
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (ModalRoute.of(context)?.isCurrent != true || _closing) return;
    if (ref.read(postLikersProvider(widget.postId)).value?.gone == true) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _close());
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _close() {
    if (_closing || !mounted) return;
    final route = ModalRoute.of(context);
    if (route != null && !route.isCurrent) return; // 见 didChangeDependencies
    _closing = true;
    Navigator.of(context).pop();
  }

  void _onPointerUp(PointerUpEvent _) {
    if (_controller.isAttached && _controller.size < _kCloseBelow) _close();
  }

  bool _onExtent(DraggableScrollableNotification n) {
    if (n.extent <= n.minExtent + 0.001) _close();
    return false;
  }

  bool _onScroll(ScrollNotification n) {
    // 翻页失败后只能点「重试」按钮，滚动不再自动重试（否则断网时每次滚动都在发请求）。
    final failed = ref.read(postLikersProvider(widget.postId)).value?.loadMoreFailed ?? false;
    if (n.metrics.axis == Axis.vertical && n.metrics.extentAfter < 300 && !failed) {
      ref.read(postLikersProvider(widget.postId).notifier).loadMore();
    }
    return false;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final provider = postLikersProvider(widget.postId);
    // 帖子已删 / 对我不可见（首页或翻页时 404）→ 提示「内容已不存在」并关抽屉。
    ref.listen(provider, (_, next) {
      if (next.value?.gone == true && !_closing) {
        showAppToast(context, l10n.detailGoneTitle);
        _close();
      }
    });
    final async = ref.watch(provider);

    return Listener(
      onPointerUp: _onPointerUp,
      child: NotificationListener<DraggableScrollableNotification>(
        onNotification: _onExtent,
        child: DraggableScrollableSheet(
          controller: _controller,
          expand: false,
          initialChildSize: _kHalf,
          minChildSize: 0,
          maxChildSize: _kFull,
          snap: true,
          snapSizes: const [_kHalf],
          // 关闭由本组件统一收口（见 [_close]），不让 BottomSheet 再 pop 一次。
          shouldCloseOnMinExtent: false,
          builder: (context, scrollController) => Container(
            key: const ValueKey('postLikersSheet'),
            decoration: const BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
            ),
            clipBehavior: Clip.antiAlias,
            child: NotificationListener<ScrollNotification>(
              onNotification: _onScroll,
              child: CustomScrollView(
                controller: scrollController,
                slivers: [
                  PinnedHeaderSliver(child: _header(l10n)),
                  ..._body(l10n, async),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _header(AppLocalizations l10n) => ColoredBox(
        color: AppColors.surface,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(height: 8),
            Container(
              key: const ValueKey('postLikersHandle'),
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: AppColors.line,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 12),
              child: Text(
                l10n.postLikersTitle,
                key: const ValueKey('postLikersTitle'),
                style: AppTypography.body.copyWith(fontWeight: FontWeight.w700),
              ),
            ),
            const Divider(height: 1, thickness: 1, color: AppColors.line2),
          ],
        ),
      );

  List<Widget> _body(AppLocalizations l10n, AsyncValue<LikersState> async) {
    final s = async.value;
    if (async.hasError && s == null) return [_fill(_error(l10n))];
    if (s == null) {
      return [
        _fill(const Center(
            child: SizedBox(width: 24, height: 24, child: CircularProgressIndicator(strokeWidth: 2)))),
      ];
    }
    // gone：监听里已在关；关闭动画期间留白，不闪空状态。
    if (s.gone) return [_fill(const SizedBox.shrink())];
    if (s.items.isEmpty && !s.hasMore) {
      return [
        _fill(Center(
          child: Text(
            l10n.postLikersEmpty,
            key: const ValueKey('postLikersEmpty'),
            textAlign: TextAlign.center,
            style: AppTypography.body.copyWith(color: AppColors.textSecondary),
          ),
        )),
      ];
    }
    final footer = s.hasMore || s.loadMoreFailed;
    return [
      SliverPadding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
        sliver: SliverList.separated(
          itemCount: s.items.length + (footer ? 1 : 0),
          separatorBuilder: (_, _) => const Divider(height: 1, thickness: 1, color: AppColors.line2),
          itemBuilder: (context, i) => i < s.items.length ? _row(s.items[i]) : _footer(l10n, s),
        ),
      ),
      SliverToBoxAdapter(child: SizedBox(height: MediaQuery.paddingOf(context).bottom + 12)),
    ];
  }

  Widget _row(FollowListItem item) => FollowUserRow(
        item: item,
        rowKey: ValueKey('postLikersRow_${item.userId}'),
        surfaceKey: 'likers:${widget.postId}:${item.userId}',
        // 非常驻位置（同推荐行）：点关注后本按钮保留「Mengikuti」可立即取关。
        persistent: false,
        source: 'likers',
      );

  Widget _footer(AppLocalizations l10n, LikersState s) => Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
        child: Center(
          child: s.loadMoreFailed
              ? TextButton(
                  key: const ValueKey('postLikersLoadMoreRetry'),
                  onPressed: () => ref.read(postLikersProvider(widget.postId).notifier).loadMore(),
                  child: Text(l10n.feedRetry),
                )
              : const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)),
        ),
      );

  Widget _error(AppLocalizations l10n) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(l10n.postLikersLoadFailed, textAlign: TextAlign.center, style: AppTypography.title),
            const SizedBox(height: AppSpacing.lg),
            OutlinedButton(
              key: const ValueKey('postLikersRetry'),
              onPressed: () => ref.read(postLikersProvider(widget.postId).notifier).retry(),
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.mint,
                side: const BorderSide(color: AppColors.mint, width: 1.5),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              child: Text(l10n.feedRetry),
            ),
          ],
        ),
      );

  static Widget _fill(Widget child) => SliverFillRemaining(hasScrollBody: false, child: child);
}
