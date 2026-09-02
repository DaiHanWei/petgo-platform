/// 退货域模型（Story 5.7 申请页 · 5.8 退款方式页 · 5.9 进度页）。
///
/// 🔴 **可退判定与金额一律由服务端下发**，本层只做解析与展示：
/// 规则散在两侧的后果不是「不一致」，而是**只在某些行上不一致** ——
/// 那种问题在测试里几乎撞不上，在用户手里天天撞上。
library;

/// 退货类型。每一种的**回程运费归属不同**，且要在选项右侧直接标出（5.7 AC）。
enum ReturnType {
  /// 质量问题（破损 / 临期 / 错发）→ 回程运费**平台**承担，且触发平台责任补偿溢价。
  qualityIssue('QUALITY_ISSUE', platformPaysReturnShipping: true),

  /// 非质量问题（不想要 / 买错规格）→ 回程运费**用户**承担，且需未拆封。
  nonQualityIssue('NON_QUALITY_ISSUE', platformPaysReturnShipping: false),

  /// 拒收 → 平台承担，跳过寄回与质检。
  refusedOnDelivery('REFUSED_ON_DELIVERY', platformPaysReturnShipping: true),

  /// 发货前取消 → 无实物往返。
  cancelBeforeShipment('CANCEL_BEFORE_SHIPMENT', platformPaysReturnShipping: true);

  const ReturnType(this.api, {required this.platformPaysReturnShipping});

  final String api;

  /// 🔴 **每个原因选项右侧直接标出回程运费由谁承担**，不等提交后才告知 ——
  /// 那是最典型的客诉来源。
  final bool platformPaysReturnShipping;

  static ReturnType? fromApi(String? raw) {
    for (final t in values) {
      if (t.api == raw) return t;
    }
    return null;
  }
}

/// 退款单状态（后端 9 态）。
enum ReturnStatus {
  pendingReview('PENDING_REVIEW'),
  rejected('REJECTED'),
  awaitShipback('AWAIT_SHIPBACK'),
  inspecting('INSPECTING'),
  refunding('REFUNDING'),
  refunded('REFUNDED'),
  refundFailed('REFUND_FAILED'),
  closed('CLOSED'),
  withdrawn('WITHDRAWN'),

  /// 认不出的状态（后端新增而 App 未升级）。🔴 降级到「只读、不给动作」。
  unknown('');

  const ReturnStatus(this.api);

  final String api;

  static ReturnStatus fromApi(String? raw) {
    for (final s in values) {
      if (s.api == raw && s != unknown) return s;
    }
    return unknown;
  }

  /// 可撤销的两态（S-8 ④）。
  bool get canWithdraw => this == pendingReview || this == awaitShipback;
}

/// 现金段去向。🔴 **没有对应的 PawCoin 段枚举** —— 那不是遗漏：
/// PawCoin 段只能退回 PawCoin，不给用户产生预期再打破（FR-100A 规则 1）。
enum CashDestination {
  toBank('TO_BANK'),
  toPawcoin('TO_PAWCOIN');

  const CashDestination(this.api);

  final String api;
}

/// 出款渠道与权威渠道费（与后端 PayoutChannel / FR-105 费率表逐字一致）。
///
/// 🔴 **费率在这里只用于展示**：净额由后端权威计算，前端不得传费。
enum PayoutChannel {
  bca('BCA', 0),
  ovo('OVO', 2500),
  gopay('GOPAY', 2500);

  const PayoutChannel(this.api, this.fee);

  final String api;
  final int fee;
}

/// 退货申请页里的一行。
class ReturnableLine {
  const ReturnableLine({
    required this.orderLineId,
    required this.productName,
    required this.specName,
    required this.unitPrice,
    required this.qty,
    required this.refundedQty,
    required this.returnableQty,
    required this.returnPolicy,
    required this.selectable,
    this.blockedCode,
  });

  final int orderLineId;
  final String productName;
  final String specName;
  final int unitPrice;
  final int qty;
  final int refundedQty;
  final int returnableQty;
  final String returnPolicy;

  /// 非质量问题下是否可勾选。
  final bool selectable;

  /// 🔴 不可勾选的原因由**服务端**给，前端直接展示 —— 前端自己拼会和服务端判定漂移。
  /// 不可退的**原因码**（不是文案）：`ALL_RETURNED` / `NON_RETURNABLE` /
  /// `NO_RETURN_AFTER_OPEN`；可退时为 null。
  ///
  /// 🔴 D-9：此前后端下发的是中文串，而本 App **没有中文包**、那句也不经 i18n
  /// ⇒ 印尼用户在退货申请页必现中文。文案改由端上按码取。
  /// ⚠️ 未知码要有兜底 —— 后端将来加新码时，老版本 App 不该显示一片空白。
  final String? blockedCode;

