/// 咨询会话模型（对应后端 `ConsultSessionResponse`，Story 5.3）。
class ConsultSession {
  const ConsultSession({
    required this.id,
    required this.status,
    required this.source,
    this.vetId,
    required this.waitingElapsedSeconds,
    required this.timedOut,
    required this.alreadyActive,
  });

  final int id;
  final String status; // WAITING | IN_PROGRESS | PENDING_CLOSE | CLOSED | INTERRUPTED | CANCELLED
  final String source;
  final int? vetId;
  final int waitingElapsedSeconds;
  final bool timedOut;
  final bool alreadyActive;

  bool get isWaiting => status == 'WAITING';
  bool get isInProgress => status == 'IN_PROGRESS';

  factory ConsultSession.fromJson(Map<String, dynamic> json) => ConsultSession(
        id: (json['id'] as num).toInt(),
        status: (json['status'] ?? 'WAITING') as String,
        source: (json['source'] ?? 'DIRECT') as String,
        vetId: (json['vetId'] as num?)?.toInt(),
        waitingElapsedSeconds: (json['waitingElapsedSeconds'] as num?)?.toInt() ?? 0,
        timedOut: (json['timedOut'] ?? false) as bool,
        alreadyActive: (json['alreadyActive'] ?? false) as bool,
      );
}

/// 咨询可用性（对应后端 `ConsultAvailabilityResponse`，Story 5.2/5.3）。
class ConsultAvailability {
  const ConsultAvailability({required this.vetOnline, this.expectedWindow});

  final bool vetOnline;
  final String? expectedWindow;

  factory ConsultAvailability.fromJson(Map<String, dynamic> json) => ConsultAvailability(
        vetOnline: (json['vetOnline'] ?? false) as bool,
        expectedWindow: json['expectedWindow'] as String?,
      );
}
