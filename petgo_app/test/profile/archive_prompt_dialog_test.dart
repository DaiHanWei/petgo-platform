import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/data/me_repository.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/data/health_event_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pending_archive.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/presentation/archive_prompt_dialog.dart';
import 'package:tailtopia/features/profile/presentation/pet_profile_create_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

class _FakeHealthRepo implements HealthEventRepository {
  final List<({String sourceRef, int petId, ArchiveDecision decision})> calls = [];
  final Set<String> decided;
  _FakeHealthRepo({Set<String>? decided}) : decided = decided ?? {};

  @override
  Future<bool> hasDecision(String sourceRef) async => decided.contains(sourceRef);

  @override
  Future<void> recordDecision({
    required HealthSourceType sourceType,
    required String sourceRef,
    required int petId,
    required ArchiveDecision decision,
    String? symptomSummary,
    String? aiLevel,
    String? adviceSummary,
    List<String> imImageRefs = const [],
  }) async {
    calls.add((sourceRef: sourceRef, petId: petId, decision: decision));
  }
}

class _FakeMeRepo implements MeRepository {
  String? lastStatus;
  @override
  Future<UserProfile> getMe() async => const UserProfile();
  @override
  Future<UserProfile> updateNickname(String nickname) async => const UserProfile();
  @override
  Future<UserProfile> updatePetStatus(String petStatus) async {
    lastStatus = petStatus;
    return UserProfile(petStatus: petStatus, hasPetProfile: false);
  }

  @override
  Future<UserProfile> updateAvatar(String avatarUrl) async => UserProfile(avatarUrl: avatarUrl);
}

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

class _FakeProfileRepo implements ProfileRepository {
  @override
  Future<void> deleteMyProfile() async {}

  @override
  Future<PetProfile> create({
    required String petType,
    required String name,
    required DateTime birthday,
    String? avatarUrl,
    String? breed,
    String? intro,
    String? idempotencyKey,
  }) async =>
      PetProfile(id: 99, name: name, cardToken: 'T', petType: petType, birthday: birthday);
  @override
  Future<PetProfile?> getMyProfile() async => null;
  @override
  Future<PetProfile> update({
    String? name,
    String? avatarUrl,
    String? breed,
    DateTime? birthday,
    String? intro,
  }) async =>
      PetProfile(id: 99, name: name ?? 'x', cardToken: 'T');
}

AuthState _auth({required String status, required bool hasProfile}) => AuthState(
      status: AuthStatus.authenticated,
      profile: UserProfile(petStatus: status, hasPetProfile: hasProfile),
    );

const _pet = PetProfile(id: 7, name: 'Momo', cardToken: 'T');

/// 触发 showArchivePrompt 的最简 harness（带路由以观察 ②③ 导航）。
Future<GoRouter> _pumpTrigger(
  WidgetTester tester, {
  required ProviderContainer container,
  bool redState = false,
}) async {
  final router = GoRouter(
    initialLocation: '/trigger',
    routes: [
      GoRoute(
        path: '/trigger',
        builder: (c, s) => Scaffold(
          body: Consumer(
            builder: (context, ref, _) => Center(
              child: ElevatedButton(
                key: const ValueKey('go'),
                onPressed: () => showArchivePrompt(
                  context,
                  ref,
                  ArchivePromptArgs(
                    sourceRef: 'triage:1',
                    sourceType: HealthSourceType.aiTriage,
                    symptomSummary: 'cough',
                    aiLevel: 'RED',
                    redState: redState,
                  ),
                ),
                child: const Text('go'),
              ),
            ),
          ),
        ),
      ),
      GoRoute(path: '/profile/create', builder: (c, s) => const Scaffold(body: Text('create-page'))),
    ],
  );
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
  return router;
}

ProviderContainer _container({
  required AuthState auth,
  required _FakeHealthRepo health,
  PetProfile? pet,
  _FakeMeRepo? me,
}) {
  final c = ProviderContainer(overrides: [
    authControllerProvider.overrideWith(() => _TestAuthController(auth)),
    healthEventRepositoryProvider.overrideWithValue(health),
    petProfileProvider.overrideWith((ref) async => pet),
    if (me != null) meRepositoryProvider.overrideWithValue(me),
  ]);
  addTearDownContainer(c);
  return c;
}

final _containers = <ProviderContainer>[];
void addTearDownContainer(ProviderContainer c) => _containers.add(c);