  /// 🔴 「开封不退」的行在**质量问题**下仍可勾选：破损/临期/错发与是否开封无关。
  /// 把它一并挡掉等于让收到破损品的用户无路可走。
  bool selectableFor(ReturnType type) {
    if (returnableQty <= 0) return false;
    if (returnPolicy == 'NON_RETURNABLE') return false;
    if (returnPolicy == 'NO_RETURN_AFTER_OPEN') {
      return type == ReturnType.qualityIssue;
    }
    return true;
  }

  factory ReturnableLine.fromJson(Map<String, dynamic> j) => ReturnableLine(
        orderLineId: _int(j['orderLineId']) ?? 0,
        productName: j['productName']?.toString() ?? '',
        specName: j['specName']?.toString() ?? '',
        unitPrice: _int(j['unitPrice']) ?? 0,
        qty: _int(j['qty']) ?? 0,
        refundedQty: _int(j['refundedQty']) ?? 0,
        returnableQty: _int(j['returnableQty']) ?? 0,
        // 🔴 未知/缺失的退货规则降级到最保守档：宁可少承诺
        returnPolicy: j['returnPolicy']?.toString() ?? 'NON_RETURNABLE',
        selectable: j['selectable'] == true,
        blockedCode: j['blockedCode']?.toString(),
      );
}

/// 退货收件地址（S-7 用户自寄；🔴 不出现上门取件）。
class ReturnAddress {
  const ReturnAddress({
    required this.receiverName,
    required this.receiverPhone,
    required this.addressText,
  });

  final String receiverName;
  final String receiverPhone;
  final String addressText;

  factory ReturnAddress.fromJson(Map<String, dynamic> j) => ReturnAddress(
        receiverName: j['receiverName']?.toString() ?? '',
        receiverPhone: j['receiverPhone']?.toString() ?? '',
        addressText: j['addressText']?.toString() ?? '',
      );
}

/// 退货申请页整页数据。
class ReturnEligibility {
  const ReturnEligibility({
    required this.orderToken,
    required this.eligible,
    required this.lines,
    this.ineligibleReason,
    this.activeRequestToken,
    this.returnWindowEndsAt,
    this.returnAddress,
  });

  final String orderToken;
  final bool eligible;
  final String? ineligibleReason;

  /// 🔴 非空时订单详情页的退货入口必须置灰并提示「已有退货申请处理中」（UX-DR3 / C-12）。
  final String? activeRequestToken;
  final DateTime? returnWindowEndsAt;
  final List<ReturnableLine> lines;
  final ReturnAddress? returnAddress;

  factory ReturnEligibility.fromJson(Map<String, dynamic> j) => ReturnEligibility(
        orderToken: j['orderToken']?.toString() ?? '',
        eligible: j['eligible'] == true,
        ineligibleReason: j['ineligibleReason']?.toString(),
        activeRequestToken: j['activeRequestToken']?.toString(),
        returnWindowEndsAt: _time(j['returnWindowEndsAt']),
        lines: j['lines'] is List
            ? (j['lines'] as List)
                .whereType<Map<String, dynamic>>()
                .map(ReturnableLine.fromJson)
                .toList(growable: false)
            : const [],
        returnAddress: j['returnAddress'] is Map<String, dynamic>
            ? ReturnAddress.fromJson(j['returnAddress'] as Map<String, dynamic>)
            : null,
      );
}

/// 退货进度 / 退款方式页数据。
class ReturnProgress {
  const ReturnProgress({
    required this.returnToken,
    required this.orderToken,
    required this.status,
    required this.returnType,
    required this.fullReturn,
    required this.outboundFeeRefundable,
    required this.coinRefund,
    required this.cashRefund,
    required this.compensationPremium,
    required this.incentivePremium,
    this.incentivePremiumIfPawcoin = 0,
    required this.shipbackReimbursement,
    required this.grandTotal,
    required this.lines,
    this.returnShipBearer,
    this.rejectReason,
    this.inspectionPhotoKeys,
    this.rejectDisposal,
    this.returnShipBackTrackingNo,
    this.shipbackDeadline,
    this.shipbackTrackingNo,
    this.cashDestination,
    this.payoutChannel,
    this.createdAt,
  });

