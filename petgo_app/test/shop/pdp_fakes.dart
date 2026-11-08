/// 商品详情 v3（v1.4.2 shop Story 8.2）测试替身：把详情页所有数据源都换成不打网络的假货。
///
/// 🔴 真 provider 会发请求并留下未完成 Timer；挂详情页的用例一律带上 [pdpOverrides]。
library;

import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/pawcoin/presentation/pawcoin_controller.dart';
import 'package:tailtopia/features/shop/address/data/address_repository.dart';
import 'package:tailtopia/features/shop/address/domain/shipping_address.dart';
import 'package:tailtopia/features/shop/coupon/data/coupon_repository.dart';
import 'package:tailtopia/features/shop/data/shipping_estimate_repository.dart';
import 'package:tailtopia/features/shop/data/shop_repository.dart';
import 'package:tailtopia/features/shop/data/shop_review_repository.dart';
import 'package:tailtopia/features/shop/domain/shipping_estimate.dart';
import 'package:tailtopia/features/shop/domain/shop_product_detail.dart';
import 'package:tailtopia/features/shop/domain/shop_review.dart';

import 'fake_shop_products.dart';

/// 估运费仓库替身：记录每次请求的族键，按 [respond] 返回（抛错 → provider 兜成 failed）。
class FakeShippingEstimateRepository extends ShippingEstimateRepository {
  FakeShippingEstimateRepository({ShippingEstimate Function(ShippingEstimateQuery q)? respond})
      : respond = respond ?? ((_) => const ShippingEstimate(serviceable: true, lowestFee: 9000)),
        super(dio: Dio());

  ShippingEstimate Function(ShippingEstimateQuery q) respond;
  final List<ShippingEstimateQuery> calls = [];

  /// 非 null → 请求挂到它完成（测在途）。
  Completer<void>? gate;

  @override
  Future<ShippingEstimate> estimate(ShippingEstimateQuery q, {int qty = 1}) async {
    calls.add(q);
    final g = gate;
    if (g != null) await g.future;
    return respond(q);
  }
}

/// 本机送达地区替身：初值可配，记录 select。
class FakeDeliveryAreaController extends ShopDeliveryAreaController {
  FakeDeliveryAreaController(this.initial, this.selected);

  final ShopDeliveryArea? initial;
  final List<ShopDeliveryArea> selected;

  @override
  Future<ShopDeliveryArea?> build() async => initial;

  @override
  Future<void> select(ShopDeliveryArea area) async {
    selected.add(area);
    state = AsyncData(area);
  }
}

class FakePawCoin extends PawCoinController {
  @override
  Future<PawCoinState> build() async => const PawCoinState(balance: 50000, items: []);
}

List<Override> pdpOverrides({
  ShopProductDetail? detail,
  Object? detailError,
  FakeCouponRepository? coupons,
  FakeShippingEstimateRepository? estimates,
  ShopDeliveryArea? storedArea,
  List<ShopDeliveryArea>? selectedAreas,
  List<ShippingAddress> addresses = const [],
  Completer<List<ShippingAddress>>? addressGate,
  RegionTree regions = const RegionTree([]),
  ProductReviews reviews = ProductReviews.empty,
}) =>
    [
      shopProductDetailProvider.overrideWith((ref, token) async {
        if (detailError != null) throw detailError;
        return detail!;
      }),
      productReviewsProvider.overrideWith((ref, token) async => reviews),
      couponRepositoryProvider.overrideWithValue(coupons ?? FakeCouponRepository()),
      shippingEstimateRepositoryProvider
          .overrideWithValue(estimates ?? FakeShippingEstimateRepository()),
      shopDeliveryAreaProvider
          .overrideWith(() => FakeDeliveryAreaController(storedArea, selectedAreas ?? [])),
      addressListProvider.overrideWith(
          (ref) => addressGate != null ? addressGate.future : Future.value(addresses)),
      regionTreeProvider.overrideWith((ref) async => regions),
      pawCoinProvider.overrideWith(FakePawCoin.new),
    ];

/// 默认收货地址（只填测试需要的字段）。
ShippingAddress fakeAddress({
  String token = 'addr-1',
  String kecamatan = 'Kebayoran Baru',
  String kota = 'Jakarta Selatan',
  bool isDefault = true,
}) =>
    ShippingAddress(
      token: token,
      receiverName: 'R',
      receiverPhone: '+628123456789',
      provinsi: 'DKI Jakarta',
      kotaKabupaten: kota,
      kecamatan: kecamatan,
      addressLine: 'Jl. X',
      kodePos: '12110',
      isDefault: isDefault,
    );

/// 一棵三级区域树：DKI Jakarta / Jakarta Selatan / {Kebayoran (开通, id 11), Pesanggrahan (未开通, id 12)}。
const RegionTree fakeRegionTree = RegionTree([
  RegionProvinsi('DKI Jakarta', [
    RegionKota('Jakarta Selatan', [
      RegionKecamatan('Kebayoran', true, id: 11),
      RegionKecamatan('Pesanggrahan', false, id: 12),
    ]),
  ]),
  RegionProvinsi('Jawa Barat', [
    RegionKota('Bandung', [RegionKecamatan('Coblong', true, id: 21)]),
  ]),
]);
