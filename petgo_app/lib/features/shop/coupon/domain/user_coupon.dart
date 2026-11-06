/// 我的券模型（v1.4.2 shop Story 9.3，消费 2.2 `GET /me/coupons` 的 `CouponView` / `CouponPage`）。
///
/// 🔴 对外标识只有 token：[UserCoupon.couponToken] 是券**模板** token（埋点 `coupon_id` 用它），
/// [UserCoupon.userCouponToken] 是用户券 token，[UserCoupon.usedOrderToken] 是订单 token。不读任何自增 id。
/// 金额（面额 / 门槛 / 封顶）全是服务端值，App 只格式化。
///
/// 🔴 缺键容错：任何字段缺失 / 类型不对都降级为空值，不抛异常（列表里一张坏数据不该拖垮整页）。
library;

import 'package:dio/dio.dart';

import '../../../../core/network/problem_detail.dart';
import '../../domain/checkout_preview.dart' show CouponKind, CouponOption;

/// 我的券的两个页签（PRD 11-05：**无「已过期」页签**，已过期券不展示）。
enum CouponTab {
  available('available'),
  used('used');

  const CouponTab(this.api);

  /// `tab=` 查询参数取值。
  final String api;
}

/// 券适用范围。未知值降级为 [all]（与「不限」同样不显示限制，比显示错的限制安全）。
enum CouponScopeType {
  all,
  category,
  product;

  static CouponScopeType fromApi(Object? raw) => switch (raw?.toString()) {
        'CATEGORY' => category,
        'PRODUCT' => product,
        _ => all,
      };
}

/// 用户券状态。🔴 App **不按状态过滤**列表（过滤口径只在服务端），只按它决定渲染方式。
enum UserCouponStatus {
  available,
  locked,
  used,
  unknown;

  static UserCouponStatus fromApi(Object? raw) => switch (raw?.toString()) {
        'AVAILABLE' => available,
        'LOCKED' => locked,
        'USED' => used,
        _ => unknown,
      };
}

/// 我的券里的一张券。
class UserCoupon {
  const UserCoupon({
    required this.couponToken,
    required this.userCouponToken,
    required this.kind,
    required this.amount,
    required this.minSpend,
    this.nameId,
    this.nameEn,
    this.scopeType = CouponScopeType.all,
    this.scopeCategories = const [],
    this.scopeProductCount,
    this.validFrom,
    this.validUntil,
    this.expiringSoon = false,
    this.isNew = false,
    this.status = UserCouponStatus.unknown,
    this.lockedByPendingOrder = false,
    this.usedAt,
    this.usedOrderNo,
    this.usedOrderToken,
  });

  final String couponToken;
  final String userCouponToken;
  final CouponKind kind;
  final String? nameId;
  final String? nameEn;

  /// 满减：面额；免运：每张封顶额（D-1）。
  final int amount;
  final int minSpend;
  final CouponScopeType scopeType;

  /// CATEGORY 时的品类码（`MAKANAN` 等）。
  final List<String> scopeCategories;
  final int? scopeProductCount;
  final DateTime? validFrom;
  final DateTime? validUntil;
  final bool expiringSoon;

  /// 刚兑换成功（G6「Baru」）。
  final bool isNew;
  final UserCouponStatus status;

  /// C-3：被待付款订单占用（可用页签内置灰、不可点）。
  final bool lockedByPendingOrder;

  /// C-9：已使用券的使用时刻 / 订单号 / 订单 token。
  final DateTime? usedAt;
  final String? usedOrderNo;
  final String? usedOrderToken;

  /// 被待付款订单占用（`lockedByPendingOrder` 或状态 LOCKED 任一即算，二者服务端同源）。
  bool get isLocked => lockedByPendingOrder || status == UserCouponStatus.locked;

  /// 交给 7.1 的 [CouponOption] 渲染券面（`CouponCard`）。
  CouponOption toOption() => CouponOption(
        userCouponToken: userCouponToken,
        couponToken: couponToken,
        kind: kind,
        expiringSoon: expiringSoon,
        deduction: 0,
        nameId: nameId,
        nameEn: nameEn,
        amount: amount,
        minSpend: minSpend,
        validUntil: validUntil,
      );

