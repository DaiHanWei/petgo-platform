import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/shop_order_detail.dart';

/// 电商订单数据层（Story 3.8）。
///
/// 🔒 全部端点在 `/me` 下，要求登录；越权与不存在同为 404（后端双条件查询）。
class ShopOrderRepository {
  ShopOrderRepository({required this.dio});

  final Dio dio;

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

  Future<ShopOrderDetail> cancel(String orderToken) async {
    final resp = await dio.post<Map<String, dynamic>>(
        '${ApiPaths.meShopOrders}/$orderToken/cancel');
    return ShopOrderDetail.fromJson(resp.data!);
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
