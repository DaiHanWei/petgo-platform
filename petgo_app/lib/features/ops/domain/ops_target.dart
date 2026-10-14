/// 统一跳转目标（V1.3.4 AD-16 · Story 5.2）：运营弹窗（Story 5.4）与运营通知（Story 6.3）共用。
///
/// 🔴 wire 值与后端 `com.tailtopia.ops.target.OpsTargetType` / `OpsAppPage` **逐字一致**
/// （UPPER_SNAKE、区分大小写）。后端只在末尾追加新值；App 收到不认识的值一律当 [OpsTarget.none]。
library;

/// 跳转目标类型（对应后端 `OpsTargetType`）。
enum OpsTargetType {
  none('NONE'),
  product('PRODUCT'),
  category('CATEGORY'),
  appPage('APP_PAGE'),
  external('EXTERNAL');

  const OpsTargetType(this.wire);

  /// 后端枚举字面量。
  final String wire;

  /// 区分大小写；不认识 → null。
  static OpsTargetType? fromWire(Object? raw) {
    if (raw is! String) return null;
    for (final t in OpsTargetType.values) {
      if (t.wire == raw) return t;
    }
    return null;
  }
}

/// `APP_PAGE` 允许的 App 内页面（对应后端 `OpsAppPage`，**只这 5 个**，PRD 补充细则）。
enum OpsAppPage {
  home('HOME'),
  publish('PUBLISH'),
  toko('TOKO'),
  knowYourPet('KNOW_YOUR_PET'),
  health('HEALTH');

  const OpsAppPage(this.wire);

  /// 后端枚举字面量。
  final String wire;

  /// 区分大小写（`home` 非法）；不认识 → null。
  static OpsAppPage? fromWire(String? raw) {
    if (raw == null) return null;
    for (final p in OpsAppPage.values) {
      if (p.wire == raw) return p;
    }
    return null;
  }
}

/// 后端下发的 `{"type": "...", "value": "..."}`。
///
/// 只做**解析**，不判断是否可跳（那是 `resolveOpsTarget` 的职责）。
class OpsTarget {
  const OpsTarget(this.type, this.value);

  final OpsTargetType type;
  final String? value;

  static const OpsTarget none = OpsTarget(OpsTargetType.none, null);

  /// 绝不抛异常：`json == null`、`type` 缺失 / 非五值之一 → [none]；`value` 非字符串 → null。
  factory OpsTarget.fromJson(Map<String, dynamic>? json) {
    if (json == null) return none;
    final type = OpsTargetType.fromWire(json['type']);
    if (type == null) return none;
    final raw = json['value'];
    return OpsTarget(type, raw is String ? raw : null);
  }

  @override
  bool operator ==(Object other) =>
      other is OpsTarget && other.type == type && other.value == value;

  @override
  int get hashCode => Object.hash(type, value);

  @override
  String toString() => 'OpsTarget(${type.wire}, $value)';
}
