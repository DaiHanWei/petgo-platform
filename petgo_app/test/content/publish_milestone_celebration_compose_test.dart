import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:image/image.dart' as img;
import 'package:tailtopia/core/media/media_scope.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/core/router/deep_link_routes.dart';
import 'package:tailtopia/core/startup/startup_popup_ledger.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/presentation/brag_post_entry.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/media/data/oss_uploader.dart';
import 'package:tailtopia/features/media/domain/media_upload_use_case.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/milestone_compose_preset.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_badge.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_share_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_painted_image.dart';
import 'package:tailtopia/features/content/domain/published_post.dart';

/// V1.4.2 social-topic Story 3.2 · T5.2 / T6.5：里程碑「去发布」→ 发布成功 → 当场庆祝 → 「Bagikan ke Komunitas」。
///
/// 🔴 `publishControllerProvider` 是全局单例 autoDispose：旧发布 sheet 必须先关掉、controller 释放后再开新发布页，
/// 否则新页的预填写进上一条帖子的草稿。断言：旧 sheet 已关、跳到里程碑列表、新发布页是**新的 controller**（空白草稿 + 预填）。
class _ContentRepo implements ContentRepository {
  int publishes = 0;

  @override
  Future<PublishedPost> publish({
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
      PublishedPost(id: ++publishes);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

const _done = MilestoneItem(
    code: 'C-S1', title: 'x', level: MilestoneLevel.s, trigger: MilestoneTrigger.userCheckin, completed: true);

class _MilestoneRepo implements MilestoneRepository {
  final reported = <String>[];

  @override
  Future<MilestoneItem> checkIn(String code, int contentId, {int? petId}) async => _done;

  @override
  Future<MilestoneList> getMilestones({int? petId}) async => const MilestoneList(
      petName: 'Oyen', completedCount: 1, totalCount: 1, groups: [
        MilestoneGroup(level: MilestoneLevel.s, completedCount: 1, totalCount: 1, items: [_done]),
      ]);

  @override
  Future<void> reportCelebrations(List<String> codes, {int? petId}) async => reported.addAll(codes);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Media implements MediaUploadUseCase {
  @override
  Future<OssUploadResult> uploadBytes({required MediaScope scope, required Uint8List bytes}) async =>
      const OssUploadResult(objectKey: 'k', publicUrl: 'https://cdn/x.jpg');

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _ProfileRepo implements ProfileRepository {
  @override
  Future<PetProfile?> getMyProfile({int? petId}) async => const PetProfile(id: 1, name: 'Oyen', cardToken: 'T');

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
      status: AuthStatus.authenticated, role: 'USER', profile: UserProfile(nickname: 'Aurel', hasPetProfile: true));

  @override
  Future<void> ensureRestored() => Future<void>.value();
}

void main() {
  setUp(() {
    StartupPopupLedger.instance.resetForTest();
    MilestoneBadgeAssets.debugOverride = const {};
    CardPaintedImage.precacheSvgForTest = (_) async {};
  });
  tearDown(() {
    MilestoneBadgeAssets.debugOverride = null;
    CardPaintedImage.precacheSvgForTest = null;
    MilestoneShareCardCapture.captureForTest = null;
    BragPostEntry.prepareForTest = null;
  });

  testWidgets('去发布 → 庆祝 → 发到社区：旧 sheet 关闭、跳里程碑列表、新发布页是新草稿 + 预填', (tester) async {
    tester.view.physicalSize = const Size(1200, 3200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final milestones = _MilestoneRepo();
    final content = _ContentRepo();
    final container = ProviderContainer(overrides: [
      contentRepositoryProvider.overrideWithValue(content),
      mediaUploadUseCaseProvider.overrideWithValue(_Media()),
      authControllerProvider.overrideWith(_Auth.new),
      petListProvider.overrideWith((ref) async => const [PetListItem(id: 1, name: 'Oyen')]),
      effectivePetIdProvider.overrideWithValue(const AsyncData(1)),
      milestoneRepositoryProvider.overrideWithValue(milestones),
      profileRepositoryProvider.overrideWithValue(_ProfileRepo()),
    ]);
    addTearDown(container.dispose);
    final router = GoRouter(navigatorKey: rootNavigatorKey, initialLocation: '/home', routes: [
      GoRoute(
          path: '/home',
          builder: (ctx, st) => Scaffold(
                body: TextButton(
                  onPressed: () =>
                      PublishComposePage.open(ctx, preset: ContentType.growthMoment, milestoneCode: 'C-S1'),
                  child: const Text('open'),
                ),
              )),
      GoRoute(path: DeepLinkRoutes.milestoneList, builder: (ctx, st) => const Scaffold(body: Text('milestone-list'))),
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
    // 预热里程碑列表（庆祝页取宠物名 / KOLEKSI）。
    await container.read(milestoneListProvider(1).future);
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('publishText')), 'old draft body');
    await tester.pump();
    final oldController = container.read(publishControllerProvider);
    expect(oldController.text, 'old draft body');

    await tester.tap(find.byKey(const ValueKey('publishSubmit')));
    for (var i = 0; i < 30; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(find.byKey(const ValueKey('milestoneCelebration')), findsOneWidget, reason: '回填成功 → 当场庆祝');

    MilestoneShareCardCapture.captureForTest = (_) async => Uint8List.fromList(img.encodePng(img.Image(width: 8, height: 8)));
    BragPostEntry.prepareForTest = (_) async => Uint8List.fromList(img.encodeJpg(img.Image(width: 30, height: 40)));
    await tester.tap(find.byKey(const ValueKey('milestoneShare')));
    for (var i = 0; i < 30; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('milestoneCelebration')), findsNothing);
    expect(find.byType(PublishComposePage), findsOneWidget, reason: '只剩新开的发布页（旧 sheet 已关）');
    expect(router.routerDelegate.currentConfiguration.uri.path, DeepLinkRoutes.milestoneList);
    final newController = container.read(publishControllerProvider);
    expect(newController, isNot(same(oldController)), reason: '🔴 不共用上一条帖子的 controller');
    final l10n = lookupAppLocalizations(const Locale('en'));
    final preset = milestoneComposePreset(
        item: _done, petId: 1, petName: 'Oyen', accountPets: const [], l10n: l10n, locale: const Locale('en'));
    expect(newController.text, preset.text);
    expect(newController.text, isNot(contains('old draft body')));
    expect(newController.items, hasLength(1));
    expect(newController.type, ContentType.growthMoment);
    expect(newController.petIds, [1]);
    expect(milestones.reported, contains('C-S1'), reason: '庆祝回报照常发出（经 container）');

    // 关掉新发布页 → 停在里程碑列表，不再弹回庆祝页。
    await tester.tap(find.byKey(const ValueKey('publishClose')));
    await tester.pumpAndSettle();
    expect(find.byType(PublishComposePage), findsNothing);
    expect(find.text('milestone-list'), findsOneWidget);
    expect(find.byKey(const ValueKey('milestoneCelebration')), findsNothing);
  });
}
