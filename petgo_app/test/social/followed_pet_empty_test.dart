import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_recommendation_strip.dart';
import 'package:tailtopia/features/profile/presentation/widgets/recommended_pet_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/pill_tab.dart';

import 'followed_pet_harness.dart';

/// V1.4.2 social-topic · Story 1.5 · AC3.2 / AC4（UI 稿 A8 / A9）：Hewan 空态与推荐宠物。
void main() {
  late AppLocalizations l10n;
  final events = <(String, Map<String, Object>?)>[];
  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('id'));
  });
  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Finder section() => find.byKey(const ValueKey('followedPetRecommend'));

  testWidgets('自己空 → 空态文案（Hewan 0 仍选中）+ 推荐区：标题、卡宽 88、Ikuti 按钮、无「Lihat semua」', (tester) async {
    await pumpFollowList(tester,
        profile: FakeProfileRepo(ownerProfile(pets: 0)),
        pets: FakeFollowedPetRepo([petPage([], count: 0)]),
        recommend: FakeRecommendRepo([recommendedPet(11), recommendedPet(12)]));
    await openPets(tester);

    expect(find.text(l10n.petFollowListEmptySelf), findsOneWidget);
    expect(find.byIcon(Icons.pets), findsOneWidget);
    expect(chipText(tester, petsChip()), '${l10n.petFollowFilterPets} 0');
    expect(tester.widget<PillTab>(petsChip()).active, isTrue);

    expect(section(), findsOneWidget);
    expect(find.descendant(of: section(), matching: find.text(l10n.petRecommendSectionTitle)), findsOneWidget);
    expect(find.text('${l10n.petRecommendSeeAll} ›'), findsNothing);
    final tile = find.byKey(const ValueKey('recommendedPet_11'));
    expect(tester.getSize(tile).width, 88);
    expect(tester.widget<PetStripTile>(find.byType(PetStripTile).first).width, 88);
    final button = find.byKey(const ValueKey('petFollowList_11'));
    expect(tester.widget<Text>(find.descendant(of: button, matching: find.byType(Text))).data,
        l10n.followActionFollow);
    // 热区 44（按钮视觉 30）。
    expect(tester.getSize(find.byKey(const ValueKey('petFollowHit_11'))).height, 44);
    expect(tester.getSize(button).height, 30);
    // 空态标题到推荐区 32。
    final titleBottom = tester.getBottomLeft(find.text(l10n.petFollowListEmptySelf)).dy;
    final recTitle = find.descendant(of: section(), matching: find.text(l10n.petRecommendSectionTitle));
    expect(tester.getTopLeft(recTitle).dy - titleBottom, 32);
    expect(tester.takeException(), isNull);
  });

  testWidgets('推荐为空 → 整块隐藏', (tester) async {
    await pumpFollowList(tester,
        profile: FakeProfileRepo(ownerProfile(pets: 0)),
        pets: FakeFollowedPetRepo([petPage([], count: 0)]),
        recommend: FakeRecommendRepo(const []));
    await openPets(tester);
    expect(find.text(l10n.petFollowListEmptySelf), findsOneWidget);
    expect(section(), findsNothing);
    expect(find.text(l10n.petRecommendSectionTitle), findsNothing);
  });

  testWidgets('推荐失败 → 整块隐藏、不显示错误态', (tester) async {
    await pumpFollowList(tester,
        profile: FakeProfileRepo(ownerProfile(pets: 0)),
        pets: FakeFollowedPetRepo([petPage([], count: 0)]),
        recommend: FakeRecommendRepo([recommendedPet(1)], fail: true));
    await openPets(tester);
    expect(section(), findsNothing);
    expect(find.text(l10n.blockedListLoadFailed), findsNothing);
  });

  testWidgets('别人空 → 只空态「Belum mengikuti hewan apa pun」，无推荐、不请求推荐', (tester) async {
    final recommend = FakeRecommendRepo([recommendedPet(1)]);
    await pumpFollowList(tester,
        profile: FakeProfileRepo(ownerProfile(self: false, pets: 0)),
        pets: FakeFollowedPetRepo([petPage([], count: 0)]),
        recommend: recommend);
    await openPets(tester);
    expect(find.text(l10n.petFollowListEmpty), findsOneWidget);
    expect(section(), findsNothing);
    expect(recommend.calls, 0);
  });

  testWidgets('推荐卡点按钮 = 追更（source=pet_recommendation）、卡不移除、按钮变 Mengikuti；不进访客页', (tester) async {
    final follows = FakePetFollowRepo();
    final h = await pumpFollowList(tester,
        profile: FakeProfileRepo(ownerProfile(pets: 0)),
        pets: FakeFollowedPetRepo([petPage([], count: 0)]),
        petFollows: follows,
        recommend: FakeRecommendRepo([recommendedPet(11)]));
    await openPets(tester);

    await tester.tap(find.byKey(const ValueKey('petFollowList_11')));
    await tester.pumpAndSettle();

    expect(follows.calls, ['POST 11']);
    expect(h.openedPet, isNull);
    expect(find.byKey(const ValueKey('recommendedPet_11')), findsOneWidget);
    final button = find.byKey(const ValueKey('petFollowList_11'));
    expect(tester.widget<Text>(find.descendant(of: button, matching: find.byType(Text))).data,
        l10n.followActionFollowing);
    final e = events.singleWhere((e) => e.$1 == 'pet_follow');
    expect(e.$2!['source'], 'pet_recommendation');
    expect(e.$2!['pet_id'], 11);
    expect(events.where((e) => e.$1 == 'pet_card_tapped'), isEmpty);
    // 空态在下拉刷新前不变。
    expect(find.text(l10n.petFollowListEmptySelf), findsOneWidget);
  });

  testWidgets('点按钮外扩热区（按钮上方 5px）同样追更', (tester) async {
    final follows = FakePetFollowRepo();
    await pumpFollowList(tester,
        profile: FakeProfileRepo(ownerProfile(pets: 0)),
        pets: FakeFollowedPetRepo([petPage([], count: 0)]),
        petFollows: follows,
        recommend: FakeRecommendRepo([recommendedPet(11)]));
    await openPets(tester);
    final rect = tester.getRect(find.byKey(const ValueKey('petFollowList_11')));
    await tester.tapAt(Offset(rect.center.dx, rect.top - 5));
    await tester.pumpAndSettle();
    expect(follows.calls, ['POST 11']);
  });

  testWidgets('点卡片其他区域 = openRecommendedPet(from: follow_list_empty)', (tester) async {
    final h = await pumpFollowList(tester,
        profile: FakeProfileRepo(ownerProfile(pets: 0)),
        pets: FakeFollowedPetRepo([petPage([], count: 0)]),
        recommend: FakeRecommendRepo([recommendedPet(11)]));
    await openPets(tester);

    await tester.tap(find.byKey(const ValueKey('recommendedPet_11')));
    await tester.pumpAndSettle();
    expect(events.where((e) => e.$1 == 'pet_card_tapped').single.$2, {'from': kPetRecommendFromFollowListEmpty});
    expect(kPetRecommendFromFollowListEmpty, 'follow_list_empty');
    expect(h.openedPet, '/pets/11?from=follow_list_empty');
  });
}