  final String returnToken;
  final String orderToken;
  final ReturnStatus status;
  final ReturnType? returnType;
  final bool fullReturn;
  final String? returnShipBearer;
  final bool outboundFeeRefundable;
  final String? rejectReason;
  final String? inspectionPhotoKeys;
  final String? rejectDisposal;
  final String? returnShipBackTrackingNo;
  final DateTime? shipbackDeadline;
  final String? shipbackTrackingNo;

  /// 🔴 只有现金段有去向。null = 用户还没选。
  final String? cashDestination;
  final String? payoutChannel;

  final int coinRefund;
  final int cashRefund;

  /// 平台责任补偿溢价。🔴 **金额与比例一律取自后端**，前端不得硬编码 ——
  /// 原型里的 `+5%` / `Rp 1.500` 是示例值不是规格（D-8 的比例与上限仍待财务定）。
  final int compensationPremium;
  final int incentivePremium;

  /// 「若选择转 PawCoin，激励溢价会是多少」——与当前选择无关的**预览值**（D-11）。
  ///
  /// 🔴 退款方式页那句「Lands instantly, with a bonus」靠它决定说不说。
  /// ⚠️ 不能用 [incentivePremium] 判：那个要**已经选了**转币才非零，
  /// 而这句承诺正是在用户做选择**之前**看到的 —— 拿它判就恒为 0、永远藏掉。
  final int incentivePremiumIfPawcoin;

  final int shipbackReimbursement;
  final int grandTotal;

  final DateTime? createdAt;
  final List<ReturnProgressLine> lines;

  /// 两段拆分只在两段都非零时才需要渲染（纯 QRIS 单与既有虚拟商品退款完全一致，无新增 UI）。
  bool get isMixed => coinRefund > 0 && cashRefund > 0;

  /// PawCoin 段占比（仅用于比例条展示；🔴 **金额计算绝不用它反算**）。
  double get coinShare {
    final total = coinRefund + cashRefund;
    return total <= 0 ? 0 : coinRefund / total;
  }

  factory ReturnProgress.fromJson(Map<String, dynamic> j) => ReturnProgress(
        returnToken: j['returnToken']?.toString() ?? '',
        orderToken: j['orderToken']?.toString() ?? '',
        status: ReturnStatus.fromApi(j['status']?.toString()),
        returnType: ReturnType.fromApi(j['returnType']?.toString()),
        fullReturn: j['fullReturn'] == true,
        returnShipBearer: j['returnShipBearer']?.toString(),
        outboundFeeRefundable: j['outboundFeeRefundable'] == true,
        rejectReason: j['rejectReason']?.toString(),
        inspectionPhotoKeys: j['inspectionPhotoKeys']?.toString(),
        rejectDisposal: j['rejectDisposal']?.toString(),
        returnShipBackTrackingNo: j['returnShipBackTrackingNo']?.toString(),
        shipbackDeadline: _time(j['shipbackDeadline']),
        shipbackTrackingNo: j['shipbackTrackingNo']?.toString(),
        cashDestination: j['cashDestination']?.toString(),
        payoutChannel: j['payoutChannel']?.toString(),
        coinRefund: _int(j['coinRefund']) ?? 0,
        cashRefund: _int(j['cashRefund']) ?? 0,
        compensationPremium: _int(j['compensationPremium']) ?? 0,
        incentivePremium: _int(j['incentivePremium']) ?? 0,
        incentivePremiumIfPawcoin: _int(j['incentivePremiumIfPawcoin']) ?? 0,
        shipbackReimbursement: _int(j['shipbackReimbursement']) ?? 0,
        grandTotal: _int(j['grandTotal']) ?? 0,
        createdAt: _time(j['createdAt']),
        lines: j['lines'] is List
            ? (j['lines'] as List)
                .whereType<Map<String, dynamic>>()
                .map(ReturnProgressLine.fromJson)
                .toList(growable: false)
            : const [],
      );
}

class ReturnProgressLine {
  const ReturnProgressLine({
    required this.productName,
    required this.specName,
    required this.qty,
    required this.lineRefundAmount,
  });

  final String productName;
  final String specName;
  final int qty;
  final int lineRefundAmount;

  factory ReturnProgressLine.fromJson(Map<String, dynamic> j) => ReturnProgressLine(
        productName: j['productName']?.toString() ?? '',
        specName: j['specName']?.toString() ?? '',
        qty: _int(j['qty']) ?? 0,
        lineRefundAmount: _int(j['lineRefundAmount']) ?? 0,
      );
}

int? _int(Object? v) => v is num ? v.toInt() : null;

DateTime? _time(Object? v) {
  if (v is! String || v.isEmpty) return null;
  // 后端一律 UTC（CLAUDE.md 命名映射链），统一转本地再比较
  return DateTime.tryParse(v)?.toLocal();
}
