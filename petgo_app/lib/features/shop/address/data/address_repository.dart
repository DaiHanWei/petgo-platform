import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/network/api_paths.dart';
import '../../../../core/network/dio_client.dart';
import '../domain/shipping_address.dart';

/// 地址簿数据层（Story 2.4，消费 2-1 的 `/me/shipping-addresses`）。
///
/// 🔒 全部端点都要求登录（与 Toko 商品浏览的游客开放策略相反）——地址是 PII。
/// 401 由 AuthInterceptor 处理，repository 不自理。
class AddressRepository {
  AddressRepository({required this.dio});

  final Dio dio;

  Future<List<ShippingAddress>> list() async {
    final resp = await dio.get<List<dynamic>>(ApiPaths.shippingAddresses);
    return (resp.data ?? const [])
        .whereType<Map<String, dynamic>>()
        .map(ShippingAddress.fromJson)
        .toList();
  }

  Future<ShippingAddress> create(ShippingAddress a) async {
    final resp = await dio.post<Map<String, dynamic>>(
        ApiPaths.shippingAddresses, data: a.toRequestJson());
    return ShippingAddress.fromJson(resp.data!);
  }

  Future<ShippingAddress> update(String token, ShippingAddress a) async {
    final resp = await dio.put<Map<String, dynamic>>(
        '${ApiPaths.shippingAddresses}/$token', data: a.toRequestJson());
    return ShippingAddress.fromJson(resp.data!);
  }

  Future<void> delete(String token) async {
    await dio.delete<void>('${ApiPaths.shippingAddresses}/$token');
  }

  Future<ShippingAddress> setDefault(String token) async {
    final resp = await dio.post<Map<String, dynamic>>(
        '${ApiPaths.shippingAddresses}/$token/default');
    return ShippingAddress.fromJson(resp.data!);
  }

  /// 🔒 区域树对游客开放，但地址簿页面本就在登录后，这里不做区分。
  Future<RegionTree> regions() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.shopRegions);
    return RegionTree.fromJson(resp.data!);
  }
}

final addressRepositoryProvider =
    Provider<AddressRepository>((ref) => AddressRepository(dio: ref.read(dioProvider)));

final addressListProvider =
    FutureProvider.autoDispose<List<ShippingAddress>>((ref) async =>
        ref.read(addressRepositoryProvider).list());

final regionTreeProvider =
    FutureProvider.autoDispose<RegionTree>((ref) async =>
        ref.read(addressRepositoryProvider).regions());
