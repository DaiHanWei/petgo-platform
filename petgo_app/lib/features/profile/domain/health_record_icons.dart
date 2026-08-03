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
/// **Story 3.4 是本表的权威落地方**（PRD FR-84 定稿表）：月经已按 FR-84 改为 `water_drop`（实心）+ 红色，
/// 并补齐两个通用标记（[kHealthRecordGenericIcon] 医疗箱、[kDiaryGenericIcon] 笔记本）。
/// 该改动**同时作用于健康记录列表页与日历格子**（两处共用本表，改一处即同步）。
///
/// ⚠️ **未使用任何字面 emoji**：emoji 无法着色，且老安卓会渲染成豆腐块。
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
  // FR-84：实心水滴 + 红色（覆盖 V1.1.0 FR-45B 的描边水滴 + infoBlue）。
  // ⚠️ 色值待 OQ-11B 定稿（需与 coral #F0425A 拉开距离）；先用 coral 占位，出色值后只改这一行。
  'MENSTRUATION': HealthRecordIcon(icon: Icons.water_drop, color: AppColors.coral),
  'CUSTOM': HealthRecordIcon(icon: Icons.description_outlined, color: AppColors.muted),
  'CONSULT': HealthRecordIcon(icon: Icons.local_hospital_outlined, color: AppColors.coral),
};

/// 通用健康图标（FR-84：当天多条结构化记录时的日历格子标记）。
///
/// ⚠️ **不可用 💊 药丸**——驱虫已占用 `medication_outlined`，会直接撞车（PRD 明确排除项）。
const HealthRecordIcon kHealthRecordGenericIcon =
    HealthRecordIcon(icon: Icons.medical_services_outlined, color: AppColors.coral);

/// 通用 diary 标记（FR-84 新增）：当天**只有纯文字日记**（无图）时的日历格子标记。
///
/// ⚠️ **这是在修一处现网缺陷**：后端允许「图片与文字二选一」，纯文字日记真实存在；
/// 旧逻辑只看「有没有首图」，没首图就掉到问诊图标兜底 —— 结果那天明明没有任何健康事件，
/// 格子却显示 🏥。**任何情况下都不得回退到问诊图标**（含图片加载失败时）。
const HealthRecordIcon kDiaryGenericIcon =
    HealthRecordIcon(icon: Icons.edit_note_outlined, color: AppColors.mint);

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
