import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../auth/domain/auth_guard.dart';
import '../../content/domain/detail_bottom_bar.dart';
import '../../content/presentation/share_card/open_share_card.dart';
import '../data/collect_providers.dart';
import '../data/collect_repository.dart';
import '../domain/collect_target.dart';

/// 收藏按钮（V1.4.0 ④ Story 2.3 · AD-15 · UI 稿 C1–C5 / D1–D4）。
///
/// 状态机照 `LikeButton`（乐观更新、`_inFlight` 防连点、`requireLogin` 门控、跟随外部数据），
/// 补上 `LikeButton` 没有的两样：按下 0.96 缩放、失败回弹 + Toast。
/// - 收藏成功：Toast「Disimpan · Lihat」，点 Lihat → `/me?saved=post|place`；
/// - 取消成功：不提示；
/// - 失败：图标弹回 + 收藏 / 取消各自的失败 Toast；不 bump 信号、不报埋点。
/// 成功后 bump [collectionsRevisionProvider]（Story 2.4 的列表据此重拉）。
class CollectButton extends ConsumerStatefulWidget {
  /// 帖子详情底栏（点赞 · 分享卡 · 收藏）。[ownerCount] 非 null（作者本人看）时图标后显示数字 + 锁。
  const CollectButton.post({
    super.key,
    required int this.postId,
    required String this.wireType,
    required this.initialCollected,
    this.ownerCount,
  })  : target = CollectTarget.post,
        placeToken = null;

  /// 场所详情顶栏（「⋯」左侧，与它同尺寸）。
  const CollectButton.place({
    super.key,
    required String this.placeToken,
    required this.initialCollected,
  })  : target = CollectTarget.place,
        postId = null,
        wireType = null,
        ownerCount = null;

  final CollectTarget target;
  final int? postId;

  /// 帖子线格式类型（埋点 `content_type` 映射用）。
  final String? wireType;
  final String? placeToken;
  final bool initialCollected;

  /// 作者视角收藏数（不计作者自己）；不随作者自己的收藏 / 取消变化。
  final int? ownerCount;

  @override
  ConsumerState<CollectButton> createState() => _CollectButtonState();
}

class _CollectButtonState extends ConsumerState<CollectButton> {
  late bool _collected = widget.initialCollected;
  bool _inFlight = false;
  bool _pressed = false;

  /// 跟随外部数据（同 `LikeButton`）：换了目标无条件跟随；同一目标只在不在飞行中时跟随，
  /// 否则会把刚点出来的乐观值盖回去。
  @override
  void didUpdateWidget(covariant CollectButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    final switched = oldWidget.postId != widget.postId || oldWidget.placeToken != widget.placeToken;
    if (switched) {
      _collected = widget.initialCollected;
      _inFlight = false;
      return;
    }
    if (!_inFlight && oldWidget.initialCollected != widget.initialCollected) {
      _collected = widget.initialCollected;
    }
  }

  Future<void> _toggle() async {
    if (_inFlight) return;
    final allowed = requireLogin(ref, context, onAllowed: () {});
    if (!allowed) return;

    final l10n = AppLocalizations.of(context);
    // Toast 挂 root Overlay，「Lihat」可能在详情页已关之后才被点 —— router 先取出来。
    final router = GoRouter.maybeOf(context);
    final prev = _collected;
    final collecting = !prev;
    setState(() {
      _collected = collecting;
      _inFlight = true;
    });
    try {
      final repo = ref.read(collectRepositoryProvider);
      final CollectResult result = switch (widget.target) {
        CollectTarget.post =>
          collecting ? await repo.collectPost(widget.postId!) : await repo.uncollectPost(widget.postId!),
        CollectTarget.place =>
          collecting ? await repo.collectPlace(widget.placeToken!) : await repo.uncollectPlace(widget.placeToken!),
      };
      if (!mounted) return;
      setState(() {
        _collected = result.collected;
        _inFlight = false;
      });
      ref.read(collectionsRevisionProvider(widget.target).notifier).bump();
      // 只在服务端确认的状态与本次意图一致时报（P-8「服务端确认成功后」）。
      if (result.collected == collecting) _reportSuccess(collecting);
      if (collecting && result.collected) {
        showAppToast(context, l10n.collectSavedToast,
            actionLabel: l10n.collectSavedToastAction,
            onAction: () => router?.go(CollectTarget.savedLocation(widget.target)));
      }
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _collected = prev;
        _inFlight = false;
      });
      showAppToast(context, collecting ? l10n.collectSaveFailed : l10n.collectRemoveFailed);
    }
  }

  /// 埋点只在后端确认成功后报（P-8 保留 PRD 原名；守卫按字面量提取事件名，四个名字逐个写死）。
  void _reportSuccess(bool collecting) {
    switch (widget.target) {
      case CollectTarget.post:
        final props = <String, Object>{
          'post_id': widget.postId!,
          'content_type': ShareCardEntry.analyticsContentType(widget.wireType ?? ''),
          'source': 'detail',
        };
        if (collecting) {
          Analytics.capture('content_collected', props);
        } else {
          Analytics.capture('content_uncollected', props);
        }
      case CollectTarget.place:
        // place_id 取场所 token（不外露自增 id，AD-17）。
        final props = <String, Object>{'place_id': widget.placeToken!};
        if (collecting) {
          Analytics.capture('place_collected', props);
        } else {
          Analytics.capture('place_uncollected', props);
        }
    }
  }

  Widget _icon({double? size, required Color idleColor}) => AnimatedScale(
        scale: _pressed ? 0.96 : 1.0,
        duration: const Duration(milliseconds: 100),
        child: Icon(
          _collected ? Icons.bookmark_rounded : Icons.bookmark_border_rounded,
          size: size,
          color: _collected ? AppColors.mint : idleColor,
        ),
      );

  /// 按下态用 Listener，不与 onTap 抢手势。
  Widget _pressable(Widget child) => Listener(
        onPointerDown: (_) => setState(() => _pressed = true),
        onPointerUp: (_) => setState(() => _pressed = false),
        onPointerCancel: (_) => setState(() => _pressed = false),
        child: child,
      );

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    if (widget.target == CollectTarget.place) {
      return _pressable(IconButton(
        key: const ValueKey('placeDetailCollect'),
        tooltip: l10n.collectActionLabel,
        onPressed: _toggle,
        icon: _icon(idleColor: AppColors.ink),
      ));
    }
    final count = widget.ownerCount;
    return Semantics(
      button: true,
      toggled: _collected, // 读屏要能听出「已收藏」
      label: l10n.collectActionLabel,
      child: _pressable(GestureDetector(
        key: const ValueKey('detailCollectButton'),
        behavior: HitTestBehavior.opaque,
        onTap: _toggle,
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            _icon(size: DetailBarMetrics.iconSize, idleColor: AppColors.textSecondary),
            // 作者本人：数字（不计自己）+ 锁；0 也照常显示，免得作者以为功能时有时无。
            if (count != null) ...[
              const SizedBox(width: 3),
              Text('$count',
                  key: const ValueKey('detailCollectCount'),
                  style: const TextStyle(fontSize: 12, color: AppColors.ink2)),
              const SizedBox(width: 3),
              const Icon(Icons.lock_outline_rounded,
                  key: ValueKey('detailCollectLock'), size: 11, color: AppColors.muted),
            ],
          ],
        ),
      )),
    );
  }
}
