/// 推送分类开关（V1.4.0 ④ Story 1.3 · AD-4）。服务端按账号存，App 只认 code。
///
/// 分类名称与说明归 App（arb），不渲染后端显示串。
abstract final class PushCategoryCode {
  static const like = 'LIKE';
  static const comment = 'COMMENT';
  static const mention = 'MENTION';
  static const follower = 'FOLLOWER';
  static const gift = 'GIFT';
  static const petReminder = 'PET_REMINDER';
  static const achievement = 'ACHIEVEMENT';
  static const promo = 'PROMO';

  /// 订单与账号：恒开、不可关（App 侧也锁定，永不发 PATCH）。
  static const orderAccount = 'ORDER_ACCOUNT';

  /// 顺序 = 后端 `PushCategory` 声明顺序（AD-1）。
  static const all = <String>[
    like, comment, mention, follower, gift, petReminder, achievement, promo, orderAccount,
  ];

  /// App 侧锁定（双保险：服务端 `lockable` 缺失或给错也照锁）。
  static const alwaysLocked = <String>{orderAccount};
}

/// 当前账号的推送分类设置（不可变）。
class PushSettings {
  const PushSettings({required this.enabled, required this.locked});

  /// code → 是否开启（只含 [PushCategoryCode.all] 里的已知 code）。
  final Map<String, bool> enabled;

  /// 不可关的 code。
  final Set<String> locked;

  bool isEnabled(String code) => enabled[code] ?? true;

  bool isLocked(String code) => locked.contains(code);

  /// `{categories:[{code, enabled, lockable}]}`：未知 code 忽略；已知 code 缺失 → 视为开启（服务端「无行即开启」）。
  factory PushSettings.fromJson(Map<String, dynamic>? json) {
    final enabled = <String, bool>{for (final c in PushCategoryCode.all) c: true};
    final locked = <String>{...PushCategoryCode.alwaysLocked};
    final list = json?['categories'];
    if (list is List) {
      for (final item in list) {
        if (item is! Map) continue;
        final code = item['code'];
        if (code is! String || !enabled.containsKey(code)) continue;
        final on = item['enabled'];
        if (on is bool) enabled[code] = on;
        if (item['lockable'] == false) locked.add(code);
      }
    }
    // 锁定的分类恒开（PRD AC3），不信任服务端给的 enabled。
    for (final c in locked) {
      enabled[c] = true;
    }
    return PushSettings(enabled: Map.unmodifiable(enabled), locked: Set.unmodifiable(locked));
  }

  /// 只改一项。
  PushSettings copyWith(String code, bool value) =>
      PushSettings(enabled: Map.unmodifiable({...enabled, code: value}), locked: locked);
}
