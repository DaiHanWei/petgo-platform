import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/network/problem_detail.dart';
import '../domain/checkout_preview.dart';

/// 结算数据层（v1.4.2 shop Story 7.1 重写：消费 3.1 试算 / 3.2 新结算模式下单）。
///
/// 🔒 两个端点都在 `/me` 下，全部要求登录。
///
/// 旧 `GET /me/checkout` 的 App 调用已删除（后端旧端点保留给旧版 App，S-4）。
/// 试算不再走 family provider：参数多、需要丢弃过期响应，由页面直接调用本类。
class CheckoutRepository {
  CheckoutRepository({required this.dio});

  final Dio dio;

  /// 试算（只读：不占券、不扣币、不锁库存）。不可下单不是错误，看 `blockReason`。
  Future<CheckoutPreview> preview(CheckoutRequest request) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.meCheckoutPreview,
      data: request.toJson(),
    );
    return CheckoutPreview.fromJson(resp.data!);
  }

  /// 下单（新结算模式，带 `quoteToken`）。服务端按报价重新计价；现金段为 0 时提交事务内已付款。
  ///
  /// 🔴 `entrySource` / `triggerType` 不传：归因由服务端从购物车行取（3.4 / 9.2），
  /// 端上编一个值比留空更糟。
  ///
  /// [idempotencyKey] 经 `Idempotency-Key` 头去重：同 key 重放取回既有订单，不重复建单、不重复扣币。
  ///
  /// 抛 [CheckoutFailure]。
  Future<ShopOrderRef> placeOrder(PlaceOrderRequest request,
      {required String idempotencyKey}) async {
    try {
      final resp = await dio.post<Map<String, dynamic>>(
        ApiPaths.meShopOrders,
        data: request.toJson(),
        options: Options(headers: <String, dynamic>{'Idempotency-Key': idempotencyKey}),
      );
      return ShopOrderRef.fromJson(resp.data!);
    } on DioException catch (e) {
      throw CheckoutFailure.from(e);
    }
  }
}

/// 下单失败原因（P-5：按 ProblemDetail `typeSlug` 分支，不解析 detail）。
///
/// 🔴 **不把后端 detail 原文丢给用户**（它是中文运营语）；[unavailableLines] 例外 ——
/// 那是结构化数据，前端自己组织文案。
class CheckoutFailure implements Exception {
  const CheckoutFailure(
      {required this.kind, this.unavailableLines = const [], this.outcomeUnknown = false});

  final CheckoutFailureKind kind;

  /// 🔴 **结果不明确**：无响应（超时 / 断网）、5xx、409 `conflict`（同幂等键的前一个请求仍在处理）。
  /// 单**可能已经建了** —— 页面必须保留当前 `Idempotency-Key`，下次提交用同一把键取回既有订单，
  /// 不能因新试算换键（否则 = 同车重复下第二单、重复扣币）。
  final bool outcomeUnknown;

  /// 🔴 有值时页面必须**逐行列出**并允许移除后继续（FR-95：不整单打回）。
  final List<UnavailableLine> unavailableLines;

  static CheckoutFailure from(DioException e) {
    final pd = ProblemDetail.fromDioException(e);
    final body = e.response?.data;
    final lines = _lines(body);
    final status = e.response?.statusCode;
    final unknown = status == null || status >= 500 || pd?.typeSlug == 'conflict';
    final generic = CheckoutFailure(kind: CheckoutFailureKind.generic, outcomeUnknown: unknown);
    switch (pd?.typeSlug) {
      case 'unavailable-lines':
        return CheckoutFailure(kind: CheckoutFailureKind.unavailableLines, unavailableLines: lines);
      case 'not-placeable':
        return const CheckoutFailure(kind: CheckoutFailureKind.notPlaceable);
      case 'coupon-invalid':
        return const CheckoutFailure(kind: CheckoutFailureKind.couponInvalid);
      case 'quote-expired':
        return const CheckoutFailure(kind: CheckoutFailureKind.quoteExpired);
      case 'sku-not-sellable':
        return const CheckoutFailure(kind: CheckoutFailureKind.skuNotSellable);
      case null:
        break;
      default:
        // 有 type 但不是上面几种（如 409 conflict「订单正在提交中」、validation）→ 通用
        return generic;
    }
    // typeSlug 缺失：回落到按状态码的判断（旧后端 / 网关层错误）。
    if (pd?.status == 409 && lines.isNotEmpty) {
      return CheckoutFailure(kind: CheckoutFailureKind.unavailableLines, unavailableLines: lines);
    }
    // 422 覆盖「地址超范围」「无可结算行」「未选地址」—— 都属于「这一单现在下不了」。
    if (pd?.status == 422) return const CheckoutFailure(kind: CheckoutFailureKind.notPlaceable);
    return generic;
  }

  static List<UnavailableLine> _lines(Object? body) {
    if (body is! Map) return const [];
    final raw = body['unavailableLines'];
    return raw is List
        ? raw.whereType<Map<String, dynamic>>().map(UnavailableLine.fromJson).toList(growable: false)
        : const [];
  }
}

enum CheckoutFailureKind {
  unavailableLines,
  notPlaceable,
  couponInvalid,
  quoteExpired,
  skuNotSellable,
  generic,
}

final Provider<CheckoutRepository> checkoutRepositoryProvider =
    Provider<CheckoutRepository>((ref) => CheckoutRepository(dio: ref.read(dioProvider)));
