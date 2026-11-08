/// 购物车域模型（Story 3.6，消费 3-1 的 `GET/POST/PUT/DELETE /api/v1/me/cart`）。
///
/// 🔴 **单店模型：没有店铺分组**——平台自营是唯一卖家，购物车就是一个平铺列表（FR-96）。
/// 照搬第三方电商的多店铺分组只会凭空多一层。
///
/// 🔴 **失效行单独成组**（[CartView.invalidLines]）：不参与合计、不可结算、**也不静默消失**。
/// 用户加过什么应该看得见——悄悄删掉会让他以为自己记错了。
library;

import 'free_shipping_progress.dart';

/// 失效原因（后端 `CartView.REASON_*`）。
///
/// 🔴 **未知值降级到 [unavailable]**（HANDOFF 硬纪律 5）：认不出的原因照样算失效，
/// 只是给一句不承诺任何事的通用文案。反过来（认不出就当有效）会把一件卖不了的东西
/// 放进合计，用户付了钱才发现——这才是不可接受的那一侧。
enum CartInvalidReason {
  /// 商品已下架：**永久的**，建议换一件。
  delisted('DELISTED'),

  /// 该规格已售罄：**暂时的**，可以等补货。
  outOfStock('OUT_OF_STOCK'),

  /// 认不出的原因（新版后端加了值而 App 未升级）。措辞不区分永久/暂时。
  unavailable('');

  const CartInvalidReason(this.api);

  final String api;

  /// null → null（有效行）；非空但认不出 → [unavailable]，**绝不当成有效**。
  static CartInvalidReason? fromApi(String? raw) {
    if (raw == null || raw.isEmpty) return null;
    for (final r in values) {
      if (r.api == raw) return r;
    }
    return unavailable;
  }
}

/// 购物车一行。字段与后端 `CartView.CartLine` 一一对应。
class CartLine {
  const CartLine({
    required this.skuToken,
    required this.productName,
    required this.specName,
    required this.price,
    required this.qty,
    this.productToken,
    this.mainImageUrl,
    this.availableStock,
    this.invalidReason,
    this.selected = true,
  });

  /// 不可枚举 SKU 标识（NFR-3）。所有写操作都按它寻址。
  final String skuToken;
  final String? productToken;
  final String? productName;
  final String specName;

  /// 单价（最小币种单位，IDR 无小数）。
  final int price;
  final int qty;
  final String? mainImageUrl;

  /// 当下可售库存。🔴 **不落库、每次读车重算**（后端 CartService 注释）。
  final int? availableStock;

  final CartInvalidReason? invalidReason;

  /// 是否勾选结算（Story 4-2，消费 4-1 的 `shop_cart_items.selected`）。
  ///
  /// 🔴 **缺省 `true`**：与后端列的 `DEFAULT TRUE` 同一个理由 ——
  /// 老后端（或字段缺失）时表现为全选，也就是本版之前的整车结算行为。
  /// 缺省成 `false` 会让升级后的 App 在旧后端上显示「一件都没勾」，
  /// 用户点不动结算却找不到原因。
  final bool selected;

  bool get isValid => invalidReason == null;

  int get lineTotal => price * qty;

  /// 是否还能再加一件。
  ///
  /// 🔴 `availableStock == null` 时**禁止加**：库存不明时宁可挡一次购买，
  /// 不可放过一次超卖（HANDOFF 硬纪律 5）。后端仍会二次校验，这里只是不让用户白点。
  bool get canIncrease => isValid && availableStock != null && qty < availableStock!;

  factory CartLine.fromJson(Map<String, dynamic> json) {
    final stock = json['availableStock'];
    return CartLine(
      skuToken: json['skuToken']?.toString() ?? '',
      productToken: _blankToNull(json['productToken']?.toString()),
      productName: _blankToNull(json['productName']?.toString()),
      specName: json['specName']?.toString() ?? '',
      price: json['price'] is num ? (json['price'] as num).toInt() : 0,
      qty: json['qty'] is num ? (json['qty'] as num).toInt() : 0,
      mainImageUrl: _blankToNull(json['mainImageUrl']?.toString()),
      availableStock: stock is num ? stock.toInt() : null,
      invalidReason: CartInvalidReason.fromApi(json['invalidReason']?.toString()),
      selected: json['selected'] as bool? ?? true,
    );
  }

  static String? _blankToNull(String? s) => (s == null || s.isEmpty) ? null : s;
}

/// 购物车视图。
class CartView {
  const CartView({
    required this.lines,
    required this.invalidLines,
    required this.subtotal,
    required this.itemCount,
    required this.selectedSubtotal,
    required this.selectedCount,
    this.estimate,
  });

  /// 有效行（参与合计、可结算）。
  final List<CartLine> lines;

  /// 失效行（🔴 置于列表底部、不参与合计、不可勾选）。
  final List<CartLine> invalidLines;

  /// 有效行合计。
  final int subtotal;

  /// 🔴 **件数，不是种类数**——角标要跟用户脑子里的「我买了几件」对上（FR-96）。
  /// 后端只累计有效行。
  final int itemCount;

  /// 「勾选且有效」的行合计（Story 4-2）。
  ///
  /// 🔴 **底栏金额只认这个字段，前端绝不自己把选中行加起来。**
  /// 前端求一遍、后端 `CheckoutService` 求一遍，是两套独立实现 ——
  /// 只要有一处对失效行的判定口径不同，用户看到的数就和实际扣的钱不一样。
  /// 金额的唯一事实源是服务端。
  final int selectedSubtotal;

