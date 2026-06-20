import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/content/presentation/publish_landing_page.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/presentation/pet_profile_create_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 发布请求抛注入异常的 fake（模拟审核 422）。
class _ThrowRepo implements ContentRepository {
  _ThrowRepo(this.slug);
  final String slug;

  @override
  Future<int> publish({
    required ContentType type,
    int? petId,
    String? text,
    List<String> imageUrls = const [],
    DateTime? eventDate,
    required String idempotencyKey,
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
    String? text,
    List<String> imageUrls = const [],
    DateTime? eventDate,
    required String idempotencyKey,
  }) async =>
      1;
}

class _FakeProfileRepo implements ProfileRepository {
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
      PetProfile(id: 1, name: name, cardToken: 'T', petType: petType, birthday: birthday);

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
      PetProfile(id: 1, name: name ?? 'x', cardToken: 'T');
}

PublishController _controller(ContentRepository repo) =>
    PublishController(repository: repo, uploadOne: (b) async => 'https://cdn/x.jpg');

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

  testWidgets('AC8: 文字命中违规 → 文字拦截提示，停留发布页（可重提）', (tester) async {
    final controller = _controller(_ThrowRepo('content-text-blocked'))..setText('ayo main judi');
    final container = ProviderContainer(
        overrides: [publishControllerProvider.overrideWithValue(controller)]);
    addTearDown(container.dispose);

    await tester.pumpWidget(_composeApp(container));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('publishSubmit')));
    await tester.pumpAndSettle();

    final l10n = await _en();
    expect(find.text(l10n.publishTextBlocked), findsOneWidget);
    expect(find.byType(PublishComposePage), findsOneWidget); // 未关闭，草稿保留
    expect(controller.text, 'ayo main judi');
  });

  testWidgets('AC8: 图片命中违规 → 图片拦截提示', (tester) async {
    final controller = _controller(_ThrowRepo('content-image-blocked'))..setText('teks');
    final container = ProviderContainer(
        overrides: [publishControllerProvider.overrideWithValue(controller)]);
    addTearDown(container.dispose);

    await tester.pumpWidget(_composeApp(container));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('publishSubmit')));
    await tester.pumpAndSettle();

    final l10n = await _en();
    expect(find.text(l10n.publishImageBlocked), findsOneWidget);
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

    // 填必填三项：类型 + 名字 + 生日。
    await tester.tap(find.byKey(const ValueKey('petType_DOG')));
    await tester.pump();
    await tester.enterText(find.byKey(const ValueKey('petProfileNameField')), 'Rocky');
    await tester.pump();
    await tester.ensureVisible(find.byKey(const ValueKey('petProfileBirthdayTile')));
    await tester.tap(find.byKey(const ValueKey('petProfileBirthdayTile')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();

    // 提交建档 → graySelectPublish 分支：回发布着陆页 + 重开发布页预选成长日历。
    await tester.ensureVisible(find.byKey(const ValueKey('petProfileSubmit')));
    await tester.tap(find.byKey(const ValueKey('petProfileSubmit')));
    await tester.pumpAndSettle();

    expect(find.byType(PublishComposePage), findsOneWidget); // 重开发布页（跳过庆祝页）
    expect(find.byKey(const ValueKey('publishEventDate')), findsOneWidget); // 预选成长日历 → 事件日期字段在
  });
}
