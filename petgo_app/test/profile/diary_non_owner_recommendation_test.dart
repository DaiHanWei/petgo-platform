import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/data/pet_recommendation_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/presentation/diary_guest_page.dart';
import 'package:tailtopia/features/profile/presentation/growth_archive_page.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_recommendation_grid.dart';
import 'package:tailtopia/features/profile/presentation/widgets/recommended_pet_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：Diary「声明未养宠 / 计划养宠」态也看到推荐（V1.3.0 batch-b1 Story 4.2 · B1-D2）。
///
/// <h3>本条与 Story 4.1 的区别只有「哪一屏 + 哪个 from」</h3>
/// 组件、接口、卡片渲染全部复用 4.1（那边已有 23 条用例），所以这里**只验三件事**：
/// ① 这一屏真的接上了；② 原有引导一个不删；③ 埋点 `from` 与 4.1 **分得开**。
///
/// <h3>🔴 游客态不动是**机械**判据，不靠「看起来没变」</h3>
/// AC2 要的是「维持现状」。只断言 UI 里没有推荐区的话，日后有人给 `DiaryGuestPage`
/// 加一段推荐照样能过 —— 所以这里还扫源码。
class _FakeRepo implements PetRecommendationRepository {
  _FakeRepo(this.pets);

  final List<RecommendedPet> pets;

  @override
  Future<List<RecommendedPet>> recommendations({int? limit}) async => pets;
}

/// ⚠️ 两个图片字段都给空：本文件不验图（4.1 那边验），而真去拉网络图会让用例带上
/// 一个 HttpClient 桩的包袱。空头像走 InitialAvatar 的首字母分支，空封面走爪印占位。
RecommendedPet _pet(int id) =>
    RecommendedPet(petId: id, name: 'Mochi$id', avatarUrl: '', petType: 'CAT', companionDays: 12);

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

AuthState _auth(String? petStatus) => AuthState(
      status: petStatus == null ? AuthStatus.guest : AuthStatus.authenticated,
      role: petStatus == null ? null : 'USER',
      profile: petStatus == null ? null : UserProfile(petStatus: petStatus),
    );

void main() {
  final captured = <(String, Map<String, Object>?)>[];

  setUp(() {
    captured.clear();
    Analytics.debugCaptureSink = (e, p) => captured.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<void> pumpDiary(
    WidgetTester tester, {
    required String? petStatus,
    required List<RecommendedPet> pets,
  }) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        petRecommendationRepositoryProvider.overrideWithValue(_FakeRepo(pets)),
        authControllerProvider.overrideWith(() => _TestAuthController(_auth(petStatus))),
        petProfileProvider.overrideWith((ref) async => null),
      ],
      child: MaterialApp.router(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        routerConfig: GoRouter(
          initialLocation: '/diary',
          routes: [
            GoRoute(path: '/diary', builder: (_, _) => const GrowthArchivePage()),
            GoRoute(path: '/pets/:petId', builder: (_, _) => const Scaffold(body: Text('visitor'))),
          ],
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  group('AC1 第二态接入（状态 B / C）', () {
    for (final status in const ['PLANNING', 'ENTHUSIAST']) {
      testWidgets('$status 态看到推荐集合，且原有引导一个不删', (tester) async {
        await pumpDiary(tester, petStatus: status, pets: [_pet(7), _pet(8)]);
        expect(find.byKey(const ValueKey('petRecommendationGrid')), findsOneWidget);
        expect(find.byKey(const ValueKey('recommendedPet_7')), findsOneWidget);
        // 🛡 AC1：该屏原有内容原样保留（标题 + 改状态入口）。
        expect(find.byKey(const ValueKey('changeStatusButton')), findsOneWidget);
        expect(find.text(AppLocalizations.of(tester.element(find.byType(Scaffold).first))
            .growthArchiveNonOwnerTitle), findsOneWidget);
      });
    }

    testWidgets('🛡 池子为空 → 整块不渲染，且版面与改动前逐像素相同', (tester) async {
      // 同 4.1：新站池子几乎必然为空，那是常态而不是边角态。
      await pumpDiary(tester, petStatus: 'PLANNING', pets: const []);
      expect(find.byType(PetRecommendationGrid), findsNothing);
      expect(find.byKey(const ValueKey('changeStatusButton')), findsOneWidget);
      final guide = find.byKey(const ValueKey('changeStatusButton'));
      expect(find.ancestor(of: guide, matching: find.byType(Center)), findsOneWidget);
      expect(find.ancestor(of: guide, matching: find.byType(SingleChildScrollView)), findsNothing);
    });

    testWidgets('真有卡 → 换可滚动容器（不然引导会被网格挤出可视区）', (tester) async {
      await pumpDiary(tester, petStatus: 'PLANNING', pets: [_pet(7)]);
      expect(
          find.ancestor(
              of: find.byKey(const ValueKey('changeStatusButton')),
              matching: find.byType(SingleChildScrollView)),
          findsOneWidget);
    });
  });

  group('AC2 游客态维持现状不动', () {
    testWidgets('未登录 → 仍是 DiaryGuestPage，没有推荐区', (tester) async {
      await pumpDiary(tester, petStatus: null, pets: [_pet(7)]);
      expect(find.byType(DiaryGuestPage), findsOneWidget);
      expect(find.byType(PetRecommendationGrid), findsNothing);
    });

    test('🔴 机械判据：diary_guest_page.dart 里没有任何推荐位的影子', () {
      // 只断言「UI 里看不到」的话，日后有人给游客态加一段推荐照样能过。
      final src = File('lib/features/profile/presentation/diary_guest_page.dart')
          .readAsStringSync();
      for (final forbidden in ['PetRecommendationGrid', 'RecommendedPet', 'pet_card_tapped']) {
        expect(src.contains(forbidden), isFalse, reason: '游客态不该出现 $forbidden（AC2）');
      }
    });
  });

  group('AC3 埋点能区分两批人', () {
    testWidgets('从本屏点卡片 → pet_card_tapped(from=diary_non_owner)', (tester) async {
      await pumpDiary(tester, petStatus: 'PLANNING', pets: [_pet(7)]);
      await tester.tap(find.byKey(const ValueKey('recommendedPet_7')));
      await tester.pumpAndSettle();
      expect(captured.where((e) => e.$1 == 'pet_card_tapped').map((e) => e.$2),
          [{'from': 'diary_non_owner'}]);
    });

    testWidgets('属性里仍然只有 from —— 本 story 不往里加别的东西', (tester) async {
      await pumpDiary(tester, petStatus: 'PLANNING', pets: [_pet(7)]);
      await tester.tap(find.byKey(const ValueKey('recommendedPet_7')));
      await tester.pumpAndSettle();
      expect(captured.firstWhere((e) => e.$1 == 'pet_card_tapped').$2!.keys, ['from']);
    });

    test('🔴 两屏的 from 必须是两个不同的值（合成一个就再也分不开两批人的转化）', () {
      expect(kPetRecommendFromDiaryNonOwner, 'diary_non_owner');
      expect(kPetRecommendFromDiaryNonOwner, isNot(kPetRecommendFromDiaryEmpty));
    });
  });
}
