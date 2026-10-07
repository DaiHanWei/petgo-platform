import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/tailsonality/domain/content/ts_match_copy.dart';
import 'package:tailtopia/features/tailsonality/domain/ts_match.dart';

/// V1.3.2 Story 2.5 · AC5：配型计算（纯客户端）。
void main() {
  test('4/4 → 档 Literally Twins、素材档 1、差异句为空', () {
    final m = computeTsMatch('ENTJ', 'ENTJ');
    expect(m.sameCount, 4);
    expect(m.tier, 1);
    expect(m.diffLineKeys, isEmpty);
    expect(kTsMatchTiers[m.sameCount]!.name.en, 'Literally Twins');
  });

  test('只 N/S 不同 → 一条差异句，键 = 主人字母 + 宠物字母', () {
    expect(computeTsMatch('ENTJ', 'ESTJ').diffLineKeys, ['NS']);
    expect(computeTsMatch('ESTJ', 'ENTJ').diffLineKeys, ['SN']);
  });

  test('全不同 → 取 E/I 与 T/F 两条（优先级 E/I > T/F > J/P > N/S）', () {
    final m = computeTsMatch('ENTJ', 'ISFP');
    expect(m.sameCount, 0);
    expect(m.tier, 5);
    expect(m.diffLineKeys, ['EI', 'TF']);
    expect(kTsMatchTiers[0]!.name.en, 'Magnetic Poles');
  });

  test('只 J/P 与 N/S 不同 → J/P 在前', () {
    expect(computeTsMatch('ENTJ', 'ESTP').diffLineKeys, ['JP', 'NS']);
  });

  test('ENTJ vs INFP → sameCount 1', () {
    expect(computeTsMatch('ENTJ', 'INFP').sameCount, 1);
  });

  test('逐轴详解键恒为 EI/NS/TF/JP 四轴顺序，且都在内容表里', () {
    final m = computeTsMatch('INFP', 'ESTJ');
    expect(m.axisDetailKeys, ['IE', 'NS', 'FT', 'PJ']);
    for (final k in m.axisDetailKeys) {
      expect(kTsAxisDetails.containsKey(k), isTrue, reason: k);
    }
    for (final k in computeTsMatch('ENTJ', 'ISFP').diffLineKeys) {
      expect(kTsAxisDiffLines.containsKey(k), isTrue, reason: k);
    }
  });

  test('带后缀 / 小写 / 长度不对 → ArgumentError', () {
    expect(() => computeTsMatch('ENTJ-H', 'ENTJ'), throwsArgumentError);
    expect(() => computeTsMatch('ENTJ', 'entj'), throwsArgumentError);
    expect(() => computeTsMatch('ENT', 'ENTJ'), throwsArgumentError);
  });
}
