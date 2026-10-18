import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/pet_slot/data/pet_slot_repository.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/presentation/pet_profile_create_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

class _FakeRepo implements ProfileRepository {
  @override
  Future<void> deleteMyProfile({int? petId}) async {}

  PetProfile? existing;
  bool createCalled = false;
  bool failExists = false; // true → 模拟并发 409（档案已存在）
  /// V1.4.0 multi-pet：非空 → 建档回 409 + 该 ProblemDetail 响应体（pet-slot-full / pet-limit-reached）。
  Map<String, dynamic>? fail409Body;
  int createdId = 1;
  String? lastPetType;
  String? lastSex;

  @override
  Future<PetProfile> create({
    required String petType,
    required String name,
    required DateTime birthday,
    String? avatarUrl,
    String? breed,
    String? intro,
    double? weightKg,
    String? neuterStatus,
    String? sex,
    String? idempotencyKey,
  }) async {
    createCalled = true;
    lastPetType = petType;
    lastSex = sex;
    if (fail409Body != null) {
      final req = RequestOptions(path: '/pet-profiles');
      throw DioException(
        requestOptions: req,
        response: Response(requestOptions: req, statusCode: 409, data: fail409Body),
      );
    }
    if (failExists) {
      final req = RequestOptions(path: '/pet-profiles');
      throw DioException(
        requestOptions: req,
        response: Response(requestOptions: req, statusCode: 409),
      );
    }
    final made = PetProfile(
        id: createdId, name: name, cardToken: 'T', petType: petType, birthday: birthday);
    // 🔴 建完档，档案**就存在了** —— 之前这里不回写，`getMyProfile()` 建档后仍返回 null，
    //    于是「建档成功 → 重新拉档案 → 拉到非空」这条真实时序在测试里根本不会发生，
    //    bug 20260826（庆祝页一闪而过）正是从这个缺口溜过去的。
    existing = made;
    return made;
  }

  @override
  Future<PetProfile?> getMyProfile({int? petId}) async => existing;

  @override
  Future<PetProfile> update({
    String? name,
    String? avatarUrl,
    String? breed,
    DateTime? birthday,
    String? sex,
    String? intro,
    double? weightKg,
    String? neuterStatus,
    int? petId,
  }) async =>
      PetProfile(id: 1, name: name ?? 'x', cardToken: 'T');
}

Widget _wrap(ProfileRepository repo) {
  return ProviderScope(
    overrides: [
      profileRepositoryProvider.overrideWithValue(repo),
      petProfileProvider.overrideWith((ref) => repo.getMyProfile()),
    ],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: PetProfileCreatePage(),
    ),
  );
}

FilledButton _submitBtn(WidgetTester tester) =>
    tester.widget<FilledButton>(find.byKey(const ValueKey('petProfileSubmit')));

/// 带路由的容器化挂载：submit 成功后页面会 `context.go(...)`，故需真实 GoRouter；
/// 用 ProviderContainer 便于 submit 后断言 auth / 提示条 provider 的状态翻转。
GoRouter _router() => GoRouter(
      initialLocation: '/',
      routes: [
        GoRoute(path: '/', builder: (_, _) => const PetProfileCreatePage()),
        GoRoute(
            path: '/profile/created',
            builder: (_, _) => const Scaffold(body: Text('created'))),
        GoRoute(path: '/profile', builder: (_, _) => const Scaffold(body: Text('profile'))),
      ],
    );

Future<void> _pumpRouted(WidgetTester tester, ProviderContainer container) async {
  await tester.binding.setSurfaceSize(const Size(440, 1600));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      routerConfig: _router(),
    ),
  ));
  await tester.pumpAndSettle();
}

/// 填齐必填三项（类型 CAT / 名字 / 生日）并提交。
Future<void> _fillAndSubmit(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('petProfileSpeciesField')));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const ValueKey('speciesOption_CAT')));
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const ValueKey('petProfileNameField')), 'Momo');
  await tester.pump();
  await tester.tap(find.byKey(const ValueKey('petProfileBirthdayTile')));
  await tester.pumpAndSettle();
  // date_picker_plus：点某天即选中并关闭（无 OK 按钮）。限定 Dialog 内避免与页面文本冲突。
  await tester.tap(find.descendant(of: find.byType(Dialog), matching: find.text('1')).first);
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const ValueKey('petProfileSubmit')));
  await tester.pumpAndSettle();
}

