// 兽医咨询计费流请求模型（Story 3.5）。对应后端 consult_requests（付费前临时态，A-5 两表）。
// 手写 fromJson（无 freezed，照 consult_session.dart 惯例）。

/// 请求状态：入队待接单 → 接单待支付。取消/超时物理删（无终态枚举）。
enum ConsultRequestState { queueing, acceptedAwaitPay, unknown }

ConsultRequestState _parseState(String? s) => switch (s) {
      'QUEUEING' => ConsultRequestState.queueing,
      'ACCEPTED_AWAIT_PAY' => ConsultRequestState.acceptedAwaitPay,
      _ => ConsultRequestState.unknown,
    };

DateTime? _parseInstant(dynamic v) =>
    v == null ? null : DateTime.tryParse(v as String)?.toLocal();

/// 发起入队响应（`POST /consultations`）：不可枚举请求号 + 当前态 + 入队截止 + 占用命中标记。
class ConsultRequest {
  const ConsultRequest({
    required this.requestToken,
    required this.state,
    this.queueDeadlineAt,
    this.alreadyActive = false,
  });

  final String requestToken;
  final ConsultRequestState state;
  final DateTime? queueDeadlineAt;
  final bool alreadyActive;

  factory ConsultRequest.fromJson(Map<String, dynamic> json) => ConsultRequest(
        requestToken: json['requestToken'] as String,
        state: _parseState(json['state'] as String?),
        queueDeadlineAt: _parseInstant(json['queueDeadlineAt']),
        alreadyActive: json['alreadyActive'] as bool? ?? false,
      );
}

/// 请求状态轮询响应（`GET /consultations/{token}`）：驱动 待接单→待支付 跃迁 + 服务端权威倒计时。
/// 请求已消失（超时删/转单删）→ 端点 404，前端据 404 分流（不在本模型表达）。
class ConsultRequestStatus {
  const ConsultRequestStatus({
    required this.state,
    this.queueDeadlineAt,
    this.payDeadlineAt,
    this.pausedAt,
  });

  final ConsultRequestState state;
  final DateTime? queueDeadlineAt;
  final DateTime? payDeadlineAt;
  final DateTime? pausedAt;

  bool get isPaused => pausedAt != null;

  factory ConsultRequestStatus.fromJson(Map<String, dynamic> json) => ConsultRequestStatus(
        state: _parseState(json['state'] as String?),
        queueDeadlineAt: _parseInstant(json['queueDeadlineAt']),
        payDeadlineAt: _parseInstant(json['payDeadlineAt']),
        pausedAt: _parseInstant(json['pausedAt']),
      );
}
