/// 账号举报类型（V1.1.4 Story 2.2，与后端 `AccountReportReason` 对齐，UPPER_SNAKE 线格式）。用户单选。
///
/// ⚠️ **不是** `features/content/domain/report_reason.dart` 那一套。那是**内容维度**五类
/// （ILLEGAL / MISINFO / INAPPROPRIATE / HARASSMENT / OTHER），与这里只有「骚扰」和「其他」对得上。
/// 举报一个**账号**说的是「这个人怎么了」，举报一条**内容**说的是「这条东西怎么了」——
/// 复用会让运营在工单里看到风马牛不相及的理由，后端也会直接 400。
enum AccountReportReason {
  spam('SPAM'),
  impersonation('IMPERSONATION'),
  harassment('HARASSMENT'),
  violatingContent('VIOLATING_CONTENT'),

  /// 其他 —— **只有这一类展开补充说明输入框且必填**（≤200 字）。
  other('OTHER');

  const AccountReportReason(this.wire);

  final String wire;
}