void main() {
  /// 🔴 **建档页必须能填性别**（bug 20260827）。
  ///
  /// 此前只有**编辑**页有这个字段，建档页没有 —— 于是新用户建完档，
  /// 身份证卡上「Jenis Kelamin」永远是「-」，除非他自己再去编辑一次。
  /// 而那张卡是身份证功能的门面，一个空字段就让整张卡看着没填完。
  ///
  /// ⚠️ 选填：与体重同一理由 —— 建档转化在漏斗上比字段完整度重要，多一个必填项就多一处流失。
  /// 所以这里同时钉住「不选也能提交」。
  group('bug 20260827 · 建档页的性别字段', () {
    testWidgets('有性别选择入口，选了会随建档一起提交', (tester) async {
      // ⚠️ 用带路由的挂载：提交成功后页面会 `context.go(...)`，
      //    不带 router 的 _wrap 会在那一步抛 GoError（第一版就是这么红的）。
      final repo = _FakeRepo();
      final container = ProviderContainer(overrides: [
        profileRepositoryProvider.overrideWithValue(repo),
        petProfileProvider.overrideWith((ref) => repo.getMyProfile()),
      ]);
      addTearDown(container.dispose);
      await _pumpRouted(tester, container);

      expect(find.byKey(const ValueKey('petProfileSexTile')), findsOneWidget,
          reason: '🔴 建档页没有性别入口 ⇒ 身份证卡上那一栏永远是空的');

      await tester.tap(find.byKey(const ValueKey('petProfileSexTile')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('petSexFemaleOption')));
      await tester.pumpAndSettle();

      await _fillAndSubmit(tester);
      expect(repo.lastSex, 'FEMALE', reason: '选了性别却没发给后端');
    });

    /// 🛡 不选也必须能建档 —— 它是选填项。
    testWidgets('不选性别照样能提交（选填，不许挡住建档转化）', (tester) async {
      final repo = _FakeRepo();
      final container = ProviderContainer(overrides: [
        profileRepositoryProvider.overrideWithValue(repo),
        petProfileProvider.overrideWith((ref) => repo.getMyProfile()),
      ]);
      addTearDown(container.dispose);
      await _pumpRouted(tester, container);
      await _fillAndSubmit(tester);

      expect(repo.createCalled, isTrue);
      expect(repo.lastSex, isNull);
    });
  });


  /// 🔴 bug 20260826：**建档成功后庆祝页只闪一下，人被送回 Diary**。
  ///
  /// 机制：本页 `build` 监听「当前档案」，拿到非空就走「已有档案直达」把人送去 `/profile`（F3）。
  /// 而 `_submit` 在跳庆祝页**之前**先 `invalidate` 了这个 provider —— 重新拉到的正是刚建好的档案。
  /// 路由切换有转场动画，本页在那 300ms 内仍在树上、仍会 build ⇒ 直达分支照常触发，
  /// 把刚 go 过去的庆祝页顶掉。用户看到的就是「闪一下然后到了 Diary」。
  ///
  /// ⚠️ 老测试抓不到，是因为假仓库建档后**仍返回「没有档案」** ——
  /// 那条真实时序在测试里从不发生。已一并修正（见 `_FakeRepo.create`）。
  testWidgets('bug 20260826: 建档成功 → 停在庆祝页，不被「已有档案直达」顶掉', (tester) async {
    final repo = _FakeRepo();
    final container = ProviderContainer(overrides: [
      profileRepositoryProvider.overrideWithValue(repo),
      petProfileProvider.overrideWith((ref) => repo.getMyProfile()),
    ]);
    addTearDown(container.dispose);
    await _pumpRouted(tester, container);
    await _fillAndSubmit(tester);

    expect(find.text('created'), findsOneWidget,
        reason: '🔴 庆祝页被顶掉了 —— 这正是「闪一下就没了」的现象');
    expect(find.text('profile'), findsNothing,
        reason: '人被送去了 Diary，说明「已有档案直达」在建档成功后仍然触发');
  });


  testWidgets('AC3: 必填三项（类型/名字/生日）齐全才可提交；缺一即禁用', (tester) async {
    // 表单较长（虚线头像+分段label+多行bio），用高视口确保 ListView 全量构建（提交钮不出 fold）。
    await tester.binding.setSurfaceSize(const Size(440, 1600));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(_wrap(_FakeRepo()));
    await tester.pumpAndSettle();

    // JENIS HEWAN 下拉选择器存在
    expect(find.byKey(const ValueKey('petProfileSpeciesField')), findsOneWidget);

    // 空表单 → 禁用
    expect(_submitBtn(tester).onPressed, isNull);

    // 仅选类型（下拉弹层选 Kucing）→ 仍禁用（缺名字/生日）
    await tester.tap(find.byKey(const ValueKey('petProfileSpeciesField')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('speciesOption_CAT')));
    await tester.pumpAndSettle();
    expect(_submitBtn(tester).onPressed, isNull);

    // 类型 + 名字 → 仍禁用（缺生日，R2/AC3：生日必填）
    await tester.enterText(find.byKey(const ValueKey('petProfileNameField')), 'Momo');
    await tester.pump();
    expect(_submitBtn(tester).onPressed, isNull);

    // 补完整生日（date picker 产出完整年月日）→ 启用
    await tester.tap(find.byKey(const ValueKey('petProfileBirthdayTile')));
    await tester.pumpAndSettle();
    await tester.tap(find.descendant(of: find.byType(Dialog), matching: find.text('1')).first);
    await tester.pumpAndSettle();
    expect(_submitBtn(tester).onPressed, isNotNull);
  });

  testWidgets('AC3: 头像未传 → 渲染默认占位（add photo）；选填品种/介绍可空', (tester) async {
    await tester.pumpWidget(_wrap(_FakeRepo()));
    await tester.pumpAndSettle();
    // 头像缺省占位 widget（pet-create.html：虚线圆 + 相机角标 + Upload Foto）。
    expect(find.byIcon(Icons.photo_camera_rounded), findsOneWidget);
    expect(find.byKey(const ValueKey('petProfileAvatar')), findsOneWidget);
  });

  testWidgets('名字 maxLength=20、介绍 maxLength=30（实时计数约束）', (tester) async {
    // ⚠️ 视口要够高：表单是 ListView（懒构建），默认 800×600 下「介绍」框落在屏外
    //    ⇒ 根本没被构建，findByKey 直接 No element。
    //    2026-08-27 加了性别字段后表单又长了一截，这条因此红过一次。
    await tester.binding.setSurfaceSize(const Size(440, 2000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(_wrap(_FakeRepo()));
    await tester.pumpAndSettle();
    final field = tester.widget<TextField>(find.byKey(const ValueKey('petProfileNameField')));
    expect(field.maxLength, 20);
    final intro = tester.widget<TextField>(find.byKey(const ValueKey('petProfileIntroField')));
    expect(intro.maxLength, 30);
  });

  testWidgets('建档成功 → 回填 auth.hasPetProfile=true 且提示条标记 completed（首页提示条立即消失，无需重启）',
      (tester) async {
    final repo = _FakeRepo();
    final container = ProviderContainer(overrides: [
      profileRepositoryProvider.overrideWithValue(repo),
      petProfileProvider.overrideWith((ref) => repo.getMyProfile()), // 无既有档案 → 渲染表单
    ]);
    addTearDown(container.dispose);
    // 起始：HAS_PET 用户尚无档案。
    container
        .read(authControllerProvider.notifier)
        .applyProfile(const UserProfile(petStatus: 'HAS_PET', hasPetProfile: false));
    expect(container.read(authControllerProvider).profile?.hasPetProfile, isFalse);

    await _pumpRouted(tester, container);
    await _fillAndSubmit(tester);

    expect(repo.createCalled, isTrue);
    // 关键断言：本次 session 内状态已翻转（修复前要等下次冷启动重拉 /me 才更新）。
    expect(container.read(authControllerProvider).profile?.hasPetProfile, isTrue);
    // 正常建档（默认 origin=onboarding）→ 跳庆祝页。
    expect(find.text('created'), findsOneWidget);
  });

  testWidgets('并发 409（档案已存在）→ 同样翻转状态并直达档案', (tester) async {
    final repo = _FakeRepo()..failExists = true;
    final container = ProviderContainer(overrides: [
      profileRepositoryProvider.overrideWithValue(repo),
      petProfileProvider.overrideWith((ref) => repo.getMyProfile()),
    ]);
    addTearDown(container.dispose);
    container
        .read(authControllerProvider.notifier)
        .applyProfile(const UserProfile(petStatus: 'HAS_PET', hasPetProfile: false));

    await _pumpRouted(tester, container);
    await _fillAndSubmit(tester);

    expect(container.read(authControllerProvider).profile?.hasPetProfile, isTrue);
    expect(find.text('profile'), findsOneWidget); // 提示并直达档案 Tab
    await tester.pump(const Duration(seconds: 3)); // 走完 petProfileExists toast 定时器
  });

  // ===== V1.4.0 multi-pet Story 1.5 · AC4.5 / AC4.6 =====
  group('V1.4.0 multi-pet：建档页', () {
    const twoPets = [
      PetListItem(id: 1, name: 'Orenji', isDefault: true),
      PetListItem(id: 2, name: 'Kopi'),
    ];

    setUp(() => SharedPreferences.setMockInitialValues({}));
    tearDown(() => Analytics.debugCaptureSink = null);

    ProviderContainer containerFor(_FakeRepo repo, {List<PetListItem> pets = twoPets}) {
      final c = ProviderContainer(overrides: [
        profileRepositoryProvider.overrideWithValue(repo),
        petProfileProvider.overrideWith((ref) => repo.getMyProfile()),
        petListProvider.overrideWith((ref) async => pets),
      ]);
      addTearDown(c.dispose);
      return c;
    }

    Map<String, dynamic> problem(String slug, [Map<String, dynamic> ext = const {}]) => {
          'type': 'https://petgo/errors/$slug',
          'title': 'x',
          'status': 409,
          'detail': '后端显示串（不得渲染）',
          ...ext,
        };

    Future<void> pumpAt(WidgetTester tester, ProviderContainer c, String location) async {
      await tester.binding.setSurfaceSize(const Size(440, 1600));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final router = GoRouter(initialLocation: location, routes: [
        GoRoute(
          path: '/profile/create',
          builder: (_, s) => PetProfileCreatePage(
              addFlow: s.uri.queryParameters[PetProfileCreatePage.addFlowQuery] == '1'),
        ),
        GoRoute(path: '/profile', builder: (_, _) => const Scaffold(body: Text('profile'))),
      ]);
      addTearDown(router.dispose);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: c,
        child: MaterialApp.router(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          routerConfig: router,
        ),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('A5：从「+」添加流程（?add=1）进来 → 已有档案也不跳走，直接渲染表单', (tester) async {
      final repo = _FakeRepo()..existing = const PetProfile(id: 1, name: 'Orenji', cardToken: 'T');
      await pumpAt(tester, containerFor(repo), '/profile/create?add=1');

      expect(find.byKey(const ValueKey('petProfileSubmit')), findsOneWidget);
      expect(find.text('profile'), findsNothing);
    });

    testWidgets('code review #3：存量入口（不带 ?add=1）+ 已有宠物 → 仍「已有档案直达」Diary Tab', (tester) async {
      final repo = _FakeRepo()..existing = const PetProfile(id: 1, name: 'Orenji', cardToken: 'T');
      await pumpAt(tester, containerFor(repo), '/profile/create');

      expect(find.text('profile'), findsOneWidget);
      expect(find.byKey(const ValueKey('petProfileSubmit')), findsNothing);
    });

    testWidgets('存量入口 + 还没有宠物 → 照常渲染表单', (tester) async {
      await pumpAt(tester, containerFor(_FakeRepo(), pets: const []), '/profile/create');
      expect(find.byKey(const ValueKey('petProfileSubmit')), findsOneWidget);
    });

    testWidgets('409 pet-slot-full → A4（扩展成员里的数字 + Rp 价格），不渲染后端显示串', (tester) async {
      final repo = _FakeRepo()
        ..fail409Body = problem('pet-slot-full', {
          'canRent': true,
          'nextLevelSlot': 5,
          'petCount': 2,
          'slotTotal': 2,
          'unitPrice': 5000,
        });
      await _pumpRouted(tester, containerFor(repo));
      await _fillAndSubmit(tester);

      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.petSlotFullTitle), findsOneWidget);
      expect(find.text(l10n.petSlotFullBody(2, 2, 'Rp5.000')), findsOneWidget);
      expect(find.text('后端显示串（不得渲染）'), findsNothing);
      expect(find.text('profile'), findsNothing, reason: '名额已满不是「已有档案」，不直达');
    });

    testWidgets('409 pet-limit-reached → A4b（唯一按钮 OK）', (tester) async {
      final repo = _FakeRepo()..fail409Body = problem('pet-limit-reached');
      await _pumpRouted(tester, containerFor(repo));
      await _fillAndSubmit(tester);

      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.petSlotLimitTitle), findsOneWidget);
      expect(find.byKey(const ValueKey('petSlotLimitOk')), findsOneWidget);
      expect(find.byKey(const ValueKey('confirmSheetCancel')), findsNothing);
    });

    testWidgets('409 profile-exists → 沿用原提示并直达档案', (tester) async {
      final repo = _FakeRepo()..fail409Body = problem('profile-exists');
      await _pumpRouted(tester, containerFor(repo));
      await _fillAndSubmit(tester);

      expect(find.text('profile'), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('建档成功 → 新宠物成为当前宠物 + pet_added {pet_index, slot_source}', (tester) async {
      final events = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
      final repo = _FakeRepo()..createdId = 3;
      final c = containerFor(repo);
      await _pumpRouted(tester, c);
      await _fillAndSubmit(tester);

      expect(c.read(currentPetIdProvider), 3);
      // 没有「+」入口带来的名额摘要 → slot_source 按 default 报；只数按已加载列表（2 只）+ 1。
      expect(events.where((e) => e.$1 == 'pet_added').map((e) => e.$2), [
        {'pet_index': 3, 'slot_source': 'default'},
      ]);
    });

    testWidgets('slot_source 按建档前的名额摘要推：≤ 默认 + 等级 → level；超出 → rent', (tester) async {
      Future<Map<String, Object>?> run(PetSlotOverview o) async {
        final events = <(String, Map<String, Object>?)>[];
        Analytics.debugCaptureSink = (e, p) => events.add((e, p));
        final repo = _FakeRepo();
        final c = containerFor(repo);
        await tester.binding.setSurfaceSize(const Size(440, 1600));
        addTearDown(() => tester.binding.setSurfaceSize(null));
        await tester.pumpWidget(UncontrolledProviderScope(
          container: c,
          child: MaterialApp.router(
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            routerConfig: GoRouter(initialLocation: '/', routes: [
              GoRoute(path: '/', builder: (_, _) => PetProfileCreatePage(slotOverview: o)),
              GoRoute(path: '/profile/created', builder: (_, _) => const Text('created')),
              GoRoute(path: '/profile', builder: (_, _) => const Text('profile')),
            ]),
          ),
        ));
        await tester.pumpAndSettle();
        await _fillAndSubmit(tester);
        return events.firstWhere((e) => e.$1 == 'pet_added').$2;
      }

      PetSlotOverview o(int petCount, {int levelSlots = 1}) => PetSlotOverview(
          total: 1 + levelSlots + 1,
          cap: 5,
          defaultSlots: 1,
          levelSlots: levelSlots,
          rentalSlots: 1,
          petCount: petCount,
          rentable: 1,
          canRent: true,
          unitPrice: 5000,
          days: 30);

      expect(await run(o(0)), {'pet_index': 1, 'slot_source': 'default'});
      expect(await run(o(1)), {'pet_index': 2, 'slot_source': 'level'});
      expect(await run(o(2)), {'pet_index': 3, 'slot_source': 'rent'});
    });
  });
}
