import 'pet_age.dart';

/// 宠物年龄 → **人类年龄当量**（V1.3.0 批次 A · Story 5.2 · FR-65 · AD-A18）。
///
/// **纯客户端**：不建接口、不落库、不持久化。整个文件没有一行 IO ——
/// 这既是 AD-A18 的要求，也让它能被逐格单测。
///
/// ⚠️ **实际年龄（年 / 月）仍走既有的 [computePetAge]**，本文件不另算一遍 ——
/// 档案页、我的页、名片页都在用那一个出口，再写一份迟早出现「卡上 2 岁 3 个月、
/// 档案页 2 岁 2 个月」。

/// 狗的体型档（AD-A18.2）。
///
/// 🔴 **不落档案字段、不持久化、不校验** —— 只在本次生成中有效。
/// 用户这次选中型、下次选大型是他自己的事；档案里不该因此多出一个说不清的字段。
enum DogSizeClass {
  small('small', null, 9),
  medium('medium', 9, 23),
  large('large', 23, 41),
  xlarge('xlarge', 41, null);

  const DogSizeClass(this.wire, this.minKg, this.maxKg);

  /// 埋点值（`size_class`，AD-A26.4 值域四值）。
  final String wire;

  /// 体重区间下界（kg）；`null` = 无下界（小型 `< 9 kg`）。
  /// 仅用于在选择页标注，**不参与任何判定** —— 判定只看用户选了哪一档。
  final double? minKg;

  /// 上界；`null` = 无上界（超大型 `> 41 kg`）。
  final double? maxKg;

  /// 第 3 年起每年累加的人岁（AVMA / AAHA 三段式分档）。
  double get yearlyAfterTwo => switch (this) {
        DogSizeClass.small => 4,
        DogSizeClass.medium => 5,
        DogSizeClass.large => 6,
        DogSizeClass.xlarge => 7,
      };
}

/// 卡面文案的年龄段（AD-A18.5b）。
///
/// 🔴 **判定用的是卡面上那个当量整数 N，不是实际月龄**。
/// 用同一个数字驱动「显示什么数」和「取哪段文案」，两者永远对得上；
/// 一个按当量、一个按月龄的话，会出现卡上写 ≈40 人岁却配了幼年文案。
///
/// 🔴 **一套分段同时适用猫狗，不按物种分叉** —— 换算规则已经把物种与体型差异
/// 吸收进 N 了：一条大型犬 5 岁和一只猫 8 岁落在同一段，卡上说的话也该是同一类。
enum PetAgeStage {
  /// N ≤ 19
  puppy,

  /// 20 ≤ N ≤ 39
  young,

  /// 40 ≤ N ≤ 59
  middle,

  /// N ≥ 60
  senior;

  static PetAgeStage fromHumanAge(int n) {
    if (n <= 19) return PetAgeStage.puppy;
    if (n <= 39) return PetAgeStage.young;
    if (n <= 59) return PetAgeStage.middle;
    return PetAgeStage.senior;
  }
}

/// 一次换算的结果。
class HumanAgeResult {
  const HumanAgeResult({
    required this.months,
    required this.humanAge,
    required this.stage,
  });

  /// 实际年龄（整月数）。卡面上的「2 岁 3 个月」由它来。
  final int months;

  int get years => months ~/ 12;
  int get monthsPart => months % 12;

  /// 人类年龄当量，**四舍五入后的整数**。卡面主视觉就是这个数（「≈ N 人岁」）。
  final int humanAge;

  /// 文案段。由 [humanAge] 决定，**不是**由 [months] 决定。
  final PetAgeStage stage;
}

/// 人类年龄当量（AD-A18.1）。
///
/// - **猫**：首年 = 15，次年 +9（满 2 岁 = 24），之后每年 +4
/// - **狗**：首年 = 15，次年 +9，第 3 年起按体型每年 +4 / +5 / +6 / +7
/// - 不足整年**按月线性插值**；未满 1 岁按月龄比例（6 个月的猫 = 7.5 → ≈ 8）
///
/// ⚠️ 采用 AVMA / AAHA / AAFP 的三段式分档，**不采用** UCSD 对数公式
/// （`16×ln(年龄)+31`：只基于 104 只拉布拉多，且 1 岁 = 31 人岁反直觉，
/// 做社交分享会引发「算错了」）。**不要自行改口径。**
double humanAgeEquivalent({
  required int months,
  required bool isDog,
  DogSizeClass? size,
}) {
  assert(!isDog || size != null, '狗必须先选体型档，否则第 3 年起的斜率无从谈起');
  final double years = months / 12;
  if (years <= 1) return 15 * years; // 未满 1 岁：按月龄比例
  if (years <= 2) return 15 + 9 * (years - 1);
  final double yearly = isDog ? (size ?? DogSizeClass.medium).yearlyAfterTwo : 4;
  return 24 + yearly * (years - 2);
}

/// 一步算完：实际年龄 → 当量 → 分段。
///
/// 🔴 [today] 取**设备本地时区的今天**（AD-A28.1）：这是娱乐工具、不涉资金，
/// 用户看到的应当是他自己日历上的今天。
/// ⚠️ **与 Story 5.3 的 WIB 不是一回事，不得互相套用** —— 那边是资金口径（奖励日界），
/// 这边是展示口径。本批次只有这两处时区判定，各自只此一处实现。
///
/// [birthday] 是**建档必填的完整日期**，因此没有缺失兜底分支（AD-A18.3）。
HumanAgeResult resolveHumanAge({
  required DateTime birthday,
  required DateTime today,
  required bool isDog,
  DogSizeClass? size,
}) {
  // 实际年龄复用既有出口，不另算一遍。
  final age = computePetAge(birthday, now: today);
  final months = age.years * 12 + age.months;
  final n = humanAgeEquivalent(months: months, isDog: isDog, size: size).round();
  return HumanAgeResult(months: months, humanAge: n, stage: PetAgeStage.fromHumanAge(n));
}