void main() {
  tearDown(() {
    for (final c in _containers) {
      c.dispose();
    }
    _containers.clear();
  });

  // ===== AC1 ① 状态 A + 已建档 =====
  testWidgets('AC1①: A 已建档 → 存入弹窗 → recordDecision(ARCHIVED)', (tester) async {
    final health = _FakeHealthRepo();
    final container = _container(auth: _auth(status: 'HAS_PET', hasProfile: true), health: health, pet: _pet);
    await _pumpTrigger(tester, container: container);

    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('archiveSave')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('archiveSave')));
    await tester.pumpAndSettle();

    expect(health.calls, hasLength(1));
    expect(health.calls.first.decision, ArchiveDecision.archived);
    expect(health.calls.first.petId, 7);
  });

  // ===== AC1 ② 状态 A + 未建档 =====
  testWidgets('AC1②: A 未建档 → 立即创建 → 挂起 pending + 跳建档', (tester) async {
    final health = _FakeHealthRepo();
    final container = _container(auth: _auth(status: 'HAS_PET', hasProfile: false), health: health);
    await _pumpTrigger(tester, container: container);

    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('archiveCreate')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('archiveCreate')));
    await tester.pumpAndSettle();

    expect(health.calls, isEmpty); // 未直接落库，等回灌
    expect(container.read(pendingArchiveProvider)?.sourceRef, 'triage:1');
    // 改用 push 后，断言目标建档页已渲染（push 不改 currentConfiguration.uri 基址）。
    expect(find.text('create-page'), findsOneWidget);
  });

  // ===== AC1 ③ 状态 B/C =====
  testWidgets('AC1③: B/C → 去创建 → FR-0G 切 A + 挂起 pending + 跳建档', (tester) async {
    final health = _FakeHealthRepo();
    final me = _FakeMeRepo();
    final container = _container(auth: _auth(status: 'PLANNING', hasProfile: false), health: health, me: me);
    await _pumpTrigger(tester, container: container);

    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('archiveCreate')));
    await tester.pumpAndSettle();

    expect(me.lastStatus, 'HAS_PET'); // FR-0G 切状态
    expect(container.read(pendingArchiveProvider)?.sourceRef, 'triage:1');
    // 改用 push 后，断言目标建档页已渲染（push 不改 currentConfiguration.uri 基址）。
    expect(find.text('create-page'), findsOneWidget);
  });

  // ===== AC4 红色态 A 已建档直接存入（无弹窗）=====
  testWidgets('AC4: 红色态 A 已建档 → 直接存入无弹窗', (tester) async {
    final health = _FakeHealthRepo();
    final container = _container(auth: _auth(status: 'HAS_PET', hasProfile: true), health: health, pet: _pet);
    await _pumpTrigger(tester, container: container, redState: true);

    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('archiveSave')), findsNothing); // 无弹窗
    expect(health.calls, hasLength(1));
    expect(health.calls.first.decision, ArchiveDecision.archived);
  });

  // ===== 只问一次：已决策不再弹 =====
  testWidgets('AC1: 已决策 sourceRef → 不再弹窗', (tester) async {
    final health = _FakeHealthRepo(decided: {'triage:1'});
    final container = _container(auth: _auth(status: 'HAS_PET', hasProfile: true), health: health, pet: _pet);
    await _pumpTrigger(tester, container: container);
    await tester.tap(find.byKey(const ValueKey('go')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('archiveSave')), findsNothing);
    expect(health.calls, isEmpty);
  });

  // ===== AC3/AC4 建档完成 → 回灌挂起存档意图（同 sourceRef，跳过庆祝页）=====
  testWidgets('AC3: triageArchive 建档成功 → 回灌 recordDecision + 清空 pending', (tester) async {
    final health = _FakeHealthRepo();
    final container = ProviderContainer(overrides: [
      healthEventRepositoryProvider.overrideWithValue(health),
      profileRepositoryProvider.overrideWithValue(_FakeProfileRepo()),
      petProfileProvider.overrideWith((ref) async => null),
    ]);
    addTearDownContainer(container);
    // 预置挂起意图（模拟 ②/③ 已挂起）。
    container.read(pendingArchiveProvider.notifier).set(const PendingArchive(
          sourceRef: 'triage:1', sourceType: HealthSourceType.aiTriage, aiLevel: 'RED'));

    final router = GoRouter(
      initialLocation: '/profile/create?origin=triageArchive',
      routes: [
        GoRoute(path: '/profile/create', builder: (c, s) => const PetProfileCreatePage()),
        GoRoute(path: '/profile', builder: (c, s) => const Scaffold(body: Text('archive'))),
        GoRoute(path: '/profile/created', builder: (c, s) => const Scaffold(body: Text('celebrate'))),
      ],
    );
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.pumpAndSettle();

    // JENIS HEWAN 下拉：弹层选 Anjing。
    await tester.tap(find.byKey(const ValueKey('petProfileSpeciesField')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('speciesOption_DOG')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('petProfileNameField')), 'Rocky');
    await tester.pump();
    await tester.ensureVisible(find.byKey(const ValueKey('petProfileBirthdayTile')));
    await tester.tap(find.byKey(const ValueKey('petProfileBirthdayTile')));
    await tester.pumpAndSettle();
    await tester.tap(find.descendant(of: find.byType(Dialog), matching: find.text('1')).first);
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const ValueKey('petProfileSubmit')));
    await tester.tap(find.byKey(const ValueKey('petProfileSubmit')));
    await tester.pumpAndSettle();

    // 回灌：同 sourceRef + 新建档案 id（99），ARCHIVED；pending 已清空；未进庆祝页。
    expect(health.calls, hasLength(1));
    expect(health.calls.first.sourceRef, 'triage:1');
    expect(health.calls.first.petId, 99);
    expect(health.calls.first.decision, ArchiveDecision.archived);
    expect(container.read(pendingArchiveProvider), isNull);
    expect(find.text('celebrate'), findsNothing); // 跳过庆祝页
  });
}
