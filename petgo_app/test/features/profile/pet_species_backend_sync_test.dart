import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/pet_species.dart';

/// L0 同步守卫：V1.5.0 pet-species Story 3.1 · AC7.1 —— App 的 27 个 code 与后端 `PetSpecies` 同名同序。
void main() {
  test('PetSpeciesCode wire == 后端 PetSpecies 常量（含顺序）', () {
    final f = File('../petgo-backend/src/main/java/com/tailtopia/profile/domain/PetSpecies.java');
    expect(f.existsSync(), isTrue, reason: '后端枚举文件不在预期路径');
    final src = f.readAsStringSync();
    final body = src.substring(src.indexOf('public enum PetSpecies {'), src.indexOf(';', src.indexOf('OTHER(')));
    final names = RegExp(r'^\s*([A-Z_]+)\("', multiLine: true).allMatches(body).map((m) => m.group(1)).toList();
    expect(names, PetSpeciesCode.values.map((e) => e.wire).toList());
  });
}
