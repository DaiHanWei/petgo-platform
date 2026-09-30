/// 一次性解锁四价（V1.3.2 Story 3.1 / 3.2 · `GET /pet-profiles/me/id-cards/pricing`）。
///
/// 🔴 **无本地兜底价**（D-2）：任一价缺失或 ≤0 即抛 —— 展示价必须与扣款价同源（`pricing_config`），
/// 猜一个价显示给用户比显示「重试」更糟。照 `DioIdCardRepository.hdPrice`。
class KeepsakePricing {
  const KeepsakePricing({
    required this.ktpHd,
    required this.passportSnapshot,
    required this.boardingPass,
    required this.tailsonality,
  });

  /// KTP 高清图（`price`）。
  final int ktpHd;

  /// 护照快照（`passportPageUnlockPrice`）。
  final int passportSnapshot;

  /// 单张登机牌（`passportBoardingUnlockPrice`）。
  final int boardingPass;

  /// Tailsonality 结果（`tailsonalityUnlockPrice`）。
  final int tailsonality;

  factory KeepsakePricing.fromJson(Map<String, dynamic> json) {
    int read(String key) {
      final v = json[key];
      final n = v is num ? v.toInt() : null;
      if (n == null || n <= 0) {
        throw FormatException('invalid keepsake pricing: $key');
      }
      return n;
    }

    return KeepsakePricing(
      ktpHd: read('price'),
      passportSnapshot: read('passportPageUnlockPrice'),
      boardingPass: read('passportBoardingUnlockPrice'),
      tailsonality: read('tailsonalityUnlockPrice'),
    );
  }
}
