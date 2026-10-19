import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/pet_slot/domain/default_renew_selection.dart';

import 'pet_slot_fakes.dart';

/// V1.4.0 multi-pet Story 4.4 · AC3（L0）：续租抽屉默认勾选 = min(月租数, renewableMax, max(0, 正常宠物 − 永久名额))，
/// 从最早到期的开始勾。
void main() {
  final early = DateTime.utc(2026, 10, 16);
  final mid = DateTime.utc(2026, 10, 31);
  final late = DateTime.utc(2026, 11, 20);

  List<String> pick({
    required int petCount,
    int levelSlots = 0,
    int readOnly = 0,
    int renewableMax = 4,
    int defaultSlots = 1,
    List<DateTime>? expiries,
  }) =>
      defaultRenewSelection(
        rentals: [
          for (final (i, e) in (expiries ?? [mid, early]).indexed) rental(i + 1, e),
        ],
        renewableMax: renewableMax,
        defaultSlots: defaultSlots,
        levelSlots: levelSlots,
        petCount: petCount,
        readOnlyCount: readOnly,
      );

  test('默认 1 + 等级 1 + 月租 2、正常宠物 3 → 默认勾 1 个（最早到期那个）', () {
    expect(pick(petCount: 3, levelSlots: 1), ['r2']);
  });

  test('无等级名额 → 勾 2 个（按「Slot sewa」原顺序返回）', () {
    expect(pick(petCount: 3), ['r1', 'r2']);
  });

  test('正常宠物 ≤ 永久名额 → 勾 0 个', () {
    expect(pick(petCount: 2, levelSlots: 1), isEmpty);
    expect(pick(petCount: 1), isEmpty);
    expect(pick(petCount: 0), isEmpty);
  });

  test('只读宠物不计入正常宠物', () {
    expect(pick(petCount: 4, levelSlots: 1, readOnly: 1), ['r2']);
  });

  test('不超过 renewableMax 与生效中月租数', () {
    expect(pick(petCount: 5, renewableMax: 1), ['r2']);
    expect(pick(petCount: 5, expiries: [late, mid, early]), ['r1', 'r2', 'r3']);
    expect(pick(petCount: 3, expiries: [late, mid, early]), ['r2', 'r3'], reason: '跳过最晚到期的');
    expect(pick(petCount: 5, renewableMax: 0), isEmpty);
  });

  test('没有月租 → 空', () {
    expect(pick(petCount: 3, expiries: []), isEmpty);
  });

  test('同一到期时刻保持原顺序', () {
    expect(pick(petCount: 2, expiries: [mid, mid]), ['r1']);
  });
}
