import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// v1.4.2 shop Story 9.4 AC5 / AC6.6（PRD §6 末行、FR-136）：复购下线后，
/// 编辑宠物档案的体重说明不再承诺「估算吃完时间」—— 只改值不改 key。
void main() {
  test('petProfileWeightHint 新值（id / en），不再提「吃完」', () async {
    final id = await AppLocalizations.delegate.load(const Locale('id'));
    final en = await AppLocalizations.delegate.load(const Locale('en'));
    expect(id.petProfileWeightHint, 'Opsional. Dipakai untuk merekomendasikan produk yang pas.');
    expect(en.petProfileWeightHint, 'Optional. Used to recommend the right products.');
    expect(id.petProfileWeightHint, isNot(contains('habis')));
    expect(en.petProfileWeightHint, isNot(contains('runs out')));
  });

  test('编辑页仍引用同一个 key', () {
    final src =
        File('lib/features/profile/presentation/pet_profile_edit_page.dart').readAsStringSync();
    expect(src, contains('l10n.petProfileWeightHint'));
  });
}
