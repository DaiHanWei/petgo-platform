/// PawCoin 充值领域模型（Story 1.5）。对齐后端 `TopupOptions`/`TopupTierDto`/`TopupResponse`。
library;

/// 充值档位。
class TopupTierOption {
  const TopupTierOption({required this.id, required this.amount, required this.coins});

  final String id; // "10k".."100k"
  final int amount; // IDR
  final int coins; // 到账 koin（1:1）

  factory TopupTierOption.fromJson(Map<String, dynamic> j) => TopupTierOption(
        id: (j['id'] ?? '') as String,
        amount: (j['amount'] as num?)?.toInt() ?? 0,
        coins: (j['coins'] as num?)?.toInt() ?? 0,
      );
}

/// 充值选项：档位 + 是否暂停（浮存门槛，AB-6C）。
class TopupOptions {
  const TopupOptions({required this.tiers, required this.paused});

  final List<TopupTierOption> tiers;
  final bool paused;

  factory TopupOptions.fromJson(Map<String, dynamic> j) => TopupOptions(
        tiers: ((j['tiers'] as List?) ?? const [])
            .map((e) => TopupTierOption.fromJson((e as Map).cast<String, dynamic>()))
            .toList(),
        paused: (j['paused'] ?? false) as bool,
      );
}

/// 下单响应：意图 token（轮询用）+ 渠道 + 付款载荷（QRIS 二维码图 URL）。
class TopupResult {
  const TopupResult({
    required this.intentToken,
    required this.channel,
    required this.amount,
    required this.coins,
    this.payload,
  });

  final String intentToken;
  final String channel; // QRIS
  final int amount;
  final int coins;
  final String? payload;

  factory TopupResult.fromJson(Map<String, dynamic> j) => TopupResult(
        intentToken: (j['intentToken'] ?? '') as String,
        channel: (j['channel'] ?? '') as String,
        amount: (j['amount'] as num?)?.toInt() ?? 0,
        coins: (j['coins'] as num?)?.toInt() ?? 0,
        payload: j['payload'] as String?,
      );
}