  /// 「勾选且有效」的件数（与 [itemCount] 同口径）。
  final int selectedCount;

  /// 底栏预估（v1.4.2 shop Story 3.1 / 8.3，D-9）。🔴 **App 只展示、不自算**（P-1）。
  /// 旧后端不下发 → null，底栏回落到 [selectedSubtotal] 并标「不含运费」。
  final CartEstimate? estimate;

  bool get isEmpty => lines.isEmpty && invalidLines.isEmpty;

  /// 全部**有效**行都已勾选（用于顶部「全选」控件的选中态）。
  ///
  /// 🔴 **判定排除失效行**：车里有一个永远选不上的失效行时，全选框仍要能显示成
  /// 「已全选」—— 否则它永远点不亮，用户会以为控件坏了。
  /// 空车（没有有效行）时为 `false`：没有东西可全选。
  bool get allValidSelected =>
      lines.isNotEmpty && lines.every((l) => l.selected);

  /// 游客态与初始态共用的空车。**游客不发请求**，直接用它（见 `CartController.build`）。
  static const CartView empty = CartView(
    lines: [],
    invalidLines: [],
    subtotal: 0,
    itemCount: 0,
    selectedSubtotal: 0,
    selectedCount: 0,
  );

  factory CartView.fromJson(Map<String, dynamic> json) {
    final subtotal = json['subtotal'] is num ? (json['subtotal'] as num).toInt() : 0;
    final itemCount = json['itemCount'] is num ? (json['itemCount'] as num).toInt() : 0;
    return CartView(
      lines: _lines(json['lines']),
      invalidLines: _lines(json['invalidLines']),
      subtotal: subtotal,
      itemCount: itemCount,
      // 🔴 缺省回落到 subtotal / itemCount，而不是 0：老后端不下发这两个字段时，
      //    每行的 selected 也缺省为 true（全选）—— 此时「选中合计」本就等于全车合计。
      //    缺省成 0 会让底栏显示 Rp 0 并禁用结算按钮，用户彻底买不了东西。
      selectedSubtotal:
          json['selectedSubtotal'] is num ? (json['selectedSubtotal'] as num).toInt() : subtotal,
      selectedCount:
          json['selectedCount'] is num ? (json['selectedCount'] as num).toInt() : itemCount,
      estimate: CartEstimate.fromJson(json['estimate']),
    );
  }

  static List<CartLine> _lines(Object? raw) => raw is List
      ? raw.whereType<Map<String, dynamic>>().map(CartLine.fromJson).toList(growable: false)
      : const [];
}

/// 购物车底栏预估（后端 `CartEstimate`，v1.4.2 shop Story 3.1 AC3）。
///
/// 口径（服务端 `ShopPricingCalculator`，PRD 11-04）：预估应付 = 所选商品 + 本单最低运费 − 默认最优券 −
/// 满额免运减免；不用币、不保价。🔴 **App 不做任何加减**，每个数原样展示（P-1）。
class CartEstimate {
  const CartEstimate({
    required this.payable,
    required this.saved,
    this.lowestFee,
    this.goodsAmount,
    this.discountCouponAmount,
    this.shippingDiscount,
    this.freeShippingProgress,
    this.reason,
  });

  /// 本单最低运费；null = 无报价（无收货地 / 不可配送 / 询价失败 / 一件没选），此时 [payable] 不含运费。
  final int? lowestFee;

  /// 预估应付。
  final int payable;

  /// 已省（「Hemat Rp X」；0 不画）。
  final int saved;

  /// 以下三项后端下发、本页不展示（无明细层，story 拍板 1），只为与后端字段对齐。
  final int? goodsAmount;
  final int? discountCouponAmount;
  final int? shippingDiscount;

  /// 满额免运进度；null = 未配置门槛。
  final FreeShippingProgress? freeShippingProgress;

  /// `null | NO_DESTINATION | NOT_SERVICEABLE | QUOTE_FAILED | NO_SELECTION`（认不出的值原样保留）。
  final String? reason;

  static const String reasonNoDestination = 'NO_DESTINATION';
  static const String reasonNotServiceable = 'NOT_SERVICEABLE';
  static const String reasonQuoteFailed = 'QUOTE_FAILED';
  static const String reasonNoSelection = 'NO_SELECTION';

  /// 运费是否已计入 [payable]。
  bool get shippingKnown => lowestFee != null;

  /// 缺键 / 非 Map / 没有 `payable` → null（按旧后端处理）。
  static CartEstimate? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final payable = raw['payable'];
    if (payable is! num) return null;
    int? n(Object? v) => v is num ? v.toInt() : null;
    final reason = raw['reason']?.toString();
    return CartEstimate(
      lowestFee: n(raw['lowestFee']),
      payable: payable.toInt(),
      saved: n(raw['saved']) ?? 0,
      goodsAmount: n(raw['goodsAmount']),
      discountCouponAmount: n(raw['discountCouponAmount']),
      shippingDiscount: n(raw['shippingDiscount']),
      freeShippingProgress: FreeShippingProgress.fromJson(raw['freeShippingProgress']),
      reason: (reason == null || reason.isEmpty) ? null : reason,
    );
  }
}
