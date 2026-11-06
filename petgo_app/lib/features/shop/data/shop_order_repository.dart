import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/network/problem_detail.dart';
import '../domain/shop_order_detail.dart';
import '../domain/shop_order_summary.dart';
import '../domain/shop_return.dart';

/// 电商订单数据层（Story 3.8）。
///
/// 🔒 全部端点在 `/me` 下，要求登录；越权与不存在同为 404（后端双条件查询）。
class ShopOrderRepository {
  ShopOrderRepository({required this.dio});

  final Dio dio;

  /// 电商订单列表（v1.4.2 shop Story 6.2 / App 9.1）：按 `displayStatus` 分组（S-7），游标分页。
  Future<ShopOrderListPage> list({ShopOrderGroup group = ShopOrderGroup.all, String? cursor}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.meShopOrders,
      queryParameters: {'group': group.api, 'cursor': ?cursor},
    );
    return ShopOrderListPage.fromJson(resp.data ?? const {});
  }

  /// 角标计数 `{pendingPayment, toReceive}`（D-18）。
  Future<ShopOrderCounts> counts() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.meShopOrderCounts);
    return ShopOrderCounts.fromJson(resp.data ?? const {});
  }

  Future<ShopOrderDetail> detail(String orderToken) async {
    final resp =
        await dio.get<Map<String, dynamic>>('${ApiPaths.meShopOrders}/$orderToken');
    return ShopOrderDetail.fromJson(resp.data!);
  }

  /// 发起支付。
  ///
  /// 🔴 **渠道不是入参**（FR-100 仅 QRIS；PawCoin 段由下单时固化的拆分决定）——
  /// 留一个恒等于 QRIS 的参数只会让人以为还能选别的。
  Future<ShopPayResult> pay(String orderToken) async {
    final resp = await dio.post<Map<String, dynamic>>(
        '${ApiPaths.meShopOrders}/$orderToken/pay');
    return ShopPayResult.fromJson(resp.data!);
  }

  /// 「我已付款」主动查单（v1.4.2 shop Story 7.2 · E3/E4；后端 3.3 `payment-check`）。
  ///
  /// 🔴 到账判定以服务端为准（P-3）：`paid` 由后端按订单真实状态算出，App 不自行推断。
  Future<PaymentCheckResult> paymentCheck(String orderToken) async {
    final resp = await dio.post<Map<String, dynamic>>(ApiPaths.meShopOrderPaymentCheck(orderToken));
    return PaymentCheckResult.fromJson(resp.data!);
  }

  Future<ShopOrderDetail> cancel(String orderToken) async {
    final resp = await dio.post<Map<String, dynamic>>(
        '${ApiPaths.meShopOrders}/$orderToken/cancel');
    return ShopOrderDetail.fromJson(resp.data!);
  }

  /// 确认收货（Story 4.5，SPEC-2 出口②）。
  ///
  /// 🔴 **已发货态就能调**，不必等系统标记送达 —— 服务端会顺手写下签收时刻，
  /// Epic 5 的退货窗口正是从那一刻起算。幂等：重复调用返回同一个已完成订单。
  Future<ShopOrderDetail> confirmReceipt(String orderToken) async {
    final resp = await dio.post<Map<String, dynamic>>(
        '${ApiPaths.meShopOrders}/$orderToken/confirm-receipt');
    return ShopOrderDetail.fromJson(resp.data!);
  }

  /// 取消退款单选择现金段去向（v1.4.2 shop Story 9.2 · 后端 4.1）。
  ///
  /// 🔴 **不传任何金额 / 手续费**：渠道费与补偿由服务端算（P-1）。`TO_PAWCOIN` 不带渠道与账号。
  /// 🔒 账号 / 户名是 PII：只进请求体，不进日志 / 埋点 / 本地缓存。调用方收到后应 invalidate 详情重取。
  Future<ShopOrderRefundSummary> chooseRefundDestination(
    String orderToken,
    String refundToken, {
    required CashDestination destination,
    PayoutChannel? channel,
    String? account,
    String? accountHolderName,
  }) async {
    final toBank = destination == CashDestination.toBank;
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.meShopOrderRefundDestination(orderToken, refundToken),
      data: {
        'destination': destination.api,
        if (toBank) 'channel': channel?.api,
        if (toBank) 'account': account?.trim(),
        if (toBank) 'accountHolderName': accountHolderName?.trim(),
      },
    );
    return ShopOrderRefundSummary.fromJson(resp.data ?? const {});
  }
}

final Provider<ShopOrderRepository> shopOrderRepositoryProvider =
    Provider<ShopOrderRepository>((ref) => ShopOrderRepository(dio: ref.read(dioProvider)));

/// 订单详情（按 token）。
///
/// 🔴 **倒计时归零时要 invalidate 它重拉**，而不是在客户端把状态改成「已取消」：
/// 是否真的超时由服务端说了算（它读到过窗订单会就地取消并释放库存）。
final shopOrderDetailProvider = FutureProvider.autoDispose
    .family<ShopOrderDetail, String>((ref, token) async {
  return ref.read(shopOrderRepositoryProvider).detail(token);
});

/// 「购物」二级标签角标（v1.4.2 shop Story 9.1 AC2.2）。🔴 失败 → null（不显示角标、不报错）。
/// 已登记 `resetUserScopedCaches`（换账号不沿用上个账号的计数）。
final shopOrderCountsProvider = FutureProvider.autoDispose<ShopOrderCounts?>((ref) async {
  try {
    return await ref.read(shopOrderRepositoryProvider).counts();
  } catch (_) {
    return null;
  }
});

/// V1.4.2 shop-admin 6.2（O-16）：确认收货 409 `return-inbound-confirmed` —— 退回商品已开始入库，App 提示联系客服。
bool isReturnInboundConfirmedError(Object e) {
  if (e is! DioException) return false;
  final pd = ProblemDetail.fromDioException(e);
  return pd?.typeSlug == 'return-inbound-confirmed';
}
