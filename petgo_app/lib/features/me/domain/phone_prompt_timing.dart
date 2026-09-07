/// 手机号软引导的时机判定（Story 7.2 · FR-70 · 决策 **X-21**）。
///
/// **产品口径：用户第 3 天打开 App 时提示。** 四条落地判定：
/// 1. **第 1 天 = 注册当天** ⇒ 第 3 天 = 注册日 **+2 个自然日**
/// 2. 自然日按 **WIB（`Asia/Jakarta`，UTC+7）** 划界
/// 3. **第 3 天起（含）首次打开**即触发，**错过第 3 天不作废**
/// 4. 已填手机号者永不提示；提示过即用掉（填了或跳过都算）
///
/// 🔴 **这是本项目第一处「时区参与判定」** —— 既有三处 `Asia/Jakarta` 全都只做**展示格式化**。
/// 所以换算**集中在本文件**：散开写两遍，两处迟早不一致，而这种不一致只在跨日的
/// 那几个小时里表现出来，极难发现。
class PhonePromptTiming {
  PhonePromptTiming._();

  /// WIB 相对 UTC 的固定偏移。印尼西部时区**不使用夏令时**，故为常量。
  static const _wibOffset = Duration(hours: 7);

  /// 判定此刻是否应该提示。
  ///
  /// [registeredAt] 来自 `/me` 的注册时间（UTC）。**为 null 时不提示** ——
  /// 算不出"第几天"就别瞎问，这条 FR 的价值全在"在合适的时机问"。
  /// 🔴 不要退化成「首次启动时本地记一个日期」：存量用户注册已久却会被当成新人
  /// 再等两天，重装 App 更是重新计时。
  static bool shouldPrompt({
    required DateTime? registeredAt,
    required DateTime now,
    required bool hasPhone,
    required bool alreadyPrompted,
  }) {
    if (hasPhone) return false; // 已填过 → 永不打扰
    if (alreadyPrompted) return false; // 本设备已用掉这次机会
    if (registeredAt == null) return false; // 算不出第几天 → 不问

    // 🔴 **日历日差，不是小时差。** 注册当天 23:00 与次日 01:00 只相隔 2 小时，
    //    却是两个自然日；用 `(now - registeredAt).inDays` 会把第 3 天推迟将近一天。
    final days = _wibDayNumber(now) - _wibDayNumber(registeredAt);

    // 🔴 `>=` 而不是 `==`：**错过第 3 天不作废**。
    //    写成 `== 2`（只在第 3 天当天提示）即为违规 —— 那天没打开 App 的用户
    //    会永远收不到，与本 FR 要解决的问题恰好相反。
    return days >= 2;
  }

  /// 把某个时刻折算成 **WIB 日历日**的序号（用于求日历日差）。
  ///
  /// ⚠️ 为什么不用 `DateTime.toLocal()`：设备时区不可控（用户可能在别的时区、
  /// 也可能手改了时区），而这条 FR 的"天"是**印尼当地的天**。固定按 WIB 折算。
  static int _wibDayNumber(DateTime instant) {
    final wib = instant.toUtc().add(_wibOffset);
    return DateTime.utc(wib.year, wib.month, wib.day).millisecondsSinceEpoch ~/
        Duration.millisecondsPerDay;
  }
}
