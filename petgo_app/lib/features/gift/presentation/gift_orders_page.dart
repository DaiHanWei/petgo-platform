import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/pay_channel_picker.dart' show formatIdrAmount;
import '../../profile/presentation/visitor_archive_view.dart';
import '../domain/gift_order_history.dart';
import '../domain/gift_send_result.dart';
import 'gift_orders_controller.dart';
import 'gift_style.dart';

/// 我的送礼记录（V1.4.0 gift Story 2.3 · AC4 · UI 稿 F2 ~ F4）。入口只在 PawCoin 钱包页（decision-log ③ S7）；
/// 退回通知 `GIFT_UNDELIVERED_REFUND` 直达（Q5）。
///
/// - 只列本人**送出**的，按接口顺序（服务端时间倒序），客户端不重排；
/// - 「Menunggu pembayaran」只显示状态，**不能续付**（Q4），行内无任何按钮；
/// - 宠物已删（F2 灰行）：整行与头像不可点；其余点头像 / 宠物名进站内访客 Diary Tab（不带 `?from=`）。
class GiftOrdersPage extends ConsumerStatefulWidget {
  const GiftOrdersPage({super.key});

  /// 路由：落在 `/me` 受控前缀下（`app_router.dart` `_controlledLocations`），游客自动拦截。
  static const String routePath = '/me/gift-orders';

  @override
  ConsumerState<GiftOrdersPage> createState() => _GiftOrdersPageState();
}

class _GiftOrdersPageState extends ConsumerState<GiftOrdersPage> {
  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(giftOrdersProvider);
    final data = async.value;
    final Widget body;
    if (data != null) {
      body = RefreshIndicator(
        onRefresh: () => ref.read(giftOrdersProvider.notifier).refresh(),
        child: data.items.isEmpty ? _empty(l10n) : _list(data),
      );
    } else if (async.hasError) {
      body = _error(l10n);
    } else {
      body = const Center(
          key: ValueKey('giftOrdersLoading'), child: CircularProgressIndicator(color: AppColors.mint));
    }
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.giftOrdersTitle)),
      body: body,
    );
  }

  /// F3 空态（放进可滚动容器，下拉刷新仍可用）。
  Widget _empty(AppLocalizations l10n) => LayoutBuilder(
        builder: (context, c) => ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: [
            ConstrainedBox(
              constraints: BoxConstraints(minHeight: c.maxHeight),
              child: Center(
                child: EmptyState(
                  key: const ValueKey('giftOrdersEmpty'),
                  icon: Icons.redeem_rounded,
                  iconBackground: AppColors.cream2,
                  title: l10n.giftOrdersEmptyTitle,
                  message: l10n.giftOrdersEmptyHint,
                ),
              ),
            ),
          ],
        ),
      );

  /// F4 首屏加载失败。
  Widget _error(AppLocalizations l10n) => Center(
        key: const ValueKey('giftOrdersError'),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.cloud_off, size: 48, color: AppColors.muted),
              const SizedBox(height: AppSpacing.md),
              Text(l10n.detailNetworkError,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 14, color: AppColors.ink2)),
              const SizedBox(height: AppSpacing.md),
              OutlinedButton(
                key: const ValueKey('giftOrdersRetry'),
                onPressed: () => ref.read(giftOrdersProvider.notifier).refresh(),
                child: Text(l10n.commonRetry),
              ),
            ],
          ),
        ),
      );

  Widget _list(GiftOrdersState data) => ListView(
        key: const ValueKey('giftOrdersList'),
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(AppSpacing.screenEdge),
        children: [
          for (final o in data.items) _OrderRow(order: o),
          _LoadMoreFooter(state: data),
        ],
      );
}

/// F2 一行：宠物头像 40 + 名字 / 礼物图标 + 名 × 数量 / 时间；右侧金额 + 渠道 + 状态胶囊。
class _OrderRow extends StatelessWidget {
  const _OrderRow({required this.order});

