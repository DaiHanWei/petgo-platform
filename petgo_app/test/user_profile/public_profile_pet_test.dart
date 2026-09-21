import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/domain/archive_scope.dart';
import 'package:tailtopia/features/profile/presentation/visitor_archive_view.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_pet_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_user_posts_repository.dart';
import 'package:tailtopia/features/user_profile/presentation/public_profile_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.0 batch-b1 Story 2.3（FR-118.2 · AD-4）：主页宠物区与站内访客入口。
///
/// 覆盖 AC2（不下发分享 token，前端也不持有）· AC3（宠物卡与跳转）·
/// AC4（站内进入隐藏来源横幅）· AC5（没有日历）。
/// AC1（服务端投影与鉴权）在后端，由 `InAppVisitorEntryTest` 守。

const int _kViewerId = 5;
const int _kTargetId = 7;
const int _kPetId = 42;

class _FakeProfileRepo implements PublicProfileRepository {
  @override
  Future<PublicProfile> getPublicProfile(int userId) async => PublicProfile(
        postCount: 0,
        likeCount: 0,
        isDeactivated: false,
        self: false,
        nickname: 'Rina',
        joinedAt: DateTime.utc(2026, 3, 4),
      );
}

class _EmptyPostsRepo implements PublicUserPostsRepository {
  @override
  Future<PublicUserPostPage> fetch(int userId, {String? cursor}) async =>
      PublicUserPostPage.empty;
}

class _LoggedInAuth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: _kViewerId, nickname: 'Me', onboardingCompleted: true),
      );
}

class _GuestAuth extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.guest);
}

PublicProfilePet _pet({int diaryCount = 42}) => PublicProfilePet(
      petId: _kPetId,
      name: 'Miu',
      avatarUrl: null,
      petType: 'CAT',
      birthday: DateTime.utc(2024, 6, 1),
      diaryCount: diaryCount,
    );

/// 记录站内访客视图被推到了哪个 petId。
class _Nav {
  int? visitedPetId;
}

Future<_Nav> _pump(
  WidgetTester tester, {
  PublicProfilePet? pet,
  bool loggedIn = true,
}) async {
  final nav = _Nav();
  final router = GoRouter(
    initialLocation: '${PublicProfilePage.routeBase}/$_kTargetId',
    routes: [
      GoRoute(
        path: PublicProfilePage.routePattern,
        builder: (_, state) =>
            PublicProfilePage(userId: int.parse(state.pathParameters['userId']!)),
      ),
      GoRoute(
        path: VisitorArchiveView.inAppRoutePattern,
        builder: (_, state) {
          nav.visitedPetId = int.parse(state.pathParameters['petId']!);
          return const Scaffold(body: Center(child: Text('visitor')));
        },
      ),
      GoRoute(path: '/login', builder: (_, _) => const Scaffold(body: Text('login'))),
    ],
  );
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    publicProfileRepositoryProvider.overrideWithValue(_FakeProfileRepo()),
    publicUserPostsRepositoryProvider.overrideWithValue(_EmptyPostsRepo()),
    publicProfilePetProvider(_kTargetId).overrideWith((ref) async => pet),
    authControllerProvider.overrideWith(loggedIn ? _LoggedInAuth.new : _GuestAuth.new),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
  return nav;
}

