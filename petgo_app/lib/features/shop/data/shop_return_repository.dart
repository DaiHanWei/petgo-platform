import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../domain/shop_return.dart';

/// 退货数据层（Story 5.7 / 5.8 / 5.9）。
///
/// 🔒 全部端点在 `/me` 下，要求登录；越权与不存在同为 404（后端双条件查询）。
///
/// 🔴 **没有 coinDestination 相关方法** —— PawCoin 段没有第二个去向
/// （FR-100A 规则 1）。这不是遗漏：接口里没有它，前端也就无从渲染那个选项。
class ShopReturnRepository {
  ShopReturnRepository({required this.dio});

  final Dio dio;

  Future<ReturnEligibility> eligibility(String orderToken) async {
    final resp = await dio.get<Map<String, dynamic>>(
        ApiPaths.meReturnEligibility(orderToken));
    return ReturnEligibility.fromJson(resp.data!);
  }

  /// 提交退货申请。
  ///
  /// 🔴 [selections] 是**订单行 id → 数量**：行级部分退货（FR-104A）。
  /// 同订单已有进行中申请时后端回 409（C-12，库级部分唯一索引强制）。
  Future<ReturnProgress> submit({
    required String orderToken,
    required ReturnType returnType,
    required Map<int, int> selections,
    String? reasonNote,
    List<String>? evidenceKeys,
  }) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.meShopReturns,
      data: {
        'orderToken': orderToken,
        'returnType': returnType.api,
        'selections': selections.map((k, v) => MapEntry(k.toString(), v)),
        'reasonNote': reasonNote,
        'evidenceKeys': evidenceKeys,
      },
    );
    return ReturnProgress.fromJson(resp.data!);
  }

  Future<ReturnProgress> progress(String returnToken) async {
    final resp =
        await dio.get<Map<String, dynamic>>('${ApiPaths.meShopReturns}/$returnToken');
    return ReturnProgress.fromJson(resp.data!);
  }

  /// 选择**现金段**去向。
  ///
  /// 🔴 渠道费不作为入参 —— 净额由后端按渠道权威计算（FR-NFR-5）。
  Future<ReturnProgress> chooseCashDestination({
    required String returnToken,
    required CashDestination destination,
    PayoutChannel? channel,
    String? account,
    String? accountHolderName,
  }) async {
    final resp = await dio.post<Map<String, dynamic>>(
      '${ApiPaths.meShopReturns}/$returnToken/cash-destination',
      data: {
        'cashDestination': destination.api,
        'payoutChannel': channel?.api,
        'payoutAccount': account,
        'accountHolderName': accountHolderName,
      },
    );
    return ReturnProgress.fromJson(resp.data!);
  }

  /// S-7：上传寄回运单（平台承担运费的情形据此返还）。
  Future<ReturnProgress> registerShipback({
    required String returnToken,
    required String carrier,
    required String trackingNo,
    int? fee,
  }) async {
    final resp = await dio.post<Map<String, dynamic>>(
      '${ApiPaths.meShopReturns}/$returnToken/shipback',
      data: {'carrier': carrier, 'trackingNo': trackingNo, 'fee': fee},
    );
    return ReturnProgress.fromJson(resp.data!);
  }

  /// S-8 ④：撤销申请（待审核 / 待寄回两态）。
  Future<ReturnProgress> withdraw(String returnToken) async {
    final resp = await dio.post<Map<String, dynamic>>(
        '${ApiPaths.meShopReturns}/$returnToken/withdraw');
    return ReturnProgress.fromJson(resp.data!);
  }
}

final Provider<ShopReturnRepository> shopReturnRepositoryProvider =
    Provider<ShopReturnRepository>(
        (ref) => ShopReturnRepository(dio: ref.read(dioProvider)));

/// 退货申请页数据（按订单 token）。
final returnEligibilityProvider = FutureProvider.autoDispose
    .family<ReturnEligibility, String>((ref, orderToken) async {
  return ref.read(shopReturnRepositoryProvider).eligibility(orderToken);
});

/// 退货进度 / 退款方式页数据（按退货 token）。
final returnProgressProvider = FutureProvider.autoDispose
    .family<ReturnProgress, String>((ref, returnToken) async {
  return ref.read(shopReturnRepositoryProvider).progress(returnToken);
});
