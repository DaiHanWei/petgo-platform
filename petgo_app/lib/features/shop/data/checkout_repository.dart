import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/network/problem_detail.dart';
import '../domain/checkout_preview.dart';

/// 结算数据层（Story 3.7，消费 3-4 的 CheckoutService）。
///
/// 🔒 两个端点都在 `/me` 下，全部要求登录。
class CheckoutRepository {
  CheckoutRepository({required this.dio});

  final Dio dio;

  Future<CheckoutPreview> preview(String addressToken) async {
    final resp = await dio.get<Map<String, dynamic>>(
      ApiPaths.meCheckout,
      queryParameters: {'addressToken': addressToken},
    );
    return CheckoutPreview.fromJson(resp.data!);
  }

  /// 下单。
  ///
  /// 🔴 [entrySource] / [triggerType] 是复购看板的服务端权威归因（AB-13B）。
  /// **当前一律传 null**：购物车行上没有记录「这件商品当初从哪个入口加进来的」（V108 无该列），
  /// 前端此刻能填的只有「从购物车结算」——那不是归因，是废话。
  /// **编一个值比留空更糟**：看板会把它当真，而错误的归因数据没人能事后识别。
  /// 👉 归因链闭合归 Story 9.2 / Epic 6，届时从加购处一路带下来。
  ///
  /// 抛 [CheckoutFailure]：库存/下架挡住时带逐行明细。
  Future<ShopOrderRef> placeOrder(String addressToken,
      {String? entrySource, String? triggerType}) async {
    try {
      final resp = await dio.post<Map<String, dynamic>>(
        ApiPaths.meShopOrders,
        data: {
          'addressToken': addressToken,
          'entrySource': ?entrySource,
          'triggerType': ?triggerType,
        },
      );
      return ShopOrderRef.fromJson(resp.data!);
    } on DioException catch (e) {
      throw CheckoutFailure.from(e);
    }
  }
}

/// 下单失败原因。
///
/// 🔴 **不把后端 detail 原文丢给用户**（它是中文运营语）；[unavailableLines] 例外 ——
/// 那是结构化数据，前端自己组织文案。
class CheckoutFailure implements Exception {
  const CheckoutFailure({required this.kind, this.unavailableLines = const []});

  final CheckoutFailureKind kind;

  /// 🔴 有值时页面必须**逐行列出**并允许移除后继续（FR-95：不整单打回）。
  final List<UnavailableLine> unavailableLines;

  static CheckoutFailure from(DioException e) {
    final pd = ProblemDetail.fromDioException(e);
    final body = e.response?.data;
    if (pd?.status == 409 && body is Map) {
      final raw = body['unavailableLines'];
      final lines = raw is List
          ? raw
              .whereType<Map<String, dynamic>>()
              .map(UnavailableLine.fromJson)
              .toList(growable: false)
          : const <UnavailableLine>[];
      if (lines.isNotEmpty) {
        return CheckoutFailure(
            kind: CheckoutFailureKind.unavailableLines, unavailableLines: lines);
      }
    }
    // 422 覆盖「地址超范围」「购物车为空」「未选地址」三种 —— 都属于「这一单现在下不了」，
    // 而页面在这些情况下本就已禁用提交，走到这里说明状态已过期，统一提示刷新即可。
    if (pd?.status == 422) return const CheckoutFailure(kind: CheckoutFailureKind.notPlaceable);
    return const CheckoutFailure(kind: CheckoutFailureKind.generic);
  }
}

enum CheckoutFailureKind { unavailableLines, notPlaceable, generic }

final Provider<CheckoutRepository> checkoutRepositoryProvider =
    Provider<CheckoutRepository>((ref) => CheckoutRepository(dio: ref.read(dioProvider)));

/// 结算试算（按地址 token）。地址一换就重算 —— 运费与拆分都依赖它。
final checkoutPreviewProvider = FutureProvider.autoDispose
    .family<CheckoutPreview, String>((ref, addressToken) async {
  return ref.read(checkoutRepositoryProvider).preview(addressToken);
});
