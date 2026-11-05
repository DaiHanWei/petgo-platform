import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/social/domain/followed_pet.dart';
import 'package:tailtopia/features/social/presentation/blocked_list_skeleton.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/social/data/followed_pet_repository.dart';
import 'package:tailtopia/features/social/data/pet_follow_repository.dart';
import 'package:tailtopia/features/social/presentation/followed_pet_list_controller.dart';
import 'package:tailtopia/features/social/presentation/widgets/pet_follow_button.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_repository.dart';

import 'followed_pet_harness.dart';

/// V1.4.2 social-topic · Story 1.5 · AC2 / AC3 / AC9（UI 稿 A6 / A7 / A9）：Hewan 列表行、取消追更、分页、失败态。
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

  Finder btn(int id) => find.byKey(ValueKey('petFollowList_$id'));
  String label(WidgetTester tester, int id) =>
      tester.widget<Text>(find.descendant(of: btn(id), matching: find.byType(Text))).data!;

  testWidgets('自己的列表行：名字 /「Kucing · milik @X」/ 常驻 Mengikuti；头像与按钮垂直居中', (tester) async {
    await pumpFollowList(tester,
        profile: FakeProfileRepo(ownerProfile()),
        pets: FakeFollowedPetRepo([petPage([followedPet(1), followedPet(2, type: 'DOG')], count: 2)]));
    await openPets(tester);

    expect(find.text('Mochi1'), findsOneWidget);
    expect(find.text('${l10n.petTypeCat} · ${l10n.petFollowOwnedBy('owner1')}'), findsOneWidget);
    expect(find.text('${l10n.petTypeDog} · ${l10n.petFollowOwnedBy('owner2')}'), findsOneWidget);
    expect(label(tester, 1), l10n.followActionFollowing);
    // 无头像 → 物种 emoji 占位。
    expect(find.text('🐱'), findsOneWidget);
    expect(find.text('🐶'), findsOneWidget);

    final avatar = tester.getRect(find.byKey(const ValueKey('followedPetAvatar_1')));
    final button = tester.getRect(btn(1));
    expect(avatar.size, const Size(40, 40));
    expect(button.width, greaterThanOrEqualTo(88));
    expect(button.height, 30);
    expect((avatar.center.dy - button.center.dy).abs(), lessThan(0.5));
  });

  testWidgets('点 Mengikuti → 立即取消、行保留、按钮变 Ikuti、无确认；埋点 pet_unfollow(source=follow_list)',
      (tester) async {
    final follows = FakePetFollowRepo();
    await pumpFollowList(tester,
        profile: FakeProfileRepo(ownerProfile()),
        pets: FakeFollowedPetRepo([petPage([followedPet(1, iFollowOwner: true)], count: 1)]),
        petFollows: follows);
    await openPets(tester);

    await tester.tap(btn(1));
    await tester.pumpAndSettle();

    expect(follows.calls, ['DELETE 1']);
    expect(find.byType(AlertDialog), findsNothing);
    expect(find.byKey(const ValueKey('followedPetRow_1')), findsOneWidget);
    expect(label(tester, 1), l10n.followActionFollow);
    final e = events.singleWhere((e) => e.$1 == 'pet_unfollow');
    expect(e.$2, {'pet_id': 1, 'owner_id': 'ownertok1', 'source': 'follow_list', 'is_following_owner': true});

    // 再点恢复。
    await tester.tap(btn(1));
    await tester.pumpAndSettle();
    expect(follows.calls, ['DELETE 1', 'POST 1']);
    expect(label(tester, 1), l10n.followActionFollowing);
    expect(events.where((e) => e.$1 == 'pet_follow').single.$2!['source'], 'follow_list');
  });

  testWidgets('取消后下拉刷新：该行消失，胶囊 Hewan 3 → 2', (tester) async {
    final pets = FakeFollowedPetRepo([
      petPage([followedPet(1), followedPet(2), followedPet(3)], count: 3),
      petPage([followedPet(2), followedPet(3)], count: 2),
    ]);
    await pumpFollowList(tester, profile: FakeProfileRepo(ownerProfile(pets: 3)), pets: pets);
    await openPets(tester);
    expect(chipText(tester, petsChip()), '${l10n.petFollowFilterPets} 3');

    await tester.tap(btn(1));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('followedPetRow_1')), findsOneWidget);
    // 刷新前胶囊不变（以列表首页计数为准）。
    expect(chipText(tester, petsChip()), '${l10n.petFollowFilterPets} 3');

    await tester.fling(find.byKey(const ValueKey('followedPetList')), const Offset(0, 400), 1000);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('followedPetRow_1')), findsNothing);
    expect(chipText(tester, petsChip()), '${l10n.petFollowFilterPets} 2');
    expect(pets.calls, [null, null]);
  });

  testWidgets('1.5 复审：取消成功后主页重取失败 → 整页不切错误态，行与按钮保留', (tester) async {
    final profile = FakeProfileRepo(ownerProfile(), failFromCall: 2);
    // 我自己的列表：路由 userId = 当前登录 id → 按钮成功后 invalidate 的正是本页的主页 provider。
    await pumpFollowList(tester,
        profile: profile, pets: FakeFollowedPetRepo([petPage([followedPet(1)], count: 1)]), ownerId: kMe);
    await openPets(tester);

    await tester.tap(btn(1));
    await tester.pumpAndSettle();

    expect(profile.calls, greaterThanOrEqualTo(2));
    expect(find.text(l10n.blockedListLoadFailed), findsNothing);
    expect(find.byKey(const ValueKey('followListRetry')), findsNothing);
    expect(find.byKey(const ValueKey('followedPetRow_1')), findsOneWidget);
    expect(label(tester, 1), l10n.followActionFollow);
    expect(petsChip(), findsOneWidget);
  });

  testWidgets('点行（除按钮外）→ /pets/{petId}', (tester) async {
    final h = await pumpFollowList(tester,
        profile: FakeProfileRepo(ownerProfile()),
        pets: FakeFollowedPetRepo([petPage([followedPet(7)], count: 1)]));
    await openPets(tester);

    await tester.tap(find.byKey(const ValueKey('followedPetName_7')));
    await tester.pumpAndSettle();
    expect(h.openedPet, '/pets/7');
  });

  testWidgets('别人的列表：Hewan 行无任何按钮，仍可点进访客页', (tester) async {
    final h = await pumpFollowList(tester,
        profile: FakeProfileRepo(ownerProfile(self: false)),
        pets: FakeFollowedPetRepo([petPage([followedPet(7, iFollow: false)], count: 1)]));
    await openPets(tester);

    expect(find.byKey(const ValueKey('followedPetRow_7')), findsOneWidget);
    expect(btn(7), findsNothing);
    expect(find.byType(FilledButton), findsNothing);
    expect(find.byType(OutlinedButton), findsNothing);
    await tester.tap(find.byKey(const ValueKey('followedPetOpen_7')));
    await tester.pumpAndSettle();
    expect(h.openedPet, '/pets/7');
  });

  testWidgets('加载中显示骨架', (tester) async {
    final pets = FakeFollowedPetRepo([petPage([followedPet(1)])])..gate = Completer<void>();
    await pumpFollowList(tester, profile: FakeProfileRepo(ownerProfile()), pets: pets);
    await tester.tap(petsChip());
    await tester.pump();
    expect(find.byType(BlockedListSkeleton), findsOneWidget);
    pets.gate!.complete();
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('followedPetRow_1')), findsOneWidget);
  });

  testWidgets('首页失败 → 错误态 + 重试（不是空态）；重试成功出列表', (tester) async {
    final pets = FakeFollowedPetRepo([Exception('boom'), petPage([followedPet(1)], count: 1)]);
    await pumpFollowList(tester, profile: FakeProfileRepo(ownerProfile()), pets: pets);
    await openPets(tester);

    expect(find.text(l10n.blockedListLoadFailed), findsOneWidget);
    expect(find.text(l10n.petFollowListEmptySelf), findsNothing);
    await tester.tap(find.byKey(const ValueKey('followListRetry')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('followedPetRow_1')), findsOneWidget);
  });

  testWidgets('滚到底自动加载下一页（带游标）；失败 → 底部重试，点重试续拉', (tester) async {
    final first = [for (var i = 1; i <= 20; i++) followedPet(i)];
    final pets = FakeFollowedPetRepo([
      petPage(first, hasMore: true, next: 'c1', count: 21),
      Exception('boom'),
      petPage([followedPet(21)]),
    ]);
    await pumpFollowList(tester, profile: FakeProfileRepo(ownerProfile()), pets: pets);
    await openPets(tester);

    await tester.drag(find.byKey(const ValueKey('followedPetList')), const Offset(0, -3000));
    await tester.pumpAndSettle();
    expect(pets.calls, [null, 'c1']);
    expect(find.byKey(const ValueKey('followedPetLoadMoreRetry')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('followedPetLoadMoreRetry')));
    await tester.pumpAndSettle();
    expect(pets.calls, [null, 'c1', 'c1']);
    await tester.drag(find.byKey(const ValueKey('followedPetList')), const Offset(0, -3000));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('followedPetRow_21')), findsOneWidget);
    // 加载更多不覆盖首页计数。
    expect(chipText(tester, petsChip()), '${l10n.petFollowFilterPets} 21');
  });

  test('FollowedPetPage.fromJson：缺键容忍，petFollowingCount 只首页有', () {
    final first = FollowedPetPage.fromJson({
      'items': [
        {
          'petId': 3,
          'name': 'Miu',
          'petType': 'CAT',
          'ownerNickname': 'Ana',
          'ownerToken': 'tok',
          'iFollow': true,
          'iFollowOwner': false,
        },
        {'petId': 4, 'name': 'Bo', 'iFollow': false, 'iFollowOwner': true},
      ],
      'nextCursor': 'c',
      'hasMore': true,
      'petFollowingCount': 2,
    });
    expect(first.petFollowingCount, 2);
    expect(first.items.first.ownerToken, 'tok');
    expect(first.items.first.iFollow, isTrue);
    expect(first.items.last.avatarUrl, isNull);
    expect(first.items.last.ownerNickname, isNull);
    expect(first.items.last.iFollowOwner, isTrue);
    expect(FollowedPetPage.fromJson({'items': [], 'hasMore': false}).petFollowingCount, isNull);
  });

  testWidgets('AC9.3：访客页按钮（缺省）追更成功后失效追更宠物列表；列表行按钮不失效', (tester) async {
    final pets = FakeFollowedPetRepo([petPage([followedPet(1)], count: 1)]);
    final container = ProviderContainer(overrides: [
      authControllerProvider.overrideWith(LoggedInAuth.new),
      followedPetRepositoryProvider.overrideWithValue(pets),
      petFollowRepositoryProvider.overrideWithValue(FakePetFollowRepo()),
      publicProfileRepositoryProvider.overrideWithValue(FakeProfileRepo(ownerProfile())),
    ]);
    addTearDown(container.dispose);
    final sub = container.listen(followedPetListControllerProvider(kOwnerToken), (_, _) {});
    addTearDown(sub.close);
    await container.read(followedPetListControllerProvider(kOwnerToken).future);
    expect(pets.calls, hasLength(1));

    Future<void> tapButton(bool invalidates) async {
      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: Center(
              child: PetFollowButton(
                key: ValueKey(invalidates),
                petId: 9,
                initialFollow: false,
                style: PetFollowButtonStyle.listCompact,
                source: 'pet_visitor',
                invalidatesFollowedPetList: invalidates,
              ),
            ),
          ),
        ),
      ));
      await tester.tap(find.byKey(const ValueKey('petFollowList_9')));
      await tester.pumpAndSettle();
    }

    await tapButton(false);
    expect(pets.calls, hasLength(1));
    await tapButton(true);
    expect(pets.calls, hasLength(2));
  });
}
