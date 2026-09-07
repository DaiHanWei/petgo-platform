/// 购物车域模型（Story 3.6，消费 3-1 的 `GET/POST/PUT/DELETE /api/v1/me/cart`）。
///
/// 🔴 **单店模型：没有店铺分组**——平台自营是唯一卖家，购物车就是一个平铺列表（FR-96）。
/// 照搬第三方电商的多店铺分组只会凭空多一层。
///
/// 🔴 **失效行单独成组**（[CartView.invalidLines]）：不参与合计、不可结算、**也不静默消失**。
/// 用户加过什么应该看得见——悄悄删掉会让他以为自己记错了。
library;

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

  bool get isEmpty => lines.isEmpty && invalidLines.isEmpty;

  /// 游客态与初始态共用的空车。**游客不发请求**，直接用它（见 `CartController.build`）。
  static const CartView empty =
      CartView(lines: [], invalidLines: [], subtotal: 0, itemCount: 0);

  factory CartView.fromJson(Map<String, dynamic> json) => CartView(
        lines: _lines(json['lines']),
        invalidLines: _lines(json['invalidLines']),
        subtotal: json['subtotal'] is num ? (json['subtotal'] as num).toInt() : 0,
        itemCount: json['itemCount'] is num ? (json['itemCount'] as num).toInt() : 0,
      );

  static List<CartLine> _lines(Object? raw) => raw is List
      ? raw.whereType<Map<String, dynamic>>().map(CartLine.fromJson).toList(growable: false)
      : const [];
}
