import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/content/presentation/publish_landing_page.dart';
import 'package:tailtopia/features/content/presentation/publish_result_page.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/presentation/pet_profile_create_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';

/// 发布请求抛注入异常的 fake（模拟审核 422）。
class _ThrowRepo implements ContentRepository {
  _ThrowRepo(this.slug);
  final String slug;

  @override
  Future<int> publish({
    required ContentType type,
    int? petId,
    List<int> petIds = const [],
    String? text,
    List<String> imageUrls = const [],
    List<ImageSize?> imageSizes = const [],
    List<double?> imageCropOffsets = const [],
    DateTime? eventDate,
    required String idempotencyKey,
    bool syncToMoment = true,
    List<int> mentionedUserIds = const [],
    String? placeCheckinToken,
  }) async {
    final ro = RequestOptions(path: '/api/v1/content-posts');
    throw DioException(
      requestOptions: ro,
      type: DioExceptionType.badResponse,
      response: Response(
        requestOptions: ro,
        statusCode: 422,
        data: {'type': 'https://petgo/errors/$slug', 'status': 422},
      ),
    );
  }
}

/// 正常返回 id 的 fake（用于建档返回续发链路）。
class _OkRepo implements ContentRepository {
  @override
  Future<int> publish({
    required ContentType type,
    int? petId,
    List<int> petIds = const [],
    String? text,
    List<String> imageUrls = const [],
    List<ImageSize?> imageSizes = const [],
    List<double?> imageCropOffsets = const [],
    DateTime? eventDate,
    required String idempotencyKey,
    bool syncToMoment = true,
    List<int> mentionedUserIds = const [],
    String? placeCheckinToken,
  }) async =>
      1;
}

class _FakeProfileRepo implements ProfileRepository {
  @override
  Future<void> deleteMyProfile({int? petId}) async {}

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
  }) async =>
      PetProfile(id: 1, name: name, cardToken: 'T', petType: petType, birthday: birthday);

  @override
  Future<PetProfile?> getMyProfile({int? petId}) async => null;

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

/// 有宠物档案的档案仓库：成长日历发布必带 pet_id（bug 564 成功路径用）。
class _WithPetProfileRepo extends _FakeProfileRepo {
  @override
  Future<PetProfile?> getMyProfile({int? petId}) async =>
      const PetProfile(id: 1, name: 'Oyen', cardToken: 'T');
}

PublishController _controller(ContentRepository repo) =>
    PublishController(repository: repo, uploadOne: (b) async => 'https://cdn/x.jpg');

/// 已登录 + 明确无档案（未建/已删——删档对称置 false，PR#34 #12）。
class _NoArchiveAuth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(
          nickname: 'Aurel',
          petStatus: 'HAS_PET', // F18：删档后 petStatus 不改，仍 HAS_PET 但无档案
          hasPetProfile: false,
          onboardingCompleted: true,
        ),
      );

  @override
  Future<void> ensureRestored() => Future<void>.value();
}

/// 已登录 + 有档案。
class _WithArchiveAuth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(
          nickname: 'Aurel',
          petStatus: 'HAS_PET',
          hasPetProfile: true,
          onboardingCompleted: true,
        ),
      );

  @override
  Future<void> ensureRestored() => Future<void>.value();
}

Future<AppLocalizations> _en() => AppLocalizations.delegate.load(const Locale('en'));

/// 高视口：发帖页事件日期行位于文字框之后（原型顺序），默认 800×600 会被 ListView 懒加载裁掉。
void _tallView(WidgetTester tester) {
  tester.view.physicalSize = const Size(1200, 3200);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

Widget _composeApp(ProviderContainer container, {ContentType? preset, DateTime? presetEventDate}) {
  return UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(body: PublishComposePage(preset: preset, presetEventDate: presetEventDate)),
    ),
  );
}

