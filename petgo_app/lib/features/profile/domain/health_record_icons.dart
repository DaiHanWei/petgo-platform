import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';

/// 健康记录类型的**图标总表**（PRD V1.1.2 FR-84 图标总表，2026-07-29 定稿 / OQ-11 已关闭）。
///
/// ⚠️ **全项目只保留这一份**。日历格子（FR-84）、健康记录列表页（FR-45B）、Diary 时间线类 ④ 胶囊
/// （FR-82）三处**共用本表，不新设第二套**；任一处需要调整就改这里，改完三处同步生效。
///
/// 取值与后端 `HealthRecordType` / 健康记录列表 `type` 字段一致（`CONSULT` 为问诊存档，非结构化录入）。
///
/// 现状基线：本表建立时逐条抄自 `health_list_page.dart` 的既有分类卡定义，**图标与配色一个没动**。
/// 已知待办（不属本表建立范围）：
/// - FR-84 要求月经改为 `water_drop`（实心）+ 红色，本表暂留现状 `water_drop_outlined` + infoBlue，
///   由 **Story 3.4** 连同日历接入一并改（色值 OQ-11B 未定，提前改会返工）。
class HealthRecordIcon {
  const HealthRecordIcon({required this.icon, required this.color});

  final IconData icon;

  /// 主色（图标着色 + 浅底 tint 的取色依据）。
  final Color color;
}

/// 类型 → 图标 / 主色。未知类型走 [healthRecordIconFor] 的兜底。
const Map<String, HealthRecordIcon> kHealthRecordIcons = {
  'VACCINE': HealthRecordIcon(icon: Icons.vaccines_outlined, color: AppColors.coral),
  'DEWORM': HealthRecordIcon(icon: Icons.medication_outlined, color: AppColors.triageGreen),
  'NEUTER': HealthRecordIcon(icon: Icons.healing_outlined, color: AppColors.mint),
  // FR-84 目标态为实心水滴 + 红色；见类文档「已知待办」，改动归 Story 3.4。
  'MENSTRUATION': HealthRecordIcon(icon: Icons.water_drop_outlined, color: AppColors.infoBlue),
  'CUSTOM': HealthRecordIcon(icon: Icons.description_outlined, color: AppColors.muted),
  'CONSULT': HealthRecordIcon(icon: Icons.local_hospital_outlined, color: AppColors.coral),
};

/// 通用健康图标（FR-84：当天多条结构化记录时的日历格子标记）。
///
/// ⚠️ **不可用 💊 药丸**——驱虫已占用 `medication_outlined`，会直接撞车（PRD 明确排除项）。
const HealthRecordIcon kHealthRecordGenericIcon =
    HealthRecordIcon(icon: Icons.medical_services_outlined, color: AppColors.coral);

/// 按类型取图标；未知类型（后端新增了前端还不认识的类型）回退到「自定义」，**绝不回退到问诊 🏥**
/// ——错显医院图标是 FR-84 点名要修的现网缺陷类型。
HealthRecordIcon healthRecordIconFor(String? type) =>
    kHealthRecordIcons[type] ?? kHealthRecordIcons['CUSTOM']!;

/// 类型的本地化标签（与健康记录列表页分类卡同一套文案 key，不另起文案）。
String healthRecordLabel(AppLocalizations l10n, String? type) => switch (type) {
      'VACCINE' => l10n.healthTypeVaccine,
      'DEWORM' => l10n.healthTypeDeworm,
      'NEUTER' => l10n.healthTypeNeuter,
      'MENSTRUATION' => l10n.healthTypeMenstruation,
      'CONSULT' => l10n.healthTypeConsult,
      _ => l10n.healthTypeCustom,
    };
