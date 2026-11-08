/// 领券侧的券模型（v1.4.2 shop Story 8.1，消费 2.2 的 `CouponView` / `NewbieCampaignView`）。
///
/// 🔴 对外标识只有 token：[ShopCoupon.couponToken] 是券**模板** token（埋点 `coupon_id` 用它），
/// [ShopCoupon.userCouponToken] 是已领到的用户券 token。不读、也不存在任何自增 id。
/// 金额（面额 / 门槛 / 封顶）全是服务端值，App 只格式化。
library;

import 'package:dio/dio.dart';

import '../../../../core/network/problem_detail.dart';
import '../../domain/checkout_preview.dart' show CouponKind, CouponOption;

/// 一张可领 / 已领的券（首页「Kupon buat kamu」、banner 领券、详情可领券共用）。
class ShopCoupon {
  const ShopCoupon({
    required this.couponToken,
    required this.kind,
    required this.amount,
    required this.minSpend,
    this.userCouponToken,
    this.nameId,
    this.nameEn,
    this.validUntil,
    this.claimed = false,
    this.newbie = false,
  });

  final String couponToken;
  final String? userCouponToken;
  final CouponKind kind;
  final String? nameId;
  final String? nameEn;

  /// 满减：面额；免运：封顶额。
  final int amount;
  final int minSpend;
  final DateTime? validUntil;
  final bool claimed;

  /// 新人券（自动发放，不可领；首页置顶紫底）。
  final bool newbie;

  ShopCoupon copyWith({bool? claimed}) => ShopCoupon(
        couponToken: couponToken,
        userCouponToken: userCouponToken,
        kind: kind,
        amount: amount,
        minSpend: minSpend,
        nameId: nameId,
        nameEn: nameEn,
        validUntil: validUntil,
        claimed: claimed ?? this.claimed,
        newbie: newbie,
      );

  /// 交给 7.1 的 [CouponOption] 渲染券面（`CouponCard`）。
  CouponOption toOption() => CouponOption(
        userCouponToken: userCouponToken ?? '',
        couponToken: couponToken,
        kind: kind,
        expiringSoon: false,
        deduction: 0,
        nameId: nameId,
        nameEn: nameEn,
        amount: amount,
        minSpend: minSpend,
        validUntil: validUntil,
      );

  factory ShopCoupon.fromJson(Map<String, dynamic> j) {
    String? blank(Object? v) => (v == null || v.toString().isEmpty) ? null : v.toString();
    int n(Object? v) => v is num ? v.toInt() : 0;
    return ShopCoupon(
      couponToken: j['couponToken']?.toString() ?? '',
      userCouponToken: blank(j['userCouponToken']),
      kind: j['kind']?.toString() == 'FREE_SHIPPING' ? CouponKind.freeShipping : CouponKind.discount,
      nameId: blank(j['nameId']),
      nameEn: blank(j['nameEn']),
      amount: n(j['amount']),
      minSpend: n(j['minSpend']),
      validUntil: j['validUntil'] is String ? DateTime.tryParse(j['validUntil'] as String) : null,
      claimed: j['claimed'] == true,
      newbie: j['newbie'] == true,
    );
  }
}

/// 游客新人券活动（`GET /shop/coupons/newbie-campaign`）。[campaignToken] = 新人券模板 token。
class NewbieCampaign {
  const NewbieCampaign({required this.campaignToken, required this.amount, required this.minSpend});

  final String campaignToken;
  final int amount;
  final int minSpend;

  factory NewbieCampaign.fromJson(Map<String, dynamic> j) => NewbieCampaign(
        campaignToken: j['campaignToken']?.toString() ?? '',
        amount: j['amount'] is num ? (j['amount'] as num).toInt() : 0,
        minSpend: j['minSpend'] is num ? (j['minSpend'] as num).toInt() : 0,
      );
}

/// 领券接口的来源（请求体 `source`）与埋点 `source` 取值。
enum CouponClaimSource {
  home('HOME', 'home'),
  banner('BANNER', 'banner'),
  detail('DETAIL', 'detail');

  const CouponClaimSource(this.api, this.analytics);

  final String api;
  final String analytics;
}

/// 领券失败分类（按 ProblemDetail `type` 的 slug 后缀判）。
enum CouponClaimFailure {
  alreadyClaimed,
  soldOut,
  notInPeriod,

  /// 未登录（401）—— 正常路径游客已先走软登录，这里兜底。
  unauthorized,

  /// 网络 / 其它错误。
  other;

  static CouponClaimFailure from(Object error) {
    if (error is! DioException) return other;
    if (error.response?.statusCode == 401) return unauthorized;
    final slug = ProblemDetail.fromJson(error.response?.data)?.typeSlug ?? '';
    if (slug.endsWith('already-claimed')) return alreadyClaimed;
    if (slug.endsWith('sold-out')) return soldOut;
    if (slug.endsWith('not-in-period')) return notInPeriod;
    return other;
  }
}