  final GiftOrderRecord order;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final lang = Localizations.localeOf(context).languageCode;
    final pet = order.pet;
    final void Function()? openPet =
        pet == null ? null : () => context.push('${VisitorArchiveView.inAppRouteBase}/${pet.petId}');
    final avatar = pet == null ? null : AppImage.provider(pet.avatarUrl, thumbWidth: 120);
    final pill = _statusPill(l10n, order.status);
    return Container(
      key: ValueKey('giftOrderRow-${order.orderToken}'),
      margin: const EdgeInsets.only(bottom: AppSpacing.sm),
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(color: AppColors.card, borderRadius: BorderRadius.circular(14)),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          GestureDetector(
            key: ValueKey('giftOrderAvatar-${order.orderToken}'),
            onTap: openPet,
            child: CircleAvatar(
              radius: 20,
              backgroundColor: pet == null ? AppColors.cream2 : GiftStyle.avatarBg,
              backgroundImage: avatar,
              child: avatar == null ? const Icon(Icons.pets, size: 20, color: AppColors.muted) : null,
            ),
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (pet == null)
                  Text(l10n.giftOrdersPetGone,
                      key: ValueKey('giftOrderPetGone-${order.orderToken}'),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 14, color: AppColors.muted))
                else
                  GestureDetector(
                    key: ValueKey('giftOrderPetName-${order.orderToken}'),
                    onTap: openPet,
                    child: Text(pet.name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.ink)),
                  ),
                const SizedBox(height: 4),
                Row(
                  children: [
                    giftIcon(order.iconUrl, size: 18),
                    const SizedBox(width: 4),
                    Flexible(
                      child: Text('${order.giftName(lang)} × ${order.quantity}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
                    ),
                  ],
                ),
                if (order.createdAt != null) ...[
                  const SizedBox(height: 4),
                  Text(formatDayMonthYearTime(context, order.createdAt!.toLocal()),
                      style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                ],
              ],
            ),
          ),
          const SizedBox(width: AppSpacing.sm),
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text('Rp${formatIdrAmount(order.total)}',
                  style: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w800,
                      color: AppColors.ink,
                      fontFeatures: [FontFeature.tabularFigures()])),
              const SizedBox(height: 2),
              Text(_channelLabel(order.channel),
                  style: const TextStyle(fontSize: 12, color: AppColors.muted)),
              if (pill != null) ...[const SizedBox(height: 4), pill],
            ],
          ),
        ],
      ),
    );
  }

  /// 渠道品牌名原样（两语一致，不进 arb）；未知代码不渲染。
  static String _channelLabel(String channel) => switch (channel) {
        'PAWCOIN' => 'PawCoin',
        'QRIS' => 'QRIS',
        _ => '',
      };

  /// 状态胶囊（AC4.3）；未知代码 / 不列入的状态 → 不显示（不渲染原始代码）。
  Widget? _statusPill(AppLocalizations l10n, GiftOrderStatus status) {
    final (String text, Color fg, Color bg)? spec = switch (status) {
      GiftOrderStatus.delivered => (l10n.giftOrdersStatusDelivered, AppColors.triageGreen, AppColors.momenBadgeBg),
      GiftOrderStatus.pending => (l10n.orderStatusPending, AppColors.gold, AppColors.goldTint),
      GiftOrderStatus.undeliveredRefunded => (l10n.giftOrdersStatusRefunded, AppColors.ink2, AppColors.cream2),
      // 拍板点 ①：钱已付、还没退回的两种中间态 → 复用「Pengembalian diproses」。
      GiftOrderStatus.undeliveredRefundPending ||
      GiftOrderStatus.refundManual =>
        (l10n.orderStatusRefunding, AppColors.ink2, AppColors.cream2),
      _ => null,
    };
    if (spec == null) return null;
    return Container(
      key: ValueKey('giftOrderStatus-${order.orderToken}'),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(color: spec.$3, borderRadius: BorderRadius.circular(999)),
      child: Text(spec.$1, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: spec.$2)),
    );
  }
}

/// 加载更多（照钱包页 `_LoadMoreFooter`）：加载中转圈 / 失败行内重试 / 还有更多显示按钮。
class _LoadMoreFooter extends ConsumerWidget {
  const _LoadMoreFooter({required this.state});

  final GiftOrdersState state;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    if (state.loadingMore) {
      return const Padding(
        padding: EdgeInsets.all(AppSpacing.lg),
        child: Center(child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))),
      );
    }
    if (state.loadMoreFailed) {
      return Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Center(
          child: TextButton(
            key: const ValueKey('giftOrdersLoadMoreRetry'),
            onPressed: () => ref.read(giftOrdersProvider.notifier).loadMore(),
            child: Text('${l10n.giftLoadMoreFailed} · ${l10n.commonRetry}'),
          ),
        ),
      );
    }
    if (state.hasMore) {
      return Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Center(
          child: TextButton(
            key: const ValueKey('giftOrdersLoadMore'),
            onPressed: () => ref.read(giftOrdersProvider.notifier).loadMore(),
            child: Text(l10n.orderLoadMore),
          ),
        ),
      );
    }
    return const SizedBox.shrink();
  }
}