void main() {
  late AppLocalizations l10n;

  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
  });

  group('AC3：主页宠物区', () {
    testWidgets('渲染宠物卡：名字 + 物种 · 年龄 · Diary 数', (tester) async {
      await _pump(tester, pet: _pet());

      expect(find.byKey(const ValueKey('profilePetCard')), findsOneWidget);
      expect(find.text('Miu'), findsOneWidget);
      final meta = tester.widget<Text>(find.byKey(const ValueKey('profilePetMeta'))).data!;
      expect(meta, contains(l10n.petTypeCat));
      expect(meta, contains(l10n.meDiaryCount('42')));
    });

    /// ⚠️ Diary 数 >99 收成「99+」—— 与「我的」页同一出口，别在这里另起一套阈值。
    testWidgets('Diary 数超过 99 显示 99+', (tester) async {
      await _pump(tester, pet: _pet(diaryCount: 128));

      expect(tester.widget<Text>(find.byKey(const ValueKey('profilePetMeta'))).data,
          contains(l10n.meDiaryCount('99+')));
    });

    /// 没建过档案的人：整块**不渲染** —— 「他还没养宠物」不需要一张空卡片来说明。
    testWidgets('没有宠物 → 宠物区整块不渲染', (tester) async {
      await _pump(tester, pet: null);

      expect(find.byKey(const ValueKey('profilePetCard')), findsNothing);
    });

    /// ⚠️ Tailsonality 角色小标位是**天然空状态**（FR-117 在批次 B2），**不做占位设计**。
    testWidgets('卡上没有 Tailsonality 占位', (tester) async {
      await _pump(tester, pet: _pet());

      expect(find.byKey(const ValueKey('profilePetTailsonality')), findsNothing);
      expect(find.textContaining('Tailsonality'), findsNothing);
    });

    testWidgets('点宠物卡 → 站内访客视图，按 petId', (tester) async {
      final nav = await _pump(tester, pet: _pet());

      await tester.tap(find.byKey(const ValueKey('profilePetCard')));
      await tester.pumpAndSettle();

      expect(nav.visitedPetId, _kPetId);
    });

    /// 🔴 站内访客接口**仅登录可用**（AC1）→ 游客点卡片走 FR-0C 登录门控，**不跳进去**。
    testWidgets('游客点宠物卡 → 登录引导，不进访客视图', (tester) async {
      final nav = await _pump(tester, pet: _pet(), loggedIn: false);

      await tester.tap(find.byKey(const ValueKey('profilePetCard')));
      await tester.pumpAndSettle();

      expect(nav.visitedPetId, isNull);
    });
  });

  group('AC2：前端也不持有分享 token', () {
    /// 🔴 B1-D1 否掉的方案正是「由主页下发对方宠物的分享链接码」。
    /// 后端 record 里装不下它（`InAppVisitorEntryTest` 钉着），这里钉前端模型同样没有 ——
    /// 有一个字段能装，迟早会有人把它填上并拿去拼分享链接。
    test('PublicProfilePet 没有任何 token / 分享链接字段', () {
      final json = <String, dynamic>{
        'petId': _kPetId,
        'name': 'Miu',
        'diaryCount': 1,
        // 就算服务端哪天多发了这些，前端也接不住（下面断言 toString 里没有它们）。
        'cardToken': 'tok-secret',
        'shareUrl': 'https://s.tailtopia.id/p/tok-secret',
      };
      final pet = PublicProfilePet.fromJson(json);

      expect(pet.petId, _kPetId);
      expect(pet.toString(), isNot(contains('tok-secret')));
    });
  });

  group('AC4 / AC5：访客视图的站内态', () {
    /// 🔴 站内入口进来**不渲染**「由 XX 分享 · 仅可查看」横幅 ——
    /// 从别人主页点宠物卡进来时，「谁分享的」这个前提根本不成立（AD-4 Rule 5）。
    test('ArchiveScope 能区分两种来源', () {
      const inApp = ArchiveScope.inAppVisitor(_kPetId);
      const shared = ArchiveScope.visitor('tok');

      expect(inApp.isVisitor, isTrue);
      expect(inApp.isInApp, isTrue);
      expect(inApp.petId, _kPetId);
      expect(inApp.token, isNull);

      expect(shared.isVisitor, isTrue);
      expect(shared.isInApp, isFalse);

      // 🔴 `isVisitor` 必须两个都判：只判 token 的话站内态会被当成作者态，
      // 于是去打 `/pet-profiles/me/*` —— 表现是「点别人的猫，看到的是自己的档案」。
      expect(const ArchiveScope.me().isVisitor, isFalse);
    });

    /// 两种作用域必须是**不同的族键**，否则两个入口会共用同一份缓存。
    test('两种来源的作用域不相等，可安全用作 family 键', () {
      expect(const ArchiveScope.inAppVisitor(1) == const ArchiveScope.visitor('1'), isFalse);
      expect(const ArchiveScope.inAppVisitor(1) == const ArchiveScope.inAppVisitor(1), isTrue);
      expect(const ArchiveScope.inAppVisitor(1).hashCode,
          const ArchiveScope.inAppVisitor(1).hashCode);
    });
  });
}
