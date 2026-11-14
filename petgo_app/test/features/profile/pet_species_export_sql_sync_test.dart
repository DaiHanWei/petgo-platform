import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/pet_species.dart';

/// L0 同步守卫：V1.5.0 pet-species Story 3.1 · AC7.2 —— 推送分组导出 SQL 的猫 / 狗 VALUES = App 猫 / 狗清单。
/// 文件不存在直接失败（不许 skip）。
void main() {
  final f = File('../scripts/ops/pet-species/export-push-groups.sql');

  List<String> valuesOf(String sql, String cte) {
    final m = RegExp('$cte\\(name\\) AS \\(\\s*VALUES\\s*(.*?)\\n\\)', dotAll: true).firstMatch(sql);
    expect(m, isNotNull, reason: '$cte 未找到');
    return RegExp(r"^\s*\('((?:[^']|'')*)'\)", multiLine: true)
        .allMatches(m!.group(1)!)
        .map((x) => x.group(1)!.replaceAll("''", "'"))
        .toList();
  }

  test('导出 SQL 存在', () => expect(f.existsSync(), isTrue));

  test('cat_breeds / dog_breeds 与 App 清单相等', () {
    final sql = f.readAsStringSync();
    expect(valuesOf(sql, 'cat_breeds').toSet(), breedsFor(PetSpeciesCode.cat).map((b) => b.label).toSet());
    expect(valuesOf(sql, 'cat_breeds'), hasLength(39));
    expect(valuesOf(sql, 'dog_breeds').toSet(), breedsFor(PetSpeciesCode.dog).map((b) => b.label).toSet());
    expect(valuesOf(sql, 'dog_breeds'), hasLength(43));
  });
}
