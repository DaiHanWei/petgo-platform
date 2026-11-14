import 'package:flutter/foundation.dart';

/// 记录卡状态（V1.5.0 Story 3.1，PRD FR-158 Record status）。由后端算好下发（`recordStatus`），
/// App 只按它选胶囊样式，**不复制任何日期规则**（架构增量 §7）。未知值 / 缺字段 → [done]。
enum HealthRecordStatus {
  overdue,
  dueSoon,
  upcoming,
  done;

  static HealthRecordStatus parse(String? raw) => switch (raw) {
    'OVERDUE' => HealthRecordStatus.overdue,
    'DUE_SOON' => HealthRecordStatus.dueSoon,
    'UPCOMING' => HealthRecordStatus.upcoming,
    _ => HealthRecordStatus.done,
  };
}

/// 健康时间线混排项（Story 7.2，FR-45B）。两源：结构化记录（[kind]=RECORD，[editable]=true 可编辑）
/// + 问诊存档（[kind]=CONSULT，[editable]=false 只读，🏥 标识）。[type] 为 code，前端本地化。
@immutable
class HealthListItem {
  const HealthListItem({
    required this.kind,
    required this.id,
    required this.editable,
    required this.type,
    this.customName,
    this.vaccineName,
    this.note,
    this.symptomSummary,
    this.aiLevel,
    this.eventDate,
    this.vaccinePreset,
    this.dose,
    this.dewormType,
    this.productDurationMonths,
    this.nextDate,
    this.displayNextDate,
    this.recordStatus = HealthRecordStatus.done,
    this.nextDateLocked = false,
  });

  final String kind; // RECORD | CONSULT
  final int id;
  final bool editable;

  /// RECORD: VACCINE/DEWORM/MENSTRUATION/NEUTER/CUSTOM；CONSULT: "CONSULT"。前端按 code 本地化。
  final String type;
  final String? customName;
  final String? vaccineName;
  final String? note;
  final String? symptomSummary; // CONSULT 只读
  final String? aiLevel; // CONSULT: GREEN/YELLOW/RED
  final DateTime? eventDate;

  // ===== V1.5.0 健康记录提醒 Story 3.1（后端 1.1 / 1.2 列表字段）=====
  /// 疫苗预设 code（F3 / F4 / RABIES / … / OTHER）。
  final String? vaccinePreset;
  final int? dose;

  /// 驱虫类型 code（INTERNAL / EXTERNAL / FLEA_WORM / HEARTWORM）；null = 存量未分型。
  final String? dewormType;
  final int? productDurationMonths;

  /// 存储的 Next date（编辑表单用）。
  final DateTime? nextDate;

  /// 列表要显示的 Next date（后端已按覆盖组 / 回填静默 / 发情周期算好）。
  final DateTime? displayNextDate;
  final HealthRecordStatus recordStatus;

  /// 发情周期后续记录：Next date 只读，跟随本周期第一天。
  final bool nextDateLocked;

  bool get isConsult => kind == 'CONSULT';

  factory HealthListItem.fromJson(Map<String, dynamic> json) {
    return HealthListItem(
      kind: json['kind'] as String? ?? 'RECORD',
      id: (json['id'] as num?)?.toInt() ?? 0,
      editable: json['editable'] as bool? ?? false,
      type: json['type'] as String? ?? 'CUSTOM',
      customName: json['customName'] as String?,
      vaccineName: json['vaccineName'] as String?,
      note: json['note'] as String?,
      symptomSummary: json['symptomSummary'] as String?,
      aiLevel: json['aiLevel'] as String?,
      eventDate: _date(json['eventDate']),
      vaccinePreset: json['vaccinePreset'] as String?,
      dose: (json['dose'] as num?)?.toInt(),
      dewormType: json['dewormType'] as String?,
      productDurationMonths: (json['productDurationMonths'] as num?)?.toInt(),
      nextDate: _date(json['nextDate']),
      displayNextDate: _date(json['displayNextDate']),
      recordStatus: HealthRecordStatus.parse(json['recordStatus'] as String?),
      nextDateLocked: json['nextDateLocked'] as bool? ?? false,
    );
  }

  static DateTime? _date(Object? raw) => raw is String ? DateTime.tryParse(raw) : null;
}

String _iso(DateTime d) => '${d.year.toString().padLeft(4, '0')}-'
    '${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// 结构化健康记录写入载荷（新增/编辑，Story 7.2）。type 为 code；日期 yyyy-MM-dd。
///
/// V1.5.0 Story 3.2（架构增量 §6.1）：追加预设 / 剂次 / 驱虫类型 / 产品时长 / 下次日期 / 显式清空。
/// 只有 1.5.0 客户端（`X-App-Version` ≥ 1.5.0）的请求后端才读这些字段。
@immutable
class HealthRecordDraft {
  const HealthRecordDraft({
    required this.type,
    required this.eventDate,
    this.customName,
    this.vaccineName,
    this.note,
    this.vaccinePreset,
    this.dose,
    this.dewormType,
    this.productDurationMonths,
    this.nextDate,
    this.clearNextDate = false,
  });

  final String type;
  final DateTime eventDate;
  final String? customName;
  final String? vaccineName;
  final String? note;
  final String? vaccinePreset;
  final int? dose;
  final String? dewormType;
  final int? productDurationMonths;
  final DateTime? nextDate;

