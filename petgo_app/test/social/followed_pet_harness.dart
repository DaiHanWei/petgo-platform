// V1.4.2 social-topic · Story 1.5：关注列表 Hewan 相关 widget 测试的共用夹具（非测试文件，不以 _test 结尾）。
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/data/pet_recommendation_repository.dart';
import 'package:tailtopia/features/social/data/follow_list_repository.dart';
import 'package:tailtopia/features/social/data/follow_suggestion_repository.dart';
import 'package:tailtopia/features/social/data/followed_pet_repository.dart';
import 'package:tailtopia/features/social/data/pet_follow_repository.dart';
import 'package:tailtopia/features/social/domain/follow_list_item.dart';
import 'package:tailtopia/features/social/domain/followed_pet.dart';
import 'package:tailtopia/features/social/domain/pet_follow_state.dart';
import 'package:tailtopia/features/social/presentation/follow_list_page.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_repository.dart';
import 'package:tailtopia/features/user_profile/presentation/public_profile_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

const int kMe = 1;
const int kOwner = 50;
const String kOwnerToken = 'ownerownerownerownerownerowner50';

class LoggedInAuth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: kMe, nickname: 'Me', onboardingCompleted: true),
      );
}

class FakeProfileRepo implements PublicProfileRepository {
  FakeProfileRepo(this.profile, {this.failFromCall});
  PublicProfile profile;
  int calls = 0;

  /// 第 N 次（含）起抛错（模拟页内操作触发的重取失败）；null = 永不失败。
  int? failFromCall;

  @override
  Future<PublicProfile> getPublicProfile(int userId) async {
    calls++;
    if (failFromCall != null && calls >= failFromCall!) throw Exception('profile refetch failed');
    return profile;
  }
}

/// Pengguna 列表：一律空页（本组测试只看 Hewan）。
class EmptyFollowListRepo implements FollowListRepository {
  final List<(FollowListTab, String?)> calls = [];

  @override
  Future<FollowListPageData> fetch(String userToken, FollowListTab tab, {String? cursor}) async {
    calls.add((tab, cursor));
    return const FollowListPageData(items: []);
  }
}

class NoSuggestions implements FollowSuggestionRepository {
  @override
  Future<List<FollowListItem>> fetch() async => const [];
}

/// Hewan 列表：按调用顺序出队（最后一个常驻）；元素是 [FollowedPetPage] 或要抛出的错误。
class FakeFollowedPetRepo implements FollowedPetRepository {
  FakeFollowedPetRepo(this.results);
  final List<Object> results;
  final List<String?> calls = [];
  Completer<void>? gate;

  @override
  Future<FollowedPetPage> fetch(String userToken, {String? cursor}) async {
    calls.add(cursor);
    if (gate != null) await gate!.future;
    final r = results.length > 1 ? results.removeAt(0) : results.first;
    if (r is FollowedPetPage) return r;
    throw r;
  }
}

class FakePetFollowRepo implements PetFollowRepository {
  final List<String> calls = [];

  @override
  Future<PetFollowResult> follow(int petId) async {
    calls.add('POST $petId');
    return const PetFollowResult(iFollow: true, followerCount: 1);
  }

  @override
  Future<PetFollowResult> unfollow(int petId) async {
    calls.add('DELETE $petId');
    return const PetFollowResult(iFollow: false, followerCount: 0);
  }
}

class FakeRecommendRepo implements PetRecommendationRepository {
  FakeRecommendRepo(this.pets, {this.fail = false});
  final List<RecommendedPet> pets;
  final bool fail;
  int calls = 0;

  @override
  Future<RecommendedPetPage> recommendations({int? limit, String? cursor}) async {
    calls++;
    if (fail) throw Exception('boom');
    return RecommendedPetPage(items: pets, hasMore: false);
  }
}

PublicProfile ownerProfile({bool self = true, int following = 5, int? pets = 3}) => PublicProfile(
      postCount: 0,
      likeCount: 0,
      isDeactivated: false,
      self: self,
      nickname: 'Mikky',
      userToken: kOwnerToken,
      followingCount: following,
      petFollowingCount: pets,
    );

FollowedPet followedPet(int id, {String type = 'CAT', bool iFollow = true, bool iFollowOwner = false}) => FollowedPet(
      petId: id,
      name: 'Mochi$id',
      petType: type,
      ownerNickname: 'owner$id',
      ownerToken: 'ownertok$id',
      iFollow: iFollow,
      iFollowOwner: iFollowOwner,
    );

FollowedPetPage petPage(List<FollowedPet> items, {bool hasMore = false, String? next, int? count}) =>
    FollowedPetPage(items: items, hasMore: hasMore, nextCursor: next, petFollowingCount: count);

RecommendedPet recommendedPet(int id) =>
    RecommendedPet(petId: id, name: 'Rec$id', avatarUrl: '', petType: 'DOG', companionDays: 9);

class Harness {
  late ProviderContainer container;
  String? openedPet;
  final pushed = <String>[];
}

Future<Harness> pumpFollowList(
  WidgetTester tester, {
  required FakeProfileRepo profile,
  required FakeFollowedPetRepo pets,
  EmptyFollowListRepo? people,
  FakePetFollowRepo? petFollows,
  FakeRecommendRepo? recommend,
  int ownerId = kOwner,
}) async {
  final h = Harness();
  final router = GoRouter(
    initialLocation: FollowListPage.location(ownerId, FollowListTab.following),
    routes: [
      GoRoute(
        path: FollowListPage.routePattern,
        builder: (_, s) => FollowListPage(
          userId: int.parse(s.pathParameters['userId']!),
          initialTab: FollowListTab.fromWire(s.uri.queryParameters['tab']),
        ),
      ),
      GoRoute(
        path: PublicProfilePage.routePattern,
        builder: (_, s) => const Scaffold(body: Text('profile')),
      ),
      GoRoute(
        path: '/pets/:petId',
        builder: (_, s) {
          h.openedPet = s.uri.toString();
          return const Scaffold(body: Text('pet visitor'));
        },
      ),
    ],
  );
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    publicProfileRepositoryProvider.overrideWithValue(profile),
    followListRepositoryProvider.overrideWithValue(people ?? EmptyFollowListRepo()),
    followSuggestionRepositoryProvider.overrideWithValue(NoSuggestions()),
    followedPetRepositoryProvider.overrideWithValue(pets),
    petFollowRepositoryProvider.overrideWithValue(petFollows ?? FakePetFollowRepo()),
    petRecommendationRepositoryProvider.overrideWithValue(recommend ?? FakeRecommendRepo(const [])),
    authControllerProvider.overrideWith(LoggedInAuth.new),
  ]);
  addTearDown(container.dispose);
  h.container = container;
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: router,
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
  return h;
}

Finder petsChip() => find.byKey(const ValueKey('followListFilterPets'));
Finder peopleChip() => find.byKey(const ValueKey('followListFilterPeople'));

/// 胶囊里的全部文字拼起来（「Hewan」+「3」）。
String chipText(WidgetTester tester, Finder chip) =>
    tester.widgetList<Text>(find.descendant(of: chip, matching: find.byType(Text))).map((t) => t.data).join(' ');

Future<void> openPets(WidgetTester tester) async {
  await tester.tap(petsChip());
  await tester.pumpAndSettle();
}

/// 覆盖表里没有任何写入的初值（便于断言）。
Map<int, PetFollowOverride> overrides(Harness h) => h.container.read(petFollowStateProvider);
