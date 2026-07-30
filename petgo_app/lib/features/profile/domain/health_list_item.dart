import 'package:flutter/foundation.dart';

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
      eventDate: json['eventDate'] == null
          ? null
          : DateTime.tryParse(json['eventDate'] as String),
    );
  }
}

/// 结构化健康记录写入载荷（新增/编辑，Story 7.2）。type 为 code；日期 yyyy-MM-dd。
@immutable
class HealthRecordDraft {
  const HealthRecordDraft({
    required this.type,
    required this.eventDate,
    this.customName,
    this.vaccineName,
    this.note,
  });

  final String type;
  final DateTime eventDate;
  final String? customName;
  final String? vaccineName;
  final String? note;

  Map<String, dynamic> toJson() {
    final d = eventDate;
    final iso = '${d.year.toString().padLeft(4, '0')}-'
        '${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
    return {
      'type': type,
      'eventDate': iso,
      if (customName != null && customName!.isNotEmpty) 'customName': customName,
      if (vaccineName != null && vaccineName!.isNotEmpty) 'vaccineName': vaccineName,
      if (note != null && note!.isNotEmpty) 'note': note,
    };
  }
}
