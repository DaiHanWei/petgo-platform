import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../order/data/order_repository.dart';
import '../../order/domain/order_summary.dart';
import '../../order/presentation/order_l10n.dart';
import '../../shop/domain/shop_product.dart' show formatIdr;

/// 建单页「关联订单」选择器用的最近订单（Story 3-3）。
///
/// 🔴 **刻意不复用 `orderListProvider`**：那是订单中心页面的**共享状态**，
/// 它的 `setFilter` 会把用户在订单中心里设好的筛选条件改掉 ——
/// 用户从建单页退回订单中心，会发现自己的筛选莫名其妙变了。
/// 这里另起一个 `autoDispose` 的一次性查询，关掉选择器就丢。
///
/// 首屏 20 条，不做分页：建单时要关联的几乎必然是最近那几单，
/// 翻页会把一个「顺手选一下」的动作做成一个浏览器。
/// 🔴 **只列后端认得的两类订单**（问诊单 + 电商单）。
/// `SupportTicketService.resolveRelatedOrder` 只查 `consult_orders` 与 `shop_orders`，
/// 其余类型（AI 解锁 / PawCoin 充值 / 高清身份证）的 token 两边都查不到 ⇒ **静默存 null**。
/// 让用户选一个注定关联不上的订单，他会以为关联好了，而客服那边看到的是「关联订单 —」——
/// 比一开始就不给他选糟得多。
const _linkableTypes = {OrderType.vetConsult, OrderType.ecommerce};

final recentOrdersForTicketProvider =
    FutureProvider.autoDispose<List<OrderSummary>>((ref) async {
  // 多取一些再筛，免得最近 20 单恰好全是充值、筛完一条不剩。
  final page = await ref.read(orderRepositoryProvider).fetchOrders(limit: 50);
  return page.items
      .where((o) => _linkableTypes.contains(o.orderType))
      .take(20)
      .toList(growable: false);
});

/// 弹出订单选择器。返回选中的订单；返回 null 表示「不关联」或用户直接关掉了弹窗。
///
/// ⚠️ 调用方要能区分「选了个订单」与「选了不关联」，所以返回值用
/// [TicketOrderPick] 包一层 —— 直接返回 `OrderSummary?` 的话，
/// 「关掉弹窗」和「主动选不关联」就变成了同一个值。
Future<TicketOrderPick?> showTicketOrderPicker(BuildContext context) {
  return showModalBottomSheet<TicketOrderPick>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.surface,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
    ),
    builder: (ctx) => const _TicketOrderPickerSheet(),
  );
}

/// 选择结果。[order] 为 null 表示用户主动选了「不关联」。
class TicketOrderPick {
  const TicketOrderPick(this.order);

  final OrderSummary? order;
}

class _TicketOrderPickerSheet extends ConsumerWidget {
  const _TicketOrderPickerSheet();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(recentOrdersForTicketProvider);

    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.of(context).size.height * 0.7,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.all(AppSpacing.md),
              child: Text(l10n.ticketRelatedOrderPick,
                  style: AppTypography.title.copyWith(fontWeight: FontWeight.w700)),
            ),
            ListTile(
              key: const ValueKey('ticketOrderPickNone'),
              leading: const Icon(Icons.block, size: 20, color: AppColors.textSecondary),
              title: Text(l10n.ticketRelatedOrderNone),
              onTap: () => Navigator.of(context).pop(const TicketOrderPick(null)),
            ),
            const Divider(height: 1),
            Flexible(
              child: async.when(
                loading: () => const Padding(
                  padding: EdgeInsets.all(AppSpacing.lg),
                  child: Center(child: CircularProgressIndicator()),
                ),
                // 🔴 拉不到订单**不该挡住提工单** —— 用户是来求助的。
                //    给一句说明，他仍然可以不关联订单直接提交。
                error: (_, _) => Padding(
                  padding: const EdgeInsets.all(AppSpacing.lg),
                  child: Text(l10n.ticketRelatedOrderLoadFailed,
                      style: AppTypography.caption
                          .copyWith(color: AppColors.textSecondary)),
                ),
                data: (orders) => orders.isEmpty
                    ? Padding(
                        padding: const EdgeInsets.all(AppSpacing.lg),
                        child: Text(l10n.ticketRelatedOrderEmpty,
                            style: AppTypography.caption
                                .copyWith(color: AppColors.textSecondary)),
                      )
                    : ListView.separated(
                        shrinkWrap: true,
                        itemCount: orders.length,
                        separatorBuilder: (_, _) => const Divider(height: 1),
                        itemBuilder: (_, i) => _row(context, l10n, orders[i]),
                      ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _row(BuildContext context, AppLocalizations l10n, OrderSummary o) {
    return ListTile(
      key: ValueKey('ticketOrderPick_${o.orderToken}'),
      title: Text(o.displayNo, style: AppTypography.body),
      subtitle: Text(
        [
          orderTypeLabel(l10n, o.orderType),
          if (o.amount != null) formatIdr(o.amount!),
        ].join(' · '),
        style: AppTypography.caption.copyWith(color: AppColors.textSecondary),
      ),
      onTap: () => Navigator.of(context).pop(TicketOrderPick(o)),
    );
  }
}
