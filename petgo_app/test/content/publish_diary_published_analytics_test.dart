import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/content/presentation/publish_result_page.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 multi-pet Story 3.3 · AC7（PRD §4）：`diary_published {pet_count}`。
///
/// - Diary 发布成功报一次，属性只有 `pet_count`（不带宠物名 / petId / 正文）；
/// - Moment / Tips 不报；发布失败不报。
void main() {
  late List<(String, Map<String, Object>?)> events;

  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<void> publish(WidgetTester tester,
      {required ContentType preset, List<int> tapPets = const [], bool fail = false, String? failSlug, int? status}) async {
    tester.view.physicalSize = const Size(1200, 3200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final controller = PublishController(
        repository: _Repo(fail: fail, failSlug: failSlug, status: status), uploadOne: (b) async => 'https://cdn/x.jpg')
      ..setText('Orenji main bola');
    final container = ProviderContainer(overrides: [
      publishControllerProvider.overrideWithValue(controller),
      profileRepositoryProvider.overrideWithValue(_Profiles()),
      authControllerProvider.overrideWith(_Auth.new),
      petListProvider.overrideWith((ref) async => const [
            PetListItem(id: 1, name: 'Orenji'),
            PetListItem(id: 2, name: 'Kopi'),
            PetListItem(id: 3, name: 'Mochi'),
          ]),
    ]);
    addTearDown(container.dispose);
    final router = GoRouter(initialLocation: '/home', routes: [
      GoRoute(path: '/home', builder: (c, s) => const Scaffold(body: Text('home'))),
      GoRoute(path: '/compose', builder: (c, s) => Scaffold(body: PublishComposePage(preset: preset))),
      GoRoute(path: '/publish/done', builder: (c, s) => PublishDonePage(args: s.extra as PublishResultArgs)),
      GoRoute(
          path: '/publish/rejected', builder: (c, s) => PublishRejectedPage(args: s.extra as PublishResultArgs)),
    ]);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.pumpAndSettle();
    router.push('/compose');
    await tester.pumpAndSettle();
    for (final id in tapPets) {
      await tester.tap(find.byKey(ValueKey('publishPetChip-$id')));
      await tester.pump();
    }
    events.clear();
    await tester.tap(find.byKey(const ValueKey('publishSubmit')));
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    await tester.pump(const Duration(seconds: 4)); // 失败 toast 计时器
    await tester.pumpAndSettle();
  }

  Iterable<Map<String, Object>?> diaryEvents() =>
      events.where((e) => e.$1 == 'diary_published').map((e) => e.$2);

  testWidgets('Diary 选 2 只发布成功 → diary_published{pet_count: 2}，属性只有 pet_count', (tester) async {
    await publish(tester, preset: ContentType.growthMoment, tapPets: [1, 3]);
    final got = diaryEvents().toList();
    expect(got, hasLength(1));
    expect(got.single, {'pet_count': 2});
  });

  testWidgets('Moment 发布成功 → 不报 diary_published', (tester) async {
    await publish(tester, preset: ContentType.daily);
    expect(diaryEvents(), isEmpty);
    expect(events.any((e) => e.$1 == 'content_publish_submitted'), isTrue); // 既有事件照旧
  });

  testWidgets('Diary 发布失败 → 不报', (tester) async {
    await publish(tester, preset: ContentType.growthMoment, tapPets: [2], fail: true);
    expect(diaryEvents(), isEmpty);
  });

  testWidgets('Diary 审核拦截（content-text-blocked）→ 进被拒页、不报 diary_published', (tester) async {
    await publish(tester, preset: ContentType.growthMoment, tapPets: [1], failSlug: 'content-text-blocked', status: 422);
    expect(find.byType(PublishRejectedPage), findsOneWidget);
    expect(diaryEvents(), isEmpty);
  });

  testWidgets('Diary 409 pet-read-only → 留在编辑页、不报 diary_published', (tester) async {
    await publish(tester, preset: ContentType.growthMoment, tapPets: [1, 2], failSlug: 'pet-read-only', status: 409);
    expect(find.byType(PublishDonePage), findsNothing);
    expect(find.byKey(const ValueKey('publishPetChips')), findsOneWidget);
    expect(diaryEvents(), isEmpty);
  });
}

class _Repo implements ContentRepository {
  _Repo({this.fail = false, this.failSlug, this.status});
  final bool fail;
  final String? failSlug;
  final int? status;

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
    if (failSlug != null) {
      final ro = RequestOptions(path: '/api/v1/content-posts');
      throw DioException(
        requestOptions: ro,
        type: DioExceptionType.badResponse,
        response: Response(requestOptions: ro, statusCode: status, data: {
          'type': 'https://petgo/errors/$failSlug',
          'status': status,
          'petId': 2,
        }),
      );
    }
    if (fail) {
      throw DioException(
          requestOptions: RequestOptions(path: '/api/v1/content-posts'), type: DioExceptionType.connectionError);
    }
    return 9;
  }
}

class _Profiles implements ProfileRepository {
  @override
  Future<PetProfile?> getMyProfile({int? petId}) async =>
      PetProfile(id: petId ?? 1, name: 'Orenji', cardToken: 'T');

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(nickname: 'Aurel', hasPetProfile: true),
      );

  @override
  Future<void> ensureRestored() => Future<void>.value();
}