void main() {
  // ===== AC8 审核拦截提示（F10） =====

  // 提交期间会展示「审核中」覆盖层（含无限旋转的进度环），故用显式 pump 跨过最小展示时长，
  // 不能 pumpAndSettle（会因动画永不 settle 而超时）。被拒后跳 P-39c 整页。
  Future<void> pumpThroughReviewing(WidgetTester tester) async {
    await tester.pump(); // 审核中覆盖层出现
    await tester.pump(const Duration(seconds: 1)); // 跨过 ~900ms 最小展示
    await tester.pump(); // pop sheet + push 结果页
    await tester.pump(const Duration(milliseconds: 500)); // 路由切换
  }

  Widget routerApp(ProviderContainer container, GoRouter router) => UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
        ),
      );

  GoRouter publishFlowRouter() => GoRouter(
        initialLocation: '/home',
        routes: [
          GoRoute(path: '/home', builder: (c, s) => const Scaffold(body: Text('home'))),
          GoRoute(path: '/compose', builder: (c, s) => const Scaffold(body: PublishComposePage())),
          GoRoute(
              path: '/publish/done',
              builder: (c, s) => PublishDonePage(args: s.extra as PublishResultArgs)),
          GoRoute(
              path: '/publish/rejected',
              builder: (c, s) => PublishRejectedPage(args: s.extra as PublishResultArgs)),
        ],
      );

  testWidgets('AC8: 文字命中违规 → 跳内容被拒页（P-39c）', (tester) async {
    _tallView(tester);
    final controller = _controller(_ThrowRepo('content-text-blocked'))..setText('ayo main judi');
    final container = ProviderContainer(
        overrides: [publishControllerProvider.overrideWithValue(controller)]);
    addTearDown(container.dispose);

    final router = publishFlowRouter();
    await tester.pumpWidget(routerApp(container, router));
    await tester.pumpAndSettle();
    router.push('/compose');
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('publishSubmit')));
    await pumpThroughReviewing(tester);

    final l10n = await _en();
    // P-39c：标题 + 文字违规拒因。
    expect(find.text(l10n.publishRejectedHeading), findsOneWidget);
    expect(find.text(l10n.publishRejectedReasonText), findsOneWidget);
  });

  testWidgets('AC8: 图片命中违规 → 跳内容被拒页（图片拒因）', (tester) async {
    _tallView(tester);
    final controller = _controller(_ThrowRepo('content-image-blocked'))..setText('teks');
    final container = ProviderContainer(
        overrides: [publishControllerProvider.overrideWithValue(controller)]);
    addTearDown(container.dispose);

    final router = publishFlowRouter();
    await tester.pumpWidget(routerApp(container, router));
    await tester.pumpAndSettle();
    router.push('/compose');
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('publishSubmit')));
    await pumpThroughReviewing(tester);

    final l10n = await _en();
    expect(find.text(l10n.publishRejectedReasonImage), findsOneWidget);
  });

  testWidgets('AC8: 发布成功 → 跳发布成功页（P-39）', (tester) async {
    _tallView(tester);
    final controller = _controller(_OkRepo())..setText('Oyen sehat hari ini');
    final container = ProviderContainer(
        overrides: [publishControllerProvider.overrideWithValue(controller)]);
    addTearDown(container.dispose);

    final router = publishFlowRouter();
    await tester.pumpWidget(routerApp(container, router));
    await tester.pumpAndSettle();
    router.push('/compose');
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('publishSubmit')));
    await pumpThroughReviewing(tester);

    final l10n = await _en();
    expect(find.text(l10n.publishDoneViewFeed), findsOneWidget);
  });

  // ===== AC5 成长日历事件日期（F9） =====

  testWidgets('AC5: 成长日历显示事件日期字段，默认今天', (tester) async {
    _tallView(tester); // 事件日期行在文字框之后（原型顺序），需高视口避免被 ListView 懒加载裁掉
    final controller = _controller(_OkRepo());
    final container = ProviderContainer(overrides: [
      publishControllerProvider.overrideWithValue(controller),
      profileRepositoryProvider.overrideWithValue(_FakeProfileRepo()),
    ]);
    addTearDown(container.dispose);

    await tester.pumpWidget(_composeApp(container, preset: ContentType.growthMoment));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('publishEventDate')), findsOneWidget);
    // 控制器持有去时分的今天（日期展示已改本地化富文本「Tanggal momen: 1 May 2024」，不再断言 YYYY-MM-DD）。
    final today = DateTime.now();
    expect(controller.eventDate, DateTime(today.year, today.month, today.day));
  });

  testWidgets('AC5: 入口默认值分流 — presetEventDate（日历格子日期）优先于今天', (tester) async {
    _tallView(tester);
    final controller = _controller(_OkRepo());
    final container = ProviderContainer(overrides: [
      publishControllerProvider.overrideWithValue(controller),
      profileRepositoryProvider.overrideWithValue(_FakeProfileRepo()),
    ]);
    addTearDown(container.dispose);

    await tester.pumpWidget(_composeApp(container,
        preset: ContentType.growthMoment, presetEventDate: DateTime(2024, 5, 1)));
    await tester.pumpAndSettle();

    // presetEventDate 优先于今天（展示为本地化富文本，断言控制器值即可）。
    expect(find.byKey(const ValueKey('publishEventDate')), findsOneWidget);
    expect(controller.eventDate, DateTime(2024, 5, 1));
  });

  testWidgets('AC5: 日常类型不显示事件日期字段', (tester) async {
    final controller = _controller(_OkRepo())..setText('hi');
    final container = ProviderContainer(
        overrides: [publishControllerProvider.overrideWithValue(controller)]);
    addTearDown(container.dispose);

    await tester.pumpWidget(_composeApp(container)); // 默认 daily
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('publishEventDate')), findsNothing);
  });

  // ===== bug 20260819：无档案（未建/已删）时 Diary preset 过档案闸 =====
  //
  // 「＋」入口（shell）有 canGrowth 守卫，但深链 / 日历格 / 里程碑「去发布」等入口直传 preset。
  // 明确无档案时 Diary preset 须回落 Moment（chip 本就灰置，但此前 preset 能把它「选中」，
  // 用户写完提交才被「先建档」拒）。

  testWidgets('无档案 + Diary preset → 回落 Moment（不选中灰置的 Diary）', (tester) async {
    _tallView(tester);
    final controller = _controller(_OkRepo());
    final container = ProviderContainer(overrides: [
      publishControllerProvider.overrideWithValue(controller),
      profileRepositoryProvider.overrideWithValue(_FakeProfileRepo()),
      authControllerProvider.overrideWith(() => _NoArchiveAuth()),
    ]);
    addTearDown(container.dispose);

    await tester.pumpWidget(_composeApp(container, preset: ContentType.growthMoment));
    await tester.pumpAndSettle();

    expect(controller.type, ContentType.daily); // Diary preset 被闸 → Moment
    expect(find.byKey(const ValueKey('publishEventDate')), findsNothing); // 无成长日历事件日期
  });

  testWidgets('有档案 + Diary preset → 保持 Diary（闸只拦明确无档案）', (tester) async {
    _tallView(tester);
    final controller = _controller(_OkRepo());
    final container = ProviderContainer(overrides: [
      publishControllerProvider.overrideWithValue(controller),
      profileRepositoryProvider.overrideWithValue(_FakeProfileRepo()),
      authControllerProvider.overrideWith(() => _WithArchiveAuth()),
      // V1.4.0 multi-pet Story 3.3：Diary 宠物 chips 读宠物列表（只 1 只 → F4 自动选中）。
      petListProvider.overrideWith((ref) async => const [PetListItem(id: 1, name: 'Oyen')]),
    ]);
    addTearDown(container.dispose);

    await tester.pumpWidget(_composeApp(container, preset: ContentType.growthMoment));
    await tester.pumpAndSettle();

    expect(controller.type, ContentType.growthMoment);
    expect(find.byKey(const ValueKey('publishEventDate')), findsOneWidget);
  });

  // ===== AC7 B/C 灰选建档返回（F15 跳过庆祝页） =====

  testWidgets('AC7: 灰选建档完成跳过庆祝页 → 回发布页预选成长日历', (tester) async {
    _tallView(tester);
    final container = ProviderContainer(overrides: [
      profileRepositoryProvider.overrideWithValue(_FakeProfileRepo()), // getMyProfile null → 渲染表单
      petProfileProvider.overrideWith((ref) async => null),
      publishControllerProvider.overrideWithValue(_controller(_OkRepo())), // 重开发布页用
    ]);
    addTearDown(container.dispose);

    final router = GoRouter(
      initialLocation: '/profile/create?origin=graySelectPublish',
      routes: [
        GoRoute(
            path: '/profile/create',
            builder: (c, s) => const PetProfileCreatePage()),
        GoRoute(
            path: '/publish',
            builder: (c, s) => PublishLandingPage(
                  preset: s.uri.queryParameters['preset'] == 'growth-calendar'
                      ? ContentType.growthMoment
                      : null,
                )),
        GoRoute(path: '/home', builder: (c, s) => const Scaffold(body: Text('home'))),
        GoRoute(path: '/profile', builder: (c, s) => const Scaffold(body: Text('profile'))),
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

    // 填必填三项：类型（下拉弹层选 Anjing）+ 名字 + 生日。
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

    // 提交建档 → graySelectPublish 分支：回发布着陆页 + 重开发布页预选成长日历。
    await tester.ensureVisible(find.byKey(const ValueKey('petProfileSubmit')));
    await tester.tap(find.byKey(const ValueKey('petProfileSubmit')));
    await tester.pumpAndSettle();

    expect(find.byType(PublishComposePage), findsOneWidget); // 重开发布页（跳过庆祝页）
    expect(find.byKey(const ValueKey('publishEventDate')), findsOneWidget); // 预选成长日历 → 事件日期字段在
  });

  // ===== 发布成功页两个出口（2026-08-05 用户反馈） =====
  //
  // 回归的缺陷：主次两个按钮都去 `/home`，次按钮等于主按钮的重复，用户没有回自己档案的出口。
  // 文案与目的地绑定：主=「View in Social」→ Social Tab；次=「Back to Diary」→ Diary Tab。

  Widget doneRouterApp(GoRouter router) => MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      );

  GoRouter doneRouter() => GoRouter(
        initialLocation: '/publish/done',
        routes: [
          GoRoute(path: '/home', builder: (c, s) => const Scaffold(body: Text('SOCIAL_TAB'))),
          GoRoute(path: '/profile', builder: (c, s) => const Scaffold(body: Text('DIARY_TAB'))),
          GoRoute(
              path: '/publish/done',
              builder: (c, s) => const PublishDonePage(
                  args: PublishResultArgs(excerpt: 'x', typeLabel: 'Diary', photoCount: 0))),
        ],
      );

  testWidgets('发布成功页主按钮「View in Social」→ Social Tab', (tester) async {
    _tallView(tester);
    await tester.pumpWidget(doneRouterApp(doneRouter()));
    await tester.pumpAndSettle();

    final l10n = await _en();
    expect(find.text(l10n.publishDoneViewFeed), findsOneWidget);
    expect(l10n.publishDoneViewFeed.toLowerCase().contains('feed'), isFalse,
        reason: '文案已统一为 Social，不得再出现 Feed');

    await tester.tap(find.byKey(const ValueKey('publishDoneViewFeed')));
    await tester.pumpAndSettle();
    expect(find.text('SOCIAL_TAB'), findsOneWidget);
  });

  testWidgets('发布成功页次按钮「Back to Diary」→ Diary Tab（此前也去 Social，与主按钮重复）', (tester) async {
    _tallView(tester);
    await tester.pumpWidget(doneRouterApp(doneRouter()));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('publishDoneBackHome')));
    await tester.pumpAndSettle();
    expect(find.text('DIARY_TAB'), findsOneWidget);
    expect(find.text('SOCIAL_TAB'), findsNothing);
  });

  // ===== 底部可见性提示跟随实际可见范围（2026-08-05 用户实机反馈） =====
  //
  // 回归的缺陷：底部那句写死「所有人可见」，Diary 关掉同步开关后与开关副标题
  //「没人看得到」同屏互相打脸。判定必须与 PublishController.isSharing 一致。

  testWidgets('Diary 默认（开关开）→ 底部提示为公开态', (tester) async {
    _tallView(tester);
    final container = ProviderContainer(overrides: [
      publishControllerProvider.overrideWithValue(_controller(_OkRepo())),
      profileRepositoryProvider.overrideWithValue(_FakeProfileRepo()),
    ]);
    addTearDown(container.dispose);
    final l10n = await _en();

    await tester.pumpWidget(_composeApp(container, preset: ContentType.growthMoment));
    await tester.pumpAndSettle();

    expect(find.text(l10n.publishPublicNotice), findsOneWidget);
    expect(find.text(l10n.publishPrivateNotice), findsNothing);
  });

  testWidgets('Diary 关掉同步开关 → 底部提示改私密态，且不再出现「所有人可见」', (tester) async {
    _tallView(tester);
    final container = ProviderContainer(overrides: [
      publishControllerProvider.overrideWithValue(_controller(_OkRepo())),
      profileRepositoryProvider.overrideWithValue(_FakeProfileRepo()),
    ]);
    addTearDown(container.dispose);
    final l10n = await _en();

    await tester.pumpWidget(_composeApp(container, preset: ContentType.growthMoment));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('publishSyncToggle')));
    await tester.pumpAndSettle();

    expect(find.text(l10n.publishPrivateNotice), findsOneWidget);
    expect(find.text(l10n.publishPublicNotice), findsNothing,
        reason: '开关已关闭，底部绝不能还说「所有人可见」——这正是本次修的缺陷');
  });

  testWidgets('Moment（无同步开关）恒公开 → 底部提示始终是公开态', (tester) async {
    _tallView(tester);
    final container = ProviderContainer(overrides: [
      publishControllerProvider.overrideWithValue(_controller(_OkRepo())),
      profileRepositoryProvider.overrideWithValue(_FakeProfileRepo()),
    ]);
    addTearDown(container.dispose);
    final l10n = await _en();

    await tester.pumpWidget(_composeApp(container, preset: ContentType.daily));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('publishSyncSwitch')), findsNothing); // Moment 不渲染开关
    expect(find.text(l10n.publishPublicNotice), findsOneWidget);
  });

  // ===== bug 20260922-520：里程碑「去发布」✕ 放弃后白屏 =====
  //
  // 回归的缺陷：/publish 着陆页只在无 milestoneCode 时 go('/home')；带 milestoneCode 且用户 ✕ 放弃，
  // 没人导航 → 空白着陆页留在栈顶。修后：放弃即回到发起页（里程碑列表）；冷启无前驱时兜底去里程碑列表。
  GoRouter milestonePublishRouter(String initial) => GoRouter(
        initialLocation: initial,
        routes: [
          GoRoute(
              path: '/profile/milestones',
              builder: (c, s) => Scaffold(
                    body: TextButton(
                      onPressed: () => c.push(
                          '/publish?preset=growth-calendar&milestoneCode=C-S1'),
                      child: const Text('milestones'),
                    ),
                  )),
          GoRoute(
              path: '/publish',
              builder: (c, s) => PublishLandingPage(
                    preset: ContentType.growthMoment,
                    milestoneCode: s.uri.queryParameters['milestoneCode'],
                  )),
          GoRoute(path: '/home', builder: (c, s) => const Scaffold(body: Text('home'))),
        ],
      );

  ProviderContainer milestoneContainer() => ProviderContainer(overrides: [
        publishControllerProvider.overrideWithValue(_controller(_OkRepo())),
        profileRepositoryProvider.overrideWithValue(_FakeProfileRepo()),
        authControllerProvider.overrideWith(() => _WithArchiveAuth()),
      // V1.4.0 multi-pet Story 3.3：Diary 宠物 chips 读宠物列表（只 1 只 → F4 自动选中）。
      petListProvider.overrideWith((ref) async => const [PetListItem(id: 1, name: 'Oyen')]),
      ]);

  testWidgets('bug 520: 里程碑去发布 → ✕ 放弃 → 回里程碑列表（不留白屏）', (tester) async {
    _tallView(tester);
    final container = milestoneContainer();
    addTearDown(container.dispose);
    final router = milestonePublishRouter('/profile/milestones');

    await tester.pumpWidget(routerApp(container, router));
    await tester.pumpAndSettle();
    await tester.tap(find.text('milestones'));
    await tester.pumpAndSettle();
    expect(find.byType(PublishComposePage), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('publishClose')));
    await tester.pumpAndSettle();

    expect(find.byType(PublishComposePage), findsNothing);
    expect(find.byType(PublishLandingPage), findsNothing); // 空白着陆页已出栈
    expect(find.text('milestones'), findsOneWidget);
    expect(router.routerDelegate.currentConfiguration.uri.path, '/profile/milestones');
  });

  testWidgets('bug 520: 冷启深链（无前驱）✕ 放弃 → 兜底去里程碑列表', (tester) async {
    _tallView(tester);
    final container = milestoneContainer();
    addTearDown(container.dispose);
    final router = milestonePublishRouter(
        '/publish?preset=growth-calendar&milestoneCode=C-S1');

    await tester.pumpWidget(routerApp(container, router));
    await tester.pumpAndSettle();
    expect(find.byType(PublishComposePage), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('publishClose')));
    await tester.pumpAndSettle();

    expect(find.byType(PublishLandingPage), findsNothing);
    expect(find.text('milestones'), findsOneWidget);
  });
  // ===== bug 20260924-564：Diary 日历「+」进发布、放弃后被送到 Social =====
  //
  // 回归的缺陷：/publish 着陆页在无 milestoneCode 时无条件 go('/home') 清栈 ——
  // 从日历进来 ✕ 放弃，落到 Social 而不是回日历；发布成功时 compose 刚 push 的成功页也被一并冲掉。
  // 修后：所有入口统一「本页重新成为栈顶才离开」，能 pop 回上一页，无上一页才兜底 /home。
  GoRouter calendarPublishRouter(String initial) => GoRouter(
        initialLocation: initial,
        routes: [
          GoRoute(
              path: '/profile',
              builder: (c, s) => Scaffold(
                    body: TextButton(
                      onPressed: () => c.push(
                          '/publish?preset=growth-calendar&date=2026-09-01'),
                      child: const Text('diary-calendar'),
                    ),
                  )),
          GoRoute(
              path: '/publish',
              builder: (c, s) => PublishLandingPage(
                    preset: ContentType.growthMoment,
                    presetEventDate:
                        DateTime.tryParse(s.uri.queryParameters['date'] ?? ''),
                  )),
          GoRoute(
              path: '/publish/done',
              builder: (c, s) => PublishDonePage(args: s.extra as PublishResultArgs)),
          GoRoute(path: '/home', builder: (c, s) => const Scaffold(body: Text('home'))),
        ],
      );

  testWidgets('bug 564: 日历入口 → ✕ 放弃 → 回 Diary 日历（不去 Social）', (tester) async {
    _tallView(tester);
    final container = milestoneContainer();
    addTearDown(container.dispose);
    final router = calendarPublishRouter('/profile');

    await tester.pumpWidget(routerApp(container, router));
    await tester.pumpAndSettle();
    await tester.tap(find.text('diary-calendar'));
    await tester.pumpAndSettle();
    expect(find.byType(PublishComposePage), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('publishClose')));
    await tester.pumpAndSettle();

    expect(find.byType(PublishComposePage), findsNothing);
    expect(find.byType(PublishLandingPage), findsNothing); // 空白着陆页已出栈
    expect(find.text('diary-calendar'), findsOneWidget);
    expect(find.text('home'), findsNothing);
    expect(router.routerDelegate.currentConfiguration.uri.path, '/profile');
  });

  testWidgets('bug 564: 冷启 / go 进来（无上一页）✕ 放弃 → 兜底 /home', (tester) async {
    _tallView(tester);
    final container = milestoneContainer();
    addTearDown(container.dispose);
    final router = calendarPublishRouter('/publish?preset=growth-calendar');

    await tester.pumpWidget(routerApp(container, router));
    await tester.pumpAndSettle();
    expect(find.byType(PublishComposePage), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('publishClose')));
    await tester.pumpAndSettle();

    expect(find.byType(PublishLandingPage), findsNothing);
    expect(find.text('home'), findsOneWidget);
  });

  testWidgets('bug 564: 日历入口发布成功 → 成功页不被冲掉；从成功页返回 → 回日历', (tester) async {
    _tallView(tester);
    final container = ProviderContainer(overrides: [
      publishControllerProvider
          .overrideWithValue(_controller(_OkRepo())..setText('Oyen sehat hari ini')),
      profileRepositoryProvider.overrideWithValue(_WithPetProfileRepo()),
      // V1.4.0 multi-pet：发布页按当前宠物取档案（requestPetIdProvider ← effectivePetIdProvider）。
      effectivePetIdProvider.overrideWithValue(const AsyncData(1)),
      authControllerProvider.overrideWith(() => _WithArchiveAuth()),
      // V1.4.0 multi-pet Story 3.3：Diary 宠物 chips 读宠物列表（只 1 只 → F4 自动选中）。
      petListProvider.overrideWith((ref) async => const [PetListItem(id: 1, name: 'Oyen')]),
    ]);
    addTearDown(container.dispose);
    final router = calendarPublishRouter('/profile');

    await tester.pumpWidget(routerApp(container, router));
    await tester.pumpAndSettle();
    await tester.tap(find.text('diary-calendar'));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('publishSubmit')));
    await pumpThroughReviewing(tester);
    await tester.pumpAndSettle();

    final l10n = await _en();
    expect(find.byType(PublishDonePage), findsOneWidget); // 成功页还在
    expect(find.text(l10n.publishDoneViewFeed), findsOneWidget);

    // 系统返回：成功页出栈 → 着陆页重新露出 → 自己再出栈 → 回日历。
    router.pop();
    await tester.pumpAndSettle();
    expect(find.byType(PublishLandingPage), findsNothing);
    expect(find.text('diary-calendar'), findsOneWidget);
    expect(router.routerDelegate.currentConfiguration.uri.path, '/profile');
  });

  _multiPetGroup();
}

