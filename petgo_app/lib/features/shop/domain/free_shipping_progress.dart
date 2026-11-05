/// 满额免运进度（v1.4.2 shop · FR-127.3；服务端计价器下发，App 只展示）。
///
/// 结算页（7.1）、商品详情（8.2）、购物车（8.3）共用。
library;

class FreeShippingProgress {
  const FreeShippingProgress({required this.threshold, required this.remaining});

  /// 满额免运门槛（商品实付口径）。
  final int threshold;

  /// 还差多少（≥ 0）。
  final int remaining;

  /// 有门槛且还没达标 → 画「再买 Rp X」一行。
  bool get showRemaining => threshold > 0 && remaining > 0;

  /// 缺键 / 非 Map → null（未配置门槛）。
  static FreeShippingProgress? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final t = raw['threshold'];
    final r = raw['remaining'];
    if (t is! num || r is! num) return null;
    return FreeShippingProgress(threshold: t.toInt(), remaining: r.toInt());
  }
}
