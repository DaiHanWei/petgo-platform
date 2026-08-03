/// 成长时间线条目类型（数据来源维度，V1.0.0 既有契约）。
enum TimelineKind { happyMoment, healthEvent, unknown }

/// 时间线条目的**五类视觉分类**标识（V1.1.2 · FR-82 · AD-2）。
///
/// ⚠️ **前后端共同契约**：下列五个 `wire` 字面量即 API 下发的 `itemType` 取值（UPPER_SNAKE）。
/// **Story 3.2 的后端实现必须采纳该词表，不得另立一套取值**；任一侧调整须同步另一侧。
///
/// 约束（AD-2）：
/// 1. 分类由**后端按 PRD 五步优先级判定并下发**，前端只按标识选样式，**不得自行推断分类**；
/// 2. 后端须在**查询时实时计算，严禁落库固化**（否则问诊补存后旧 banner 不会消失）；
/// 3. 判定依据是「这一天有没有对应的健康记录条目」，**不是**里程碑的触发方式字段。
///
/// 与 [TimelineKind] 的关系：`kind` 说的是「这条数据来自哪个源」，`itemType` 说的是
/// 「这条数据长什么样」。两者不可互相替代 —— 同一个 `HAPPY_MOMENT` 源可能是类 ① 也可能是类 ②。
enum TimelineItemType {
  /// 类 ① 普通快乐时刻 → 标准照片卡，**无任何徽章/标记**。
  happyMoment('HAPPY_MOMENT'),

  /// 类 ② 打卡关联型里程碑 → 照片卡 + 右上角金色徽章角标（「内容顺带解锁成就」）。
  happyMomentMilestone('HAPPY_MOMENT_MILESTONE'),

  /// 类 ③ 系统自动型里程碑 → 横向通栏庆祝 banner，无照片，按 S/M/L 配色。
  milestoneBanner('MILESTONE_BANNER'),

  /// 类 ④ 健康 / 问诊类记录 → 胶囊 / 标签式条目（明显小于照片卡）。
  ///
  /// 含两种子形态，由 [TimelineItem.healthRecordType] 区分（A6 稿 ④a / ④b）：
  /// `CONSULT` → 粉底问诊条（沿用现状，带 AI 风险等级徽章）；其余结构化类型 → 蓝调矮胶囊。
  healthRecord('HEALTH_RECORD'),

  /// 类 ⑤ 身份证解锁 → 独立证件卡样式。
  idCardIssued('ID_CARD_ISSUED');

  const TimelineItemType(this.wire);

  /// 线格式字面量（= 后端 `itemType` 取值）。
  final String wire;

  /// 解析线格式；未知 / 缺失返回 null（由 [TimelineItem.resolvedType] 兜底）。
  static TimelineItemType? parse(String? raw) {
    if (raw == null) return null;
    for (final t in values) {
      if (t.wire == raw) return t;
    }
    return null;
  }
}

/// 成长时间线条目（后端 `TimelineItemResponse` 客户端模型）。两类合并倒序。
class TimelineItem {
  const TimelineItem({
    required this.kind,
    required this.date,
    this.eventDate,
    this.postId,
    this.imageUrls = const [],
    this.text,
    this.aiLevel,
    this.symptomSummary,
    this.sourceType,
    this.sourceRef,
    this.itemType,
    this.milestoneCode,
    this.milestoneLevel,
    this.healthRecordType,
    this.idCardSerial,
  });

  final TimelineKind kind;

  /// 发生/创建时刻（createdAt）；兼作游标与健康事件显示日期。
  final DateTime date;

  /// 成长日历事件日期（F9，仅快乐时刻有值）；为空回退 [date]。决定时间线显示与排序位置。
  final DateTime? eventDate;

  // 快乐时刻字段
  final int? postId;
  final List<String> imageUrls;
  final String? text;

  // 健康事件字段
  final String? aiLevel;
  final String? symptomSummary;

  /// 健康事件来源：`AI_TRIAGE` / `VET_CONSULT`（区分 AI 分诊与兽医问诊，bug 20260702-231）。
  final String? sourceType;

  /// 健康事件来源引用（问诊/会话 token，幂等键）；据此深链到对应结果页（bug 20260706-259）。
  /// 后端形如 `<前缀>:<数字 id>`——AI 分诊 `triage:<triageId>`、兽医问诊 `consult:<sessionId>`。
  final String? sourceRef;

  // ===== V1.1.2 五类分类契约（FR-82 · AD-2）。后端 Story 3.2 下发；游客示例用内置常量填同样字段。=====

  /// 五类视觉分类标识（后端下发）。3.2 上线前的后端不带此字段 → 见 [resolvedType]。
  final TimelineItemType? itemType;

  /// 里程碑稳定 code（类 ②/③ 用，如 `C-L2`）。显示文案一律客户端按 locale 出
  /// （`kMilestoneTitles` / `kMilestoneCelebrationCopy`），**后端不下发展示文案**，杜绝中文泄漏。
  final String? milestoneCode;