// ===== V1.4.0 multi-pet Story 3.3：Diary 发布页宠物 chips（F1 / F2 / F4）· 预选 · 请求体 · 409 只读 =====

/// 记录请求的 fake；[readOnlyPetId] 非空 → 抛 409 pet-read-only（带 petId）。
class _PetRecordingRepo implements ContentRepository {
  _PetRecordingRepo({this.readOnlyPetId});
  final int? readOnlyPetId;
  List<int>? lastPetIds;
  int? lastPetId;
  int calls = 0;

  @override
  Future<int> publish({
    required ContentType type,
    int? petId,
    List<int> petIds = const [],
    String? text,
    List<String> imageUrls = const [],
    List<ImageSize?> imageSizes = const [],
    List<double?> imageCropOffsets = const [],
    DateTime? eventDate,
    required String idempotencyKey,
    bool syncToMoment = true,
    List<int> mentionedUserIds = const [],
    String? placeCheckinToken,
  }) async {
    calls++;
    lastPetIds = petIds;
    lastPetId = petId;
    if (readOnlyPetId != null) {
      final ro = RequestOptions(path: '/api/v1/content-posts');
      throw DioException(
        requestOptions: ro,
        type: DioExceptionType.badResponse,
        response: Response(
          requestOptions: ro,
          statusCode: 409,
          data: {'type': 'https://petgo/errors/pet-read-only', 'status': 409, 'petId': readOnlyPetId},
        ),
      );
    }
    return 42;
  }
}

