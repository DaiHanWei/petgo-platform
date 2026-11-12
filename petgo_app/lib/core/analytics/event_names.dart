/// 自建埋点新增事件名常量（analytics-pipeline Epic 2）。
///
/// 这些名字已在后端事件清单 `petgo-backend/src/main/resources/analytics/event-catalog.yaml`
/// 预登记（Story 1.2），**不得改写法**：生命周期三个是 Segment 写法（带空格、首字母大写），
/// 其余是 snake_case。新增事件的固定三步：清单登记 → 加常量 → 比对测试变绿。
abstract final class AnalyticsEvents {
  /// 登录后关联身份（Story 2.2 使用）。
  static const String userIdentified = 'user_identified';

  /// 页面浏览，取代 PostHog 的 `$screen`，页面名放属性 `screen`（Story 2.4 使用）。
  static const String screenViewed = 'screen_viewed';

  /// 生命周期：新装首启（Story 2.3 使用）。
  static const String applicationInstalled = 'Application Installed';

  /// 生命周期：升级后首启（Story 2.3 使用）。
  static const String applicationUpdated = 'Application Updated';

  /// 生命周期：冷启动 / 回前台（Story 2.3 使用）。
  static const String applicationOpened = 'Application Opened';
}
