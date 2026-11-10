import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/pill_tab.dart';

import 'followed_pet_harness.dart';

/// V1.4.2 social-topic · Story 1.5 · AC1（UI 稿 A5）：关注列表 Mengikuti Tab 的 Pengguna / Hewan 筛选。
void main() {
  late AppLocalizations l10n;
  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('id'));
  });

  testWidgets('Mengikuti Tab 有两个胶囊、默认 Pengguna、无「全部」；数字来自主页 followingCount / petFollowingCount',
      (tester) async {
    final pets = FakeFollowedPetRepo([petPage([followedPet(1)], count: 3)]);
    await pumpFollowList(tester,
        profile: FakeProfileRepo(ownerProfile(following: 5, pets: 3)), pets: pets);

    expect(peopleChip(), findsOneWidget);
    expect(petsChip(), findsOneWidget);
    expect(find.byType(PillTab), findsNWidgets(2));
    expect(chipText(tester, peopleChip()), '${l10n.petFollowFilterPeople} 5');
    expect(chipText(tester, petsChip()), '${l10n.petFollowFilterPets} 3');
    expect(tester.widget<PillTab>(peopleChip()).active, isTrue);
    expect(tester.widget<PillTab>(petsChip()).active, isFalse);
    // 进页默认 Pengguna：Hewan 列表不请求。
    expect(pets.calls, isEmpty);
  });

  testWidgets('Pengikut Tab 无胶囊', (tester) async {
    await pumpFollowList(tester,
        profile: FakeProfileRepo(ownerProfile()), pets: FakeFollowedPetRepo([petPage([])]));
    await tester.tap(find.byKey(const ValueKey('followListTabFollowers')));
    await tester.pumpAndSettle();

    expect(find.byType(PillTab), findsNothing);
  });

  testWidgets('切 Hewan 后再切回 Pengguna 再切 Hewan：都不重新请求', (tester) async {
    final people = EmptyFollowListRepo();
    final pets = FakeFollowedPetRepo([petPage([followedPet(1)], count: 1)]);
    await pumpFollowList(tester, profile: FakeProfileRepo(ownerProfile()), pets: pets, people: people);
    expect(people.calls, hasLength(1));

    await openPets(tester);
    expect(tester.widget<PillTab>(petsChip()).active, isTrue);
    expect(find.byKey(const ValueKey('followedPetRow_1')), findsOneWidget);
    expect(pets.calls, hasLength(1));

    await tester.tap(peopleChip());
    await tester.pumpAndSettle();
    await openPets(tester);

    expect(people.calls, hasLength(1));
    expect(pets.calls, hasLength(1));
    expect(find.byKey(const ValueKey('followedPetRow_1')), findsOneWidget);
  });

  testWidgets('旧后端缺 petFollowingCount → Hewan 胶囊不带数字', (tester) async {
    await pumpFollowList(tester,
        profile: FakeProfileRepo(ownerProfile(pets: null)), pets: FakeFollowedPetRepo([petPage([])]));
    expect(chipText(tester, petsChip()), l10n.petFollowFilterPets);
  });

  testWidgets('胶囊外观照 Discover：选中品牌紫底、圆角 999、内边距 13 / 7、12 / w600', (tester) async {
    await pumpFollowList(tester,
        profile: FakeProfileRepo(ownerProfile()), pets: FakeFollowedPetRepo([petPage([])]));
    final box = tester.widget<Container>(find.descendant(of: peopleChip(), matching: find.byType(Container)).first);
    final deco = box.decoration! as BoxDecoration;
    expect(deco.color, AppColors.mint);
    expect(deco.borderRadius, BorderRadius.circular(999));
    expect(box.padding, const EdgeInsets.symmetric(horizontal: 13, vertical: 7));
    final label = tester.widget<Text>(find.text(l10n.petFollowFilterPeople));
    expect(label.style!.fontSize, 12);
    expect(label.style!.fontWeight, FontWeight.w600);
  });
}
