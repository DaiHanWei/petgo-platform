import 'dart:async';

import 'package:dio/dio.dart';
import 'package:tailtopia/features/shop/coupon/data/coupon_repository.dart';
import 'package:tailtopia/features/shop/coupon/domain/user_coupon.dart';

/// 我的券测试替身（v1.4.2 shop Story 9.3）：按页签给页，记录请求，兑换结果可注入。
class FakeMyCouponRepository extends CouponRepository {
  FakeMyCouponRepository({
    Map<CouponTab, List<MyCouponPage>>? pages,
    this.listError,
  })  : pages = pages ?? {},
        super(dio: Dio());

  /// 每个页签依次返回的页（第 0 页为首屏，之后按 cursor 顺序）；用完后重复最后一页。
  final Map<CouponTab, List<MyCouponPage>> pages;
  Object? listError;

  /// 首屏（无 cursor）请求等待它（测首屏未返回时的兑换）。
  Completer<void>? firstGate;

  /// 加载更多（带 cursor）请求等待它 / 抛它。
  Completer<void>? moreGate;
  Object? moreError;

  /// 第二次起的首屏请求（下拉 / 后台刷新）抛它。
  Object? refreshError;

  final List<(CouponTab, String?)> listCalls = [];
  final List<String> redeemCalls = [];

  /// 兑换：返回值或抛错；[redeemGate] 非空时等待它完成（测在途态）。
  UserCoupon? redeemResult;
  Object? redeemError;
  Completer<void>? redeemGate;

  @override
  Future<MyCouponPage> myCoupons(CouponTab tab, {String? cursor}) async {
    listCalls.add((tab, cursor));
    if (cursor == null && firstGate != null) await firstGate!.future;
    if (cursor != null && moreGate != null) await moreGate!.future;
    if (listError != null) throw listError!;
    if (cursor != null && moreError != null) throw moreError!;
    if (cursor == null &&
        refreshError != null &&
        listCalls.where((c) => c.$1 == tab && c.$2 == null).length > 1) {
      throw refreshError!;
    }
    final list = pages[tab] ?? const [MyCouponPage(items: [], availableCount: 0)];
    final idx = listCalls.where((c) => c.$1 == tab && c.$2 != null).length;
    if (cursor == null) return list.first;
    return list[idx.clamp(0, list.length - 1)];
  }

  @override
  Future<UserCoupon> redeem(String code) async {
    redeemCalls.add(normalizeCouponCode(code));
    if (redeemGate != null) await redeemGate!.future;
    if (redeemError != null) throw redeemError!;
    return redeemResult!;
  }

  @override
  Future<int> availableCount() async => 0;
}

DioException problem(int status, String? slug) => DioException(
      requestOptions: RequestOptions(path: '/api/v1/me/coupons/redeem'),
      response: Response(
        requestOptions: RequestOptions(path: '/api/v1/me/coupons/redeem'),
        statusCode: status,
        data: slug == null ? null : {'type': 'https://petgo/errors/$slug', 'status': status},
      ),
      type: DioExceptionType.badResponse,
    );

/// UI 稿示例券。
Map<String, dynamic> couponJson({
  String token = 'uc-1',
  String couponToken = 'tpl-1',
  String kind = 'DISCOUNT',
  String nameId = 'Kupon pengguna baru',
  int amount = 20000,
  int minSpend = 100000,
  String scopeType = 'ALL',
  List<String> scopeCategories = const [],
  String validUntil = '2026-12-31T16:59:00Z',
  bool expiringSoon = false,
  bool isNew = false,
  String status = 'AVAILABLE',
  bool locked = false,
  String? usedAt,
  String? usedOrderNo,
  String? usedOrderToken,
}) =>
    {
      'couponToken': couponToken,
      'userCouponToken': token,
      'kind': kind,
      'nameId': nameId,
      'nameEn': nameId,
      'amount': amount,
      'minSpend': minSpend,
      'scopeType': scopeType,
      'scopeCategories': scopeCategories,
      'validUntil': validUntil,
      'expiringSoon': expiringSoon,
      'isNew': isNew,
      'status': status,
      'lockedByPendingOrder': locked,
      'usedAt': ?usedAt,
      'usedOrderNo': ?usedOrderNo,
      'usedOrderToken': ?usedOrderToken,
    };

UserCoupon coupon({
  String token = 'uc-1',
  String couponToken = 'tpl-1',
  String kind = 'DISCOUNT',
  String nameId = 'Kupon pengguna baru',
  int amount = 20000,
  int minSpend = 100000,
  String scopeType = 'ALL',
  List<String> scopeCategories = const [],
  bool expiringSoon = false,
  bool isNew = false,
  String status = 'AVAILABLE',
  bool locked = false,
  String? usedAt,
  String? usedOrderNo,
  String? usedOrderToken,
}) =>
    UserCoupon.fromJson(couponJson(
      token: token,
      couponToken: couponToken,
      kind: kind,
      nameId: nameId,
      amount: amount,
      minSpend: minSpend,
      scopeType: scopeType,
      scopeCategories: scopeCategories,
      expiringSoon: expiringSoon,
      isNew: isNew,
      status: status,
      locked: locked,
      usedAt: usedAt,
      usedOrderNo: usedOrderNo,
      usedOrderToken: usedOrderToken,
    ));
