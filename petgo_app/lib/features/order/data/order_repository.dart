import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/order_detail.dart';
import '../domain/order_summary.dart';

/// 订单中心数据层（Story 5.2，消费 5-1 `GET /orders`）。游标分页 + 类型筛选。
/// 401 由 AuthInterceptor 处理，repository 不自理；错误以 [DioException] 抛给控制器。
class OrderRepository {
  OrderRepository({required this.dio});

  final Dio dio;

  /// 拉订单页。[type] 为空聚合 3 类；[cursor] 为末条 createdAt epochMillis（首页 null）。
  Future<OrderPage> fetchOrders({OrderType? type, String? cursor, int limit = 20}) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.orders,
      queryParameters: {
        'type': ?type?.toApi(),
        'cursor': ?cursor,
        'limit': limit,
      },
    );
    return OrderPage.fromJson(resp.data!);
  }

  /// 订单详情（Story 5.3，`GET /orders/{token}`）。非 owner/不存在→后端 404（DioException 抛给页面）。
  Future<OrderDetail> fetchDetail(String orderToken) async {
    final resp = await dio.get<Map<String, dynamic>>('${ApiPaths.orders}/$orderToken');
    return OrderDetail.fromJson(resp.data!);
  }
}

final orderRepositoryProvider =
    Provider<OrderRepository>((ref) => OrderRepository(dio: ref.read(dioProvider)));

/// 订单详情（按 token）。
final orderDetailProvider = FutureProvider.autoDispose.family<OrderDetail, String>(
    (ref, token) => ref.read(orderRepositoryProvider).fetchDetail(token));
