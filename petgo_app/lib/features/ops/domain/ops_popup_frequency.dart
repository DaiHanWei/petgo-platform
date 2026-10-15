import 'ops_popup.dart';

/// 本地频次（V1.3.4 Story 5.4 · AC3 · AD-15）。纯函数，L0 可测。

/// 本地频次记录里 ONCE 的取值。
const String kOpsPopupOnceValue = 'once';

/// WIB（UTC+7）自然日 `yyyy-MM-dd`。🔴 **不用手机本地时区**（PRD 补充细则）。
String wibDateOf(DateTime now) {
  final d = now.toUtc().add(const Duration(hours: 7));
  String two(int n) => n.toString().padLeft(2, '0');
  return '${d.year.toString().padLeft(4, '0')}-${two(d.month)}-${two(d.day)}';
}

/// 该候选在本地是否已满频次（AC3.3）。
bool isOpsPopupCapped(OpsPopupCandidate c, Map<String, String> shown, String todayWib) {
  final v = shown[c.token];
  if (v == null) return false;
  return switch (c.frequency) {
    // ONCE：有任何记录即剔除（含游客期间写下的，PRD AC4）。
    OpsPopupFrequency.once => true,
    // DAILY：今天（WIB）弹过才剔除；`once` / 昨天 → 不剔除。
    OpsPopupFrequency.daily => v == todayWib,
  };
}

/// 剔除本地频次已满的，按服务端顺序取**第一个**；全被剔除 → null。
OpsPopupCandidate? pickOpsPopup(
  List<OpsPopupCandidate> candidates,
  Map<String, String> shown,
  String todayWib,
) {
  for (final c in candidates) {
    if (!isOpsPopupCapped(c, shown, todayWib)) return c;
  }
  return null;
}

/// 显示后写入本地的值：ONCE → `once`，DAILY → 当天 WIB 日期（AC3.4）。
String opsPopupShownValue(OpsPopupFrequency f, DateTime now) =>
    f == OpsPopupFrequency.once ? kOpsPopupOnceValue : wibDateOf(now);
