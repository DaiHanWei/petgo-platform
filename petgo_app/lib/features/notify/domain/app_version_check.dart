/// App 版本更新判定（Story 6.5，FR-22C）。纯逻辑，L0 可全覆盖。
///
/// 此提醒为 **App 内提示**，不走系统推送、不需推送权限。
library;

/// 当前构建版本（V1 经 `--dart-define=APP_VERSION` 注入；缺省 1.0.0）。
/// 说明：未引入 package_info_plus 原生插件以免动摇前端 L0；可后续切真机包信息。
const String kAppVersion = String.fromEnvironment('APP_VERSION', defaultValue: '1.0.0');

/// 更新态三分支。
enum UpdateDecision {
  /// 当前 ≥ latest：无更新。
  none,

  /// minSupported ≤ 当前 < latest：推荐更新（可稍后）。
  recommended,

  /// 当前 < minSupported：强制更新（不可跳过）。
  forced,
}

class AppVersionCheck {
  AppVersionCheck._();

  /// 语义版本比较：a<b → -1，a==b → 0，a>b → 1。按点分段数值比较，缺位补 0，忽略非数字预发后缀。
  static int compareSemver(String a, String b) {
    final pa = _parse(a);
    final pb = _parse(b);
    final len = pa.length > pb.length ? pa.length : pb.length;
    for (var i = 0; i < len; i++) {
      final x = i < pa.length ? pa[i] : 0;
      final y = i < pb.length ? pb[i] : 0;
      if (x != y) return x < y ? -1 : 1;
    }
    return 0;
  }

  static List<int> _parse(String v) {
    // 取主版本段（去掉 -beta/+build 等后缀），各段非数字归 0。
    final core = v.split(RegExp(r'[-+]')).first;
    return core.split('.').map((s) {
      final n = int.tryParse(s.trim());
      return n ?? 0;
    }).toList();
  }

  /// 三态判定：current < minSupported ⇒ forced；minSupported ≤ current < latest ⇒ recommended；否则 none。
  static UpdateDecision decide({
    required String current,
    required String latest,
    required String minSupported,
  }) {
    if (compareSemver(current, minSupported) < 0) return UpdateDecision.forced;
    if (compareSemver(current, latest) < 0) return UpdateDecision.recommended;
    return UpdateDecision.none;
  }

  /// 平台商店 URL：iOS → App Store / Android → Google Play。
  static String? storeUrl({required bool isIos, String? iosStoreUrl, String? androidStoreUrl}) {
    final url = isIos ? iosStoreUrl : androidStoreUrl;
    return (url == null || url.isEmpty) ? null : url;
  }
}