  factory UserCoupon.fromJson(Map<String, dynamic> j) {
    String? blank(Object? v) => (v == null || v.toString().isEmpty) ? null : v.toString();
    int n(Object? v) => v is num ? v.toInt() : 0;
    DateTime? t(Object? v) => v is String ? DateTime.tryParse(v) : null;
    final cats = j['scopeCategories'];
    final pc = j['scopeProductCount'];
    return UserCoupon(
      couponToken: j['couponToken']?.toString() ?? '',
      userCouponToken: j['userCouponToken']?.toString() ?? '',
      kind: j['kind']?.toString() == 'FREE_SHIPPING' ? CouponKind.freeShipping : CouponKind.discount,
      nameId: blank(j['nameId']),
      nameEn: blank(j['nameEn']),
      amount: n(j['amount']),
      minSpend: n(j['minSpend']),
      scopeType: CouponScopeType.fromApi(j['scopeType']),
      scopeCategories: cats is List
          ? cats.where((e) => e != null).map((e) => e.toString()).toList(growable: false)
          : const [],
      scopeProductCount: pc is num ? pc.toInt() : null,
      validFrom: t(j['validFrom']),
      validUntil: t(j['validUntil']),
      expiringSoon: j['expiringSoon'] == true,
      isNew: j['isNew'] == true,
      status: UserCouponStatus.fromApi(j['status']),
      lockedByPendingOrder: j['lockedByPendingOrder'] == true,
      usedAt: t(j['usedAt']),
      usedOrderNo: blank(j['usedOrderNo']),
      usedOrderToken: blank(j['usedOrderToken']),
    );
  }
}

/// `GET /me/coupons` 一页：`{items[], nextCursor, availableCount}`（每页 20）。
class MyCouponPage {
  const MyCouponPage({required this.items, this.nextCursor, this.availableCount});

  final List<UserCoupon> items;
  final String? nextCursor;

  /// AVAILABLE 且未过期张数（不含 LOCKED）。缺键 → null（页签退回不带数的写法）。
  final int? availableCount;

  bool get hasMore => nextCursor != null && nextCursor!.isNotEmpty;

  factory MyCouponPage.fromJson(Map<String, dynamic> j) {
    final raw = j['items'];
    final cur = j['nextCursor'];
    final cnt = j['availableCount'];
    return MyCouponPage(
      items: raw is List
          ? raw
              .whereType<Map<String, dynamic>>()
              .map(UserCoupon.fromJson)
              .where((c) => c.userCouponToken.isNotEmpty)
              .toList(growable: false)
          : const [],
      nextCursor: (cur == null || cur.toString().isEmpty) ? null : cur.toString(),
      availableCount: cnt is num ? cnt.toInt() : null,
    );
  }
}

/// 兑换失败分类（P-5：按 ProblemDetail `typeSlug` 分支，**不解析 `detail`**）。
enum CouponRedeemError {
  /// `coupon-not-found`：码无效。
  invalid,

  /// `coupon-code-used`：一次性码已被使用。
  codeUsed,

  /// `coupon-not-in-period`。
  notInPeriod,

  /// `coupon-already-claimed`。
  alreadyClaimed,

  /// `coupon-sold-out`。
  soldOut,

  /// `coupon-redeem-locked` 或任何 429：防试码锁定 1 小时（服务端计时）。
  locked,

  /// 401：交全局登录态处理，本页不提示。
  unauthorized,

  /// 网络 / 其他（含 `coupon-invalid` 等兑换接口不该出现的码）→ toast。
  other;

  /// 是否显示在输入框下（红框 + 红字）。[other] 走 toast，[unauthorized] 不提示。
  bool get isInline => this != other && this != unauthorized;

  static CouponRedeemError from(Object error) {
    if (error is! DioException) return other;
    final status = error.response?.statusCode;
    if (status == 401) return unauthorized;
    final slug = ProblemDetail.fromJson(error.response?.data)?.typeSlug ?? '';
    final bySlug = fromSlug(slug);
    if (bySlug != null) return bySlug;
    if (status == 429) return locked;
    return other;
  }

  /// `typeSlug` → 分类；未知 → null。
  static CouponRedeemError? fromSlug(String slug) => switch (slug) {
        'coupon-not-found' => invalid,
        'coupon-code-used' => codeUsed,
        'coupon-not-in-period' => notInPeriod,
        'coupon-already-claimed' => alreadyClaimed,
        'coupon-sold-out' => soldOut,
        'coupon-redeem-locked' => locked,
        _ => null,
      };
}

/// 兑换码最大长度（D-4 `VARCHAR(32)`）。
const int kCouponCodeMaxLength = 32;

/// 兑换码最小长度（后端 `CouponAdminService.CODE_FORMAT = ^[A-Z0-9]{4,32}$`）。
const int kCouponCodeMinLength = 4;

final RegExp _nonCodeChars = RegExp('[^A-Z0-9]');

/// 兑换码规范化（复审 #3，保守实现）：大写 → 去掉一切非 `[A-Z0-9]` 字符（含首尾 / 中间空格、连字符）→ 截到 32 位。
/// 与后端码格式 `^[A-Z0-9]{4,32}$` 同口径。
String normalizeCouponCode(String raw) {
  final s = raw.toUpperCase().replaceAll(_nonCodeChars, '');
  return s.length > kCouponCodeMaxLength ? s.substring(0, kCouponCodeMaxLength) : s;
}

/// 规范化后不足 4 位：不可提交（不发请求、不耗服务端试错次数）。
bool isSubmittableCouponCode(String raw) =>
    normalizeCouponCode(raw).length >= kCouponCodeMinLength;