/// 按 petId 返回档案的仓库（F3：分享取第一只关联宠物）。
class _ByIdProfileRepo extends _FakeProfileRepo {
  final requested = <int?>[];
  @override
  Future<PetProfile?> getMyProfile({int? petId}) async {
    requested.add(petId);
    return PetProfile(id: petId ?? 0, name: 'Pet$petId', cardToken: 'card-$petId');
  }
}

/// 记录里程碑回填打卡（抛错 = best-effort 失败，发布照常走成功页）。
class _MilestoneRecorder implements MilestoneRepository {
  final checkIns = <String>[];

  @override
  Future<MilestoneItem> checkIn(String code, int contentId, {int? petId}) async {
    checkIns.add('$code@$petId');
    throw StateError('not needed');
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

const _orenji = PetListItem(id: 1, name: 'Orenji');
const _kopi = PetListItem(id: 2, name: 'Kopi');
const _mochi = PetListItem(id: 3, name: 'Mochi');
const _lockedPet = PetListItem(id: 4, name: 'Bubu', status: PetListItem.statusReadOnly);

void _multiPetGroup() {
  group('V1.4.0 multi-pet Story 3.3 · 发布页选宠物', () {
    late int listFetches;

    ProviderContainer container0(
      PublishController c, {
      List<PetListItem> pets = const [_orenji, _kopi, _mochi, _lockedPet],
      ProfileRepository? profiles,
      int? currentPetId,
    }) {
      listFetches = 0;
      final container = ProviderContainer(overrides: [
        publishControllerProvider.overrideWithValue(c),
        profileRepositoryProvider.overrideWithValue(profiles ?? _FakeProfileRepo()),
        authControllerProvider.overrideWith(() => _WithArchiveAuth()),
        petListProvider.overrideWith((ref) async {
          listFetches++;
          return pets;
        }),
        if (currentPetId != null) effectivePetIdProvider.overrideWithValue(AsyncData(currentPetId)),
      ]);
      addTearDown(container.dispose);
      return container;
    }

    Widget app(ProviderContainer container, PublishComposePage page) => UncontrolledProviderScope(
          container: container,
          child: MaterialApp(
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            locale: const Locale('en'),
            home: Scaffold(body: page),
          ),
        );

    bool submitEnabled(WidgetTester tester) =>
        tester.widget<FilledButton>(find.byKey(const ValueKey('publishSubmit'))).onPressed != null;

    String hintOf(WidgetTester tester) =>
        tester.widget<TextField>(find.byKey(const ValueKey('publishText'))).decoration!.hintText!;

    testWidgets('F1：3 只 ACTIVE + 1 只只读 → 3 个 chip（只读不出现）；默认不选、Bagikan 置灰、占位 F-1', (tester) async {
      _tallView(tester);
      final c = _controller(_OkRepo())..setText('hari ini');
      await tester.pumpWidget(app(container0(c), const PublishComposePage(preset: ContentType.growthMoment)));
      await tester.pumpAndSettle();
      final l10n = await _en();

      expect(find.byKey(const ValueKey('publishPetChips')), findsOneWidget);
      for (final id in [1, 2, 3]) {
        expect(find.byKey(ValueKey('publishPetChip-$id')), findsOneWidget);
      }
      expect(find.byKey(const ValueKey('publishPetChip-4')), findsNothing);
      expect(find.text('Bubu'), findsNothing);
      expect(c.petIds, isEmpty);
      expect(submitEnabled(tester), isFalse);
      expect(hintOf(tester), l10n.diaryPetsComposeHint);
    });

    testWidgets('F2：选 2 只 → 按创建顺序存、占位「Orenji & Kopi」、Bagikan 可点；✓ 只出在选中的', (tester) async {
      _tallView(tester);
      final c = _controller(_OkRepo())..setText('hari ini');
      await tester.pumpWidget(app(container0(c), const PublishComposePage(preset: ContentType.growthMoment)));
      await tester.pumpAndSettle();
      final l10n = await _en();

      // 先点 Kopi 再点 Orenji：存的仍是创建顺序。
      await tester.tap(find.byKey(const ValueKey('publishPetChip-2')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('publishPetChip-1')));
      await tester.pump();
      expect(c.petIds, [1, 2]);
      expect(hintOf(tester), l10n.publishHintGrowth('Orenji & Kopi'));
      expect(submitEnabled(tester), isTrue);
      expect(find.byKey(const ValueKey('publishPetChipCheck')), findsNWidgets(2));

      // ≥ 3 只：第一只 + dkk.（en：and others）
      await tester.tap(find.byKey(const ValueKey('publishPetChip-3')));
      await tester.pump();
      expect(hintOf(tester), l10n.publishHintGrowth(l10n.diaryPetsMany('Orenji')));
    });

    testWidgets('F4：只 1 只正常宠物 → 自动选中、Bagikan 可点；取消后回 F1 置灰', (tester) async {
      _tallView(tester);
      final c = _controller(_OkRepo())..setText('hari ini');
      await tester.pumpWidget(app(container0(c, pets: const [_orenji, _lockedPet]),
          const PublishComposePage(preset: ContentType.growthMoment)));
      await tester.pumpAndSettle();
      final l10n = await _en();

      expect(c.petIds, [1]);
      expect(submitEnabled(tester), isTrue);
      expect(hintOf(tester), l10n.publishHintGrowth('Orenji'));

      await tester.tap(find.byKey(const ValueKey('publishPetChip-1')));
      await tester.pump();
      expect(c.petIds, isEmpty);
      expect(submitEnabled(tester), isFalse);
    });

    testWidgets('F5：Moment / Tips 不出 chips 行；切回 Diary 已选保留', (tester) async {
      _tallView(tester);
      final c = _controller(_OkRepo())..setText('hari ini');
      await tester.pumpWidget(app(container0(c), const PublishComposePage(preset: ContentType.growthMoment)));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('publishPetChip-2')));
      await tester.pump();

      await tester.tap(find.byKey(const ValueKey('seg_DAILY')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('publishPetChips')), findsNothing);
      expect(submitEnabled(tester), isTrue); // Moment 不受宠物条件影响
      await tester.tap(find.byKey(const ValueKey('seg_KNOWLEDGE')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('publishPetChips')), findsNothing);

      await tester.tap(find.byKey(const ValueKey('seg_GROWTH_MOMENT')));
      await tester.pumpAndSettle();
      expect(c.petIds, [2]);
    });

    testWidgets('AC1.7：initialPetIds 预选（过滤掉只读 / 不存在的）', (tester) async {
      _tallView(tester);
      final c = _controller(_OkRepo())..setText('hari ini');
      await tester.pumpWidget(app(container0(c),
          const PublishComposePage(preset: ContentType.growthMoment, initialPetIds: [3, 4, 99, 1])));
      await tester.pumpAndSettle();
      expect(c.petIds, [1, 3]);
    });

    testWidgets('AC1.7：里程碑「去发布」→ 预选 Diary Tab 当前宠物', (tester) async {
      _tallView(tester);
      final c = _controller(_OkRepo())..setText('hari ini');
      await tester.pumpWidget(app(container0(c, currentPetId: 2),
          const PublishComposePage(preset: ContentType.growthMoment, milestoneCode: 'C-S1')));
      await tester.pumpAndSettle();
      expect(c.petIds, [2]);
    });

    testWidgets('AC1.7：里程碑入口当前宠物只读 → 不预选', (tester) async {
      _tallView(tester);
      final c = _controller(_OkRepo())..setText('hari ini');
      await tester.pumpWidget(app(container0(c, currentPetId: 4),
          const PublishComposePage(preset: ContentType.growthMoment, milestoneCode: 'C-S1')));
      await tester.pumpAndSettle();
      expect(c.petIds, isEmpty);
    });

    testWidgets('code review #2：里程碑预选在等当前宠物期间用户已点 chip → 不覆盖用户选择', (tester) async {
      _tallView(tester);
      final c = _controller(_OkRepo())..setText('hari ini');
      final current = Completer<int?>();
      final container = ProviderContainer(overrides: [
        publishControllerProvider.overrideWithValue(c),
        profileRepositoryProvider.overrideWithValue(_FakeProfileRepo()),
        authControllerProvider.overrideWith(() => _WithArchiveAuth()),
        petListProvider.overrideWith((ref) async => const [_orenji, _kopi, _mochi]),
        effectivePetIdProvider.overrideWith((ref) => current.future),
      ]);
      addTearDown(container.dispose);
      await tester.pumpWidget(app(container,
          const PublishComposePage(preset: ContentType.growthMoment, milestoneCode: 'C-S1')));
      await tester.pump();
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('publishPetChip-3')));
      await tester.pump();
      current.complete(2); // 当前宠物这时才解析出来
      await tester.pumpAndSettle();
      expect(c.petIds, [3]);
    });

