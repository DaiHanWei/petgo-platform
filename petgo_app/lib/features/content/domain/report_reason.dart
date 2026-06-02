/// 举报类型（Story 3.7，与后端 `ReportReason` 对齐，UPPER_SNAKE 线格式）。用户单选。
enum ReportReason {
  illegal('ILLEGAL'),
  misinfo('MISINFO'),
  inappropriate('INAPPROPRIATE'),
  harassment('HARASSMENT'),
  other('OTHER');

  const ReportReason(this.wire);

  final String wire;
}
