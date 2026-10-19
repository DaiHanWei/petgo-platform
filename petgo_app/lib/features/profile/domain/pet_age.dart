import '../../../l10n/app_localizations.dart';

/// 宠物年龄（由 birthday 计算，前端计算——记录于 2.4 Completion Notes）。
class PetAge {
  const PetAge(this.years, this.months, [this.days = 0]);

  final int years;
  final int months;

  /// 出生至今的**总天数**。只在「不满 1 个月」时用于文案（见 [formatPetAge]）——
  /// 满月后以年 + 月表达，天数不再有意义。
  final int days;

  /// 年月都为 0（不满 1 个月）。刚建档的幼宠会落在这里。
  bool get isUnderOneMonth => years == 0 && months == 0;
}

/// 由生日计算年龄（年 + 月 + 总天数）。纯函数，L0 可测。未来日期或 null 返回 (0,0,0)。
PetAge computePetAge(DateTime? birthday, {DateTime? now}) {
  if (birthday == null) return const PetAge(0, 0);
  final ref = now ?? DateTime.now();
  if (!birthday.isBefore(ref)) return const PetAge(0, 0);
  var years = ref.year - birthday.year;
  var months = ref.month - birthday.month;
  if (ref.day < birthday.day) months -= 1;
  if (months < 0) {
    years -= 1;
    months += 12;
  }
  if (years < 0) return const PetAge(0, 0);
  final days = DateTime(ref.year, ref.month, ref.day)
      .difference(DateTime(birthday.year, birthday.month, birthday.day))
      .inDays;
  return PetAge(years, months, days);
}

/// 年龄的展示文案（**唯一出口**，档案页 / 我的页 / 名片页共用）。
///
/// 不满 1 个月按**天**表达（「3 hari」）—— 原先一律走年 + 月，刚建档的幼宠会显示
/// 「0th 0bln」（2026-08-04 用户实机反馈）。满月后：0 年只显示月、整年只显示年，否则年 + 月。生日当天（0 天）与无生日一样返回 null，
/// 由调用方整段省略，不留「0 hari」这种空话。
String? formatPetAge(AppLocalizations l10n, DateTime? birthday, {DateTime? now}) {
  if (birthday == null) return null;
  final age = computePetAge(birthday, now: now);
  if (age.isUnderOneMonth) {
    return age.days < 1 ? null : l10n.growthArchiveAgeDays(age.days);
  }
  // 0 年只说月（「3 bln」）、整年只说年（「2 thn」）、其余「1 thn 1 bln」—— UI 稿口径；
  // 原先一律「{y}th {m}bln」，会出现「0th 3bln」（V1.4.0 L2 验收 P1-3）。
  if (age.years == 0) return l10n.petAgeMonthsOnly(age.months);
  if (age.months == 0) return l10n.petAgeYearsOnly(age.years);
  return l10n.growthArchiveAge(age.years, age.months);
}

/// 宠物物种文案（`CAT` / `DOG` / `OTHER`）；未知或 null 返回 null，由调用方整段省略。
String? petSpeciesLabel(AppLocalizations l10n, String? petType) => switch (petType) {
      'CAT' => l10n.petTypeCat,
      'DOG' => l10n.petTypeDog,
      'OTHER' => l10n.petTypeOther,
      _ => null,
    };