    Future<_MilestoneRecorder> publishFromMilestone(WidgetTester tester, {required bool untickMilestonePet}) async {
      _tallView(tester);
      final milestones = _MilestoneRecorder();
      final c = _controller(_PetRecordingRepo())..setText('hari ini');
      final container = ProviderContainer(overrides: [
        publishControllerProvider.overrideWithValue(c),
        profileRepositoryProvider.overrideWithValue(_FakeProfileRepo()),
        authControllerProvider.overrideWith(() => _WithArchiveAuth()),
        petListProvider.overrideWith((ref) async => const [_orenji, _kopi]),
        effectivePetIdProvider.overrideWithValue(const AsyncData(2)),
        milestoneRepositoryProvider.overrideWithValue(milestones),
      ]);
      addTearDown(container.dispose);
      final router = GoRouter(initialLocation: '/home', routes: [
        GoRoute(path: '/home', builder: (ctx, st) => const Scaffold(body: Text('home'))),
        GoRoute(
            path: '/compose',
            builder: (ctx, st) => const Scaffold(
                body: PublishComposePage(preset: ContentType.growthMoment, milestoneCode: 'C-S1'))),
        GoRoute(path: '/publish/done', builder: (ctx, st) => PublishDonePage(args: st.extra as PublishResultArgs)),
      ]);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('en'),
        ),
      ));
      await tester.pumpAndSettle();
      router.push('/compose');
      await tester.pumpAndSettle();
      expect(c.petIds, [2]); // 预选里程碑宠物
      await tester.tap(find.byKey(const ValueKey('publishPetChip-1')));
      if (untickMilestonePet) await tester.tap(find.byKey(const ValueKey('publishPetChip-2')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('publishSubmit')));
      await tester.pump();
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();
      return milestones;
    }

    testWidgets('code review #2：里程碑宠物仍在所选 → 按它回填打卡', (tester) async {
      final m = await publishFromMilestone(tester, untickMilestonePet: false);
      expect(m.checkIns, ['C-S1@2']);
    });

    testWidgets('code review #2：用户取消了里程碑宠物 → 不回填（帖子没关联它，后端会拒）', (tester) async {
      final m = await publishFromMilestone(tester, untickMilestonePet: true);
      expect(m.checkIns, isEmpty);
    });

    testWidgets('AC1.8：宠物列表失败 → 行内重试；点重试重新拉', (tester) async {
      _tallView(tester);
      final c = _controller(_OkRepo())..setText('hari ini');
      var fail = true;
      var fetches = 0;
      final container = ProviderContainer(overrides: [
        publishControllerProvider.overrideWithValue(c),
        profileRepositoryProvider.overrideWithValue(_FakeProfileRepo()),
        authControllerProvider.overrideWith(() => _WithArchiveAuth()),
        petListProvider.overrideWith((ref) async {
          fetches++;
          if (fail) throw StateError('offline');
          return const [_orenji, _kopi];
        }),
      ]);
      addTearDown(container.dispose);
      await tester.pumpWidget(app(container, const PublishComposePage(preset: ContentType.growthMoment)));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('publishPetChipsRetry')), findsOneWidget);
      fail = false;
      await tester.tap(find.byKey(const ValueKey('publishPetChipsRetry')));
      await tester.pumpAndSettle();
      expect(fetches, greaterThanOrEqualTo(2));
      expect(find.byKey(const ValueKey('publishPetChip-2')), findsOneWidget);
    });

    testWidgets('AC3.1 / F3：发布带 petIds（创建顺序）+ petId 第一只；成功页分享取第一只宠物', (tester) async {
      _tallView(tester);
      final repo = _PetRecordingRepo();
      final profiles = _ByIdProfileRepo();
      final c = _controller(repo)..setText('hari ini');
      final container = container0(c, profiles: profiles);
      final router = GoRouter(initialLocation: '/home', routes: [
        GoRoute(path: '/home', builder: (ctx, st) => const Scaffold(body: Text('home'))),
        GoRoute(
            path: '/compose',
            builder: (ctx, st) => const Scaffold(body: PublishComposePage(preset: ContentType.growthMoment))),
        GoRoute(path: '/publish/done', builder: (ctx, st) => PublishDonePage(args: st.extra as PublishResultArgs)),
      ]);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('en'),
        ),
      ));
      await tester.pumpAndSettle();
      router.push('/compose');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('publishPetChip-3')));
      await tester.tap(find.byKey(const ValueKey('publishPetChip-2')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('publishSubmit')));
      await tester.pump();
      await tester.pump(const Duration(seconds: 1));
      await tester.pumpAndSettle();

      expect(repo.lastPetIds, [2, 3]);
      expect(repo.lastPetId, 2);
      expect(profiles.requested.last, 2); // F3：第一只关联宠物
      final l10n = await _en();
      expect(find.text(l10n.publishDoneShareGrowthBookNamed('Pet2')), findsOneWidget);
    });

    testWidgets('AC3.2：409 pet-read-only → Toast 只读文案 + 刷新宠物列表 + 去掉该 chip 选中，不进成功页', (tester) async {
      _tallView(tester);
      final repo = _PetRecordingRepo(readOnlyPetId: 2);
      final c = _controller(repo)..setText('hari ini');
      final container = container0(c);
      await tester.pumpWidget(app(container, const PublishComposePage(preset: ContentType.growthMoment)));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('publishPetChip-1')));
      await tester.tap(find.byKey(const ValueKey('publishPetChip-2')));
      await tester.pump();
      final before = listFetches;

      await tester.tap(find.byKey(const ValueKey('publishSubmit')));
      await tester.pump();
      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      final l10n = await _en();
      expect(find.text(l10n.petReadonlyBlockTitle('Kopi')), findsOneWidget);
      expect(c.petIds, [1]);
      expect(listFetches, greaterThan(before));
      expect(find.byType(PublishDonePage), findsNothing);
      expect(find.byKey(const ValueKey('publishPetChips')), findsOneWidget); // 仍在编辑页
      await tester.pump(const Duration(seconds: 4)); // toast 计时器走完
    });
  });
}
