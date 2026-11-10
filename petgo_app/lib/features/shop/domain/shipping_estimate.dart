/// 商品详情运费估算（v1.4.2 shop Story 3.1 后端 · App 8.2，D-9 / FR-127 / FR-172.1）。
///
/// 🔴 所有数值只取服务端（P-1）：最低运费、免运门槛与「还差多少」都由计价器算好下发，App 只格式化。
library;

import 'free_shipping_progress.dart';

class ShippingEstimate {
  const ShippingEstimate({
    this.serviceable,
    this.lowestFee,
    this.quoteFailed = false,
    this.freeShippingProgress,
    this.reason,
  });

  /// 能否送达；无收货地 / 询价失败为 null。
  final bool? serviceable;

  /// 本单所有快递的最低运费；不可配送 / 未询价为 null。
  final int? lowestFee;

  /// Biteship 询价失败（HTTP 仍 200）→ B15「Ongkir belum bisa dimuat · Coba lagi」。
  final bool quoteFailed;

  /// 免运进度（基数 = 商品实付）；门槛 0 或无收货地为 null。
  final FreeShippingProgress? freeShippingProgress;

  /// `null | NO_DESTINATION | NOT_SERVICEABLE | QUOTE_FAILED`。
  final String? reason;

  /// 请求整体失败（网络 / 5xx）按 B15 处理：可重试，没有进度可画。
  static const ShippingEstimate failed = ShippingEstimate(quoteFailed: true);

  factory ShippingEstimate.fromJson(Map<String, dynamic> j) => ShippingEstimate(
        serviceable: j['serviceable'] is bool ? j['serviceable'] as bool : null,
        lowestFee: j['lowestFee'] is num ? (j['lowestFee'] as num).toInt() : null,
        quoteFailed: j['quoteFailed'] == true,
        freeShippingProgress: FreeShippingProgress.fromJson(j['freeShippingProgress']),
        reason: j['reason']?.toString(),
      );
}

/// 估运费的族键（record 天生结构相等）。[address] 与 [region] 至多一个非空；都空 = 无收货地。
typedef ShippingEstimateQuery = ({String product, String sku, String? address, int? region});