  /// true = 用户清空过下次日期（后端 `clearNextDate`）。
  final bool clearNextDate;

  Map<String, dynamic> toJson() {
    return {
      'type': type,
      'eventDate': _iso(eventDate),
      if (customName != null && customName!.isNotEmpty) 'customName': customName,
      if (vaccineName != null && vaccineName!.isNotEmpty) 'vaccineName': vaccineName,
      if (note != null && note!.isNotEmpty) 'note': note,
      'vaccinePreset': ?vaccinePreset,
      'dose': ?dose,
      'dewormType': ?dewormType,
      'productDurationMonths': ?productDurationMonths,
      if (nextDate != null) 'nextDate': _iso(nextDate!),
      if (clearNextDate) 'clearNextDate': true,
    };
  }
}

/// 表单预填接口请求（`POST …/health-records/preview`，V1.5.0 Story 3.2 · AC2.3，架构增量 §6.1）。
@immutable
class HealthRecordPreviewRequest {
  const HealthRecordPreviewRequest({
    required this.type,
    this.recordId,
    this.vaccinePreset,
    this.vaccineName,
    this.dose,
    this.dewormType,
    this.productDurationMonths,
    this.eventDate,
  });

  final int? recordId;
  final String type;
  final String? vaccinePreset;
  final String? vaccineName;
  final int? dose;
  final String? dewormType;
  final int? productDurationMonths;
  final DateTime? eventDate;

  Map<String, dynamic> toJson() => {
        'recordId': ?recordId,
        'type': type,
        'vaccinePreset': ?vaccinePreset,
        if (vaccineName != null && vaccineName!.isNotEmpty) 'vaccineName': vaccineName,
        'dose': ?dose,
        'dewormType': ?dewormType,
        'productDurationMonths': ?productDurationMonths,
        if (eventDate != null) 'eventDate': _iso(eventDate!),
      };

  @override
  bool operator ==(Object other) =>
      other is HealthRecordPreviewRequest &&
      other.recordId == recordId &&
      other.type == type &&
      other.vaccinePreset == vaccinePreset &&
      other.vaccineName == vaccineName &&
      other.dose == dose &&
      other.dewormType == dewormType &&
      other.productDurationMonths == productDurationMonths &&
      other.eventDate == eventDate;

  @override
  int get hashCode => Object.hash(
      recordId, type, vaccinePreset, vaccineName, dose, dewormType, productDurationMonths, eventDate);
}

/// 表单预填接口结果（V1.5.0 Story 3.2）。**规则只在后端一份**：App 只显示，不复制任何日期规则。
@immutable
class HealthRecordPreview {
  const HealthRecordPreview({
    this.suggestedNextDate,
    this.nextDateAllowed = true,
    this.nextDateLocked = false,
    this.lockedNextDate,
    this.nextDateMin,
    this.nextDateMax,
    this.dueVaccinePreset,
    this.dueDose,
    this.hints = const [],
  });

  final DateTime? suggestedNextDate;
  final bool nextDateAllowed;
  final bool nextDateLocked;
  final DateTime? lockedNextDate;
  final DateTime? nextDateMin;
  final DateTime? nextDateMax;
  final String? dueVaccinePreset;
  final int? dueDose;

  /// 提示 code（`VACCINE_PREGNANT` / `RABIES_YEARLY` / `DEWORM_UNDER_8_WEEKS` / `DEWORM_PREGNANT` /
  /// `PERMETHRIN` / `HEARTWORM_TEST` / `HEAT_NEUTERED`），顺序由后端定；不认识的 code 由 UI 忽略。
  final List<String> hints;

  factory HealthRecordPreview.fromJson(Map<String, dynamic> json) => HealthRecordPreview(
        suggestedNextDate: HealthListItem._date(json['suggestedNextDate']),
        nextDateAllowed: json['nextDateAllowed'] as bool? ?? true,
        nextDateLocked: json['nextDateLocked'] as bool? ?? false,
        lockedNextDate: HealthListItem._date(json['lockedNextDate']),
        nextDateMin: HealthListItem._date(json['nextDateMin']),
        nextDateMax: HealthListItem._date(json['nextDateMax']),
        dueVaccinePreset: json['dueVaccinePreset'] as String?,
        dueDose: (json['dueDose'] as num?)?.toInt(),
        hints: [for (final h in (json['hints'] as List<dynamic>? ?? const [])) if (h is String) h],
      );
}

/// 从健康提醒进入健康记录页时带来的表单预填（V1.5.0 Story 3.1 · AC5，路由 `/profile/health` 的 query）。
/// 本页只接住并原样交给表单；各字段如何落到表单上归 Story 3.2。
class HealthRecordPrefill {
  const HealthRecordPrefill({
    this.vaccinePreset,
    this.dose,
    this.dewormType,
    this.customName,
    this.dateEmpty = false,
  });

  final String? vaccinePreset;
  final int? dose;
  final String? dewormType;
  final String? customName;

  /// true = 事件日期留空让用户填（提醒里「还不知道哪天打的」场景）。
  final bool dateEmpty;

  bool get isEmpty =>
      vaccinePreset == null && dose == null && dewormType == null && customName == null && !dateEmpty;
}
