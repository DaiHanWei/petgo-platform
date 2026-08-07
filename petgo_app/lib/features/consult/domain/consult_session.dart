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
    this.closedReason,
    this.interruptedReason,
    this.rated = false,
    this.suspendDeadlineAt,
    this.vetDisplayName,
    this.vetAvatarUrl,
    this.vetOnline,
  });

  final int id;
  final String status; // WAITING | IN_PROGRESS | PENDING_CLOSE | CLOSED | INTERRUPTED | CANCELLED
  final String source;
  final int? vetId;
  final int waitingElapsedSeconds;
  final bool timedOut;
  final bool alreadyActive;
  final String? closedReason; // RATED | UNRATED
  final String? interruptedReason; // VET_BANNED
  // 本次会话是否已评分（后端权威）。已评分则关闭评分入口，避免重复评分被 409。
  // 注意:不能只看 closedReason —— 补评分只清补弹标记、不改 UNRATED。
  final bool rated;
  // Story 3.8（H-5）：非 null = 兽医被封禁、本付费会话挂起中（服务端权威 15min 截止）→ 显逃生入口 + 倒计时。
  final DateTime? suspendDeadlineAt;

  // ── 会话对端（兽医）身份，2026-08-07 补 ──
  //
  // 改前会话页顶栏这三样全是**写死的占位**（`drh. Dewi Santoso` / 首字母 D / 恒亮在线点），
  // 不管谁接单都显示同一个人；而那名字恰好是真实存在的兽医账号，于是看起来像「会话串号」。
  // 现在一律来自后端（仅读路径 `GET /consult-sessions/{id}` 与 `/active` 富化）。
  //
  // ⚠️ 三者都可空：WAITING 尚无兽医、后端富化失败也会降级为 null。
  // 取不到时**必须回落到中性文案**（见 `consultVetFallbackName`），不得再填任何具体人名。
  final String? vetDisplayName;
  final String? vetAvatarUrl;
  final bool? vetOnline;

  /// 顶栏头像的首字母兜底。无名字 → `?`（不再写死 `D`）。
  String get vetInitial => initialOf(vetDisplayName);

  /// 由显示名取头像首字母。**静态**：会话页把名字单独存在 state 里（见该页说明），
  /// 手上不一定有 [ConsultSession] 实例。
  static String initialOf(String? displayName) {
    final n = displayName?.trim() ?? '';
    if (n.isEmpty) return '?';
    // 印尼语兽医名普遍带 `drh.` 前缀（= 医师头衔，不是名字），取首字母要跳过它，
    // 否则满屏都是 D —— 这正是改前那个写死的 `D` 的来源。
    final parts = n.split(RegExp(r'\s+')).where((p) {
      final w = p.replaceAll('.', '').toLowerCase();
      return w.isNotEmpty && w != 'drh' && w != 'dr';
    });
    final first = parts.isEmpty ? n : parts.first;
    // 用 runes 而非 substring(0,1)：后者会把 emoji / 非 BMP 字符劈成半个码元渲染成豆腐块。
    // 本文件是纯 domain，不引 `characters` 包（那会多一条 pubspec 直接依赖）。
    return String.fromCharCode(first.runes.first).toUpperCase();
  }

  bool get isWaiting => status == 'WAITING';
  bool get isInProgress => status == 'IN_PROGRESS';
  bool get isSuspended => suspendDeadlineAt != null && status == 'IN_PROGRESS';

  factory ConsultSession.fromJson(Map<String, dynamic> json) => ConsultSession(
        id: (json['id'] as num).toInt(),
        status: (json['status'] ?? 'WAITING') as String,
        source: (json['source'] ?? 'DIRECT') as String,
        vetId: (json['vetId'] as num?)?.toInt(),
        waitingElapsedSeconds: (json['waitingElapsedSeconds'] as num?)?.toInt() ?? 0,
        timedOut: (json['timedOut'] ?? false) as bool,
        alreadyActive: (json['alreadyActive'] ?? false) as bool,
        closedReason: json['closedReason'] as String?,
        interruptedReason: json['interruptedReason'] as String?,
        rated: (json['rated'] ?? false) as bool,
        suspendDeadlineAt: json['suspendDeadlineAt'] == null
            ? null
            : DateTime.parse(json['suspendDeadlineAt'] as String).toUtc(),
        // 空串按「没有」处理，免得顶栏渲染出一片空白（后端理论上只发 null，防御一层）。
        vetDisplayName: _blankToNull(json['vetDisplayName'] as String?),
        vetAvatarUrl: _blankToNull(json['vetAvatarUrl'] as String?),
        vetOnline: json['vetOnline'] as bool?,
      );

  static String? _blankToNull(String? v) =>
      (v == null || v.trim().isEmpty) ? null : v;
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
