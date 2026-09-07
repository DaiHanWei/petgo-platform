import '../../../core/analytics/analytics.dart';
import '../../../core/storage/prefs.dart';

/// 推送授权态变化上报（Story 9.3 · 跨切口埋点 · **推送疲劳的终点信号**）。
///
/// **为什么这个信号值得单独埋**：推送疲劳的其他信号都是渐进的（打开率下降、静默增多），
/// 只有「用户去系统设置里把通知关了」是**终点** —— 到这一步就再也没有第二次机会了。
/// 没有这个读数，我们只能看到推送量涨、打开率跌，却说不清有多少人是彻底退出的。
///
/// 🔴 **只能靠自己记**：系统权限从 granted → denied 通常发生在 App 被杀掉之后，
/// 端上唯一能察觉的时机是下一次冷启动 —— 而那时内存里没有任何「之前是开着的」记忆。
/// 所以上一次的授权态必须落盘（[AppPrefs.pushPermissionLastGranted]）。
///
/// ⚠️ **首次记录不算变化**：没有上一次的状态就没有跃迁，此时只写基线、不上报。
/// 把「第一次看到是关的」当成撤销，会让新装用户凭空贡献一堆假撤销。
///
/// 🔒 事件只带受控值（`enabled` 布尔 + `from_screen` 受控字面量），无 PII（NFR-5）。
class PushPermissionChangeReporter {
  const PushPermissionChangeReporter._();

  /// 事件名。
  ///
  /// ⚠️ 与 AC 字面量 `push_permission_revoked` 不同：那个名字过不了埋点命名守卫
  /// （`_revoked` 不在受控动作词表里）。按既有先例改名迁就规则、**不放宽规则**；
  /// 用 `_toggled` + `enabled` 属性表达同一件事，且顺带把「又开回来」也记了下来
  /// （撤销率的分母因此更准）。看板口径映射见 Epic 9 交付说明。
  /// ⚠️ 事件名在 [record] 里**写成字面量**而不是引用这个常量：埋点命名守卫是靠扫源码里的
  /// `Analytics.capture('<字面量>'` 提取事件名的，用常量传参会让这条事件<b>整条绕过</b>
  /// snake_case / 模块前缀 / 动作词尾三道检查。这个常量只给测试引用。
  static const String event = 'notify_push_permission_toggled';

  /// 比对并上报。[granted] 是刚查到的系统权限真值。
  ///
  /// @param fromScreen 受控字面量（如 `app_launch` / `settings_page`），**不得传 UI 文案**
  /// @return 是否上报了一次变化
  static Future<bool> record(bool granted, {required String fromScreen, AppPrefs? prefs}) async {
    try {
      final p = prefs ?? await AppPrefs.create();
      final last = p.pushPermissionLastGranted;
      await p.setPushPermissionLastGranted(granted);
      if (last == null || last == granted) {
        return false;   // 无基线 or 无跃迁 —— 不制造假数据
      }
      await Analytics.capture(
          'notify_push_permission_toggled', {'enabled': granted, 'from_screen': fromScreen});
      return true;
    } catch (_) {
      // 埋点失败绝不能影响功能：拿不到 prefs 就当没这回事
      return false;
    }
  }
}