  /// 里程碑级别线格式 `S` / `M` / `L`（类 ③ banner 的配色与等级角标）。
  final String? milestoneLevel;

  /// 健康记录类型（类 ④ 用）：`VACCINE` / `DEWORM` / `MENSTRUATION` / `NEUTER` / `CUSTOM` / `CONSULT`。
  /// 与 FR-45B 健康记录列表同一套取值；图标与配色取 `kHealthRecordIcons`（FR-84 图标总表，全项目一份）。
  final String? healthRecordType;

  /// 身份证编号（类 ⑤ 用，如 `#00842`）。**可为空**——老档案未申请时后端无编号，此时不渲染编号位。
  final String? idCardSerial;

  /// 实际用于选样式的分类：优先后端下发的 [itemType]，缺失时按 [kind] 兜底。
  ///
  /// ⚠️ 兜底**不是** AD-2 禁止的「前端自行推断分类」，而是 Story 3.2 上线前的过渡：
  /// 老后端只有 `kind` 两值，映射到类 ①/④ 即现网表现。3.2 下发 `itemType` 后此分支自然不再命中。
  TimelineItemType get resolvedType =>
      itemType ??
      (kind == TimelineKind.healthEvent
          ? TimelineItemType.healthRecord
          : TimelineItemType.happyMoment);

  /// 类 ④ 是否为问诊 / AI 健康事件（→ A6 稿 ④a 粉底条）；否则为结构化健康记录（→ ④b 蓝调矮胶囊）。
  bool get isConsultRecord =>
      healthRecordType == 'CONSULT' || (healthRecordType == null && kind == TimelineKind.healthEvent);

  /// 健康事件是否为兽医问诊（否则按 AI 分诊显示）。
  bool get isVetConsult => sourceType == 'VET_CONSULT';

  /// 点击健康事件应跳转的路由（bug 20260706-259）；无 [sourceRef]、未知来源或 id 非法则返回 null（不可点）。
  /// 兽医问诊 → `/consult/conversation/<sessionId>`；AI 分诊 → `/triage/result/<triageId>`。
  /// 两条目标路由都对 id 做 `int.parse`，故须剥掉 `triage:`/`consult:` 前缀、取纯数字段。
  String? get healthEventRoute {
    final ref = sourceRef;
    if (ref == null || ref.isEmpty) return null;
    final id = ref.contains(':') ? ref.substring(ref.lastIndexOf(':') + 1) : ref;
    if (id.isEmpty || int.tryParse(id) == null) return null;
    // from=diary：从 diary 进入，会话页返回应回 diary 而非 /triage(Health) Tab（bug 20260721-336）。
    if (sourceType == 'VET_CONSULT') return '/consult/conversation/$id?from=diary';
    if (sourceType == 'AI_TRIAGE') return '/triage/result/$id';
    return null;
  }

  /// 时间线显示日期：快乐时刻取事件日期（F9），健康事件取发生时刻。
  DateTime get displayDate => eventDate ?? date;

  factory TimelineItem.fromJson(Map<String, dynamic> json) {
    final rawImages = json['imageUrls'];
    final rawEvent = json['eventDate'] as String?;
    return TimelineItem(
      kind: _parseKind(json['kind'] as String?),
      date: DateTime.parse(json['date'] as String),
      eventDate: rawEvent != null ? DateTime.parse(rawEvent) : null,
      postId: json['postId'] as int?,
      imageUrls: rawImages is List ? rawImages.map((e) => e.toString()).toList() : const [],
      text: json['text'] as String?,
      aiLevel: json['aiLevel'] as String?,
      symptomSummary: json['symptomSummary'] as String?,
      sourceType: json['sourceType'] as String?,
      sourceRef: json['sourceRef'] as String?,
      itemType: TimelineItemType.parse(json['itemType'] as String?),
      milestoneCode: json['milestoneCode'] as String?,
      milestoneLevel: json['milestoneLevel'] as String?,
      healthRecordType: json['healthRecordType'] as String?,
      idCardSerial: json['idCardSerial'] as String?,
    );
  }

  static TimelineKind _parseKind(String? raw) {
    switch (raw) {
      case 'HAPPY_MOMENT':
        return TimelineKind.happyMoment;
      case 'HEALTH_EVENT':
        return TimelineKind.healthEvent;
      default:
        return TimelineKind.unknown;
    }
  }
}

/// 时间线分页（游标）。
class TimelinePage {
  const TimelinePage({required this.items, this.nextCursor, this.hasMore = false});

  final List<TimelineItem> items;
  final String? nextCursor;
  final bool hasMore;

  factory TimelinePage.fromJson(Map<String, dynamic> json) {
    final rawItems = json['items'];
    return TimelinePage(
      items: rawItems is List
          ? rawItems.map((e) => TimelineItem.fromJson((e as Map).cast<String, dynamic>())).toList()
          : const [],
      nextCursor: json['nextCursor'] as String?,
      hasMore: (json['hasMore'] ?? false) as bool,
    );
  }
}
