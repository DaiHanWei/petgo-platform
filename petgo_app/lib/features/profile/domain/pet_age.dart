/// 宠物年龄（由 birthday 计算，前端计算——记录于 2.4 Completion Notes）。
class PetAge {
  const PetAge(this.years, this.months);

  final int years;
  final int months;
}

/// 由生日计算年龄（年 + 月）。纯函数，L0 可测。未来日期或 null 返回 (0,0)。
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
  return PetAge(years, months);
}
