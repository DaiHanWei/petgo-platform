import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/me/data/me_counts_provider.dart';
import 'package:tailtopia/features/me/data/my_posts_repository.dart';
import 'package:tailtopia/features/me/presentation/me_page.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/social/presentation/follow_list_page.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_pet_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_user_posts_repository.dart';
import 'package:tailtopia/features/user_profile/presentation/public_profile_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 follow · Story 2.2 · AC2：四格的 Mengikuti / Pengikut 两格进关注 / 粉丝列表；Postingan / Suka 不可点。
class _Auth extends AuthController {
  _Auth(this.id);
  final int? id;

  @override
  AuthState build() => AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: id, nickname: 'Me', petStatus: 'PLANNING', onboardingCompleted: true),
      );
}

class _ProfileRepo implements PublicProfileRepository {
  @override
  Future<PublicProfile> getPublicProfile(int userId) async => const PublicProfile(
      postCount: 1, likeCount: 2, isDeactivated: false, self: false, nickname: 'Mikky',
      userToken: 'tok7tok7tok7tok7tok7tok7tok7tok7', followingCount: 3, followerCount: 4);
}

class _EmptyPosts implements PublicUserPostsRepository {
  @override
  Future<PublicUserPostPage> fetch(int userId, {String? cursor}) async => PublicUserPostPage.empty;
}

Future<List<String>> _pump(WidgetTester tester, Widget home, {int? meId = 1}) async {
  tester.view.physicalSize = const Size(1080, 3200);
  tester.view.devicePixelRatio = 2.0;
  addTearDown(tester.view.reset);
  final visited = <String>[];
  final router = GoRouter(
    initialLocation: '/start',
    routes: [
      GoRoute(path: '/start', builder: (_, _) => home),
      GoRoute(
        path: FollowListPage.routePattern,
        builder: (_, s) {
          visited.add(s.uri.toString());
          return const Scaffold(body: Text('list'));
        },
      ),
    ],
  );
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    authControllerProvider.overrideWith(() => _Auth(meId)),
    meCountsProvider.overrideWith((ref, id) async => const MeCounts(
        postCount: 1, likeCount: 2, followingCount: 3, followerCount: 4)),
    myPostsProvider.overrideWith((ref) async => const []),
    petProfileProvider.overrideWith((ref) async => null),
    publicProfileRepositoryProvider.overrideWithValue(_ProfileRepo()),
    publicUserPostsRepositoryProvider.overrideWithValue(_EmptyPosts()),
    publicProfilePetProvider(7).overrideWith((ref) async => null),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: router,
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
  return visited;
}

void main() {
  for (final (name, home, id) in [
    ('我的页', const MePage(), 1),
    ('他人主页', const PublicProfilePage(userId: 7), 7),
  ]) {
    testWidgets('$name：Mengikuti / Pengikut 进列表，Postingan / Suka 不可点', (tester) async {
      final visited = await _pump(tester, home);

      for (final k in ['profileStatPosts', 'profileStatLikes']) {
        expect(find.descendant(of: find.byKey(ValueKey(k)), matching: find.byType(InkWell)), findsNothing);
      }
      await tester.tap(find.byKey(const ValueKey('profileStatFollowing')));
      await tester.pumpAndSettle();
      expect(visited.last, '/users/$id/follows?tab=following');

      Navigator.of(tester.element(find.text('list'))).pop();
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('profileStatFollowers')));
      await tester.pumpAndSettle();
      expect(visited.last, '/users/$id/follows?tab=followers');
    });
  }

  testWidgets('我的页：登录态里没有 id → 两格不可点、不崩', (tester) async {
    await _pump(tester, const MePage(), meId: null);
    for (final k in ['profileStatFollowing', 'profileStatFollowers']) {
      expect(find.descendant(of: find.byKey(ValueKey(k)), matching: find.byType(InkWell)), findsNothing);
    }
  });
}
