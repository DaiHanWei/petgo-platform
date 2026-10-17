import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/content/video/video_picker.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

class _Repo implements ContentRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _PetRepo implements ProfileRepository {
  @override
  Future<PetProfile?> getMyProfile() async => const PetProfile(id: 1, name: 'Oyen', cardToken: 'T');

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

AuthController Function() _auth(int vipLevel) => () => _Auth(vipLevel);

class _Auth extends AuthController {
  _Auth(this.vipLevel);
  final int vipLevel;

  @override
  AuthState build() => AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(
          nickname: 'Official',
          petStatus: 'HAS_PET',
          hasPetProfile: true,
          onboardingCompleted: true,
          vipLevel: vipLevel,
        ),
      );

  @override
  Future<void> ensureRestored() => Future<void>.value();
}

/// L0 widget：专题线 video Story 1.5 —— 视频入口可见性、互斥、检查结果展示。
void main() {
  const fixtures = 'test/fixtures/video';

  Future<PublishController> pump(
    WidgetTester tester, {
    int vipLevel = 10,
    ContentType? preset,
    String? checkinToken,
    List<Uint8List>? initialImages,
    bool videoEntryAllowed = true,
    String? pickedPath,
    Future<String?> Function()? picker,
  }) async {
    tester.view.physicalSize = const Size(1200, 3200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final controller = PublishController(repository: _Repo(), uploadOne: (b) async => 'x');
    final container = ProviderContainer(overrides: [
      publishControllerProvider.overrideWithValue(controller),
      profileRepositoryProvider.overrideWithValue(_PetRepo()),
      authControllerProvider.overrideWith(_auth(vipLevel)),
      videoPickerProvider.overrideWithValue(picker ?? () async => pickedPath),
    ]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
        home: Scaffold(
          body: PublishComposePage(
            preset: preset,
            placeCheckinToken: checkinToken,
            initialImages: initialImages,
            videoEntryAllowed: videoEntryAllowed,
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    return controller;
  }

  final entry = find.byKey(const ValueKey('publishAddVideo'));

  testWidgets('等级 1 看不到视频入口', (tester) async {
    await pump(tester, vipLevel: 1);
    expect(entry, findsNothing);
  });

  testWidgets('等级 10 在 Momen / Edukasi 下能看到视频入口', (tester) async {
    final c = await pump(tester, preset: ContentType.daily);
    expect(entry, findsOneWidget);
    c.setType(ContentType.knowledge);
    await tester.pump();
    expect(entry, findsOneWidget);
  });

  testWidgets('按当前类型判断：Diary 下隐藏，切到 Momen 出现（底栏 + 在 Diary Tab 也一样，FR-5）', (tester) async {
    final c = await pump(tester, preset: ContentType.growthMoment);
    expect(c.type, ContentType.growthMoment);
    expect(entry, findsNothing);
    c.setType(ContentType.daily);
    await tester.pump();
    expect(entry, findsOneWidget);
    c.setType(ContentType.growthMoment);
    await tester.pump();
    expect(entry, findsNothing);
  });

  testWidgets('成长日历入口（videoEntryAllowed=false）切到 Momen 也不显示', (tester) async {
    final c = await pump(tester, preset: ContentType.growthMoment, videoEntryAllowed: false);
    c.setType(ContentType.daily);
    await tester.pump();
    expect(entry, findsNothing);
  });

  testWidgets('打卡后发帖不显示', (tester) async {
    await pump(tester, preset: ContentType.daily, checkinToken: 'c' * 32);
    expect(entry, findsNothing);
  });

  testWidgets('晒图预填不显示', (tester) async {
    await pump(tester, preset: ContentType.daily, initialImages: [Uint8List.fromList([1, 2, 3])]);
    expect(entry, findsNothing);
  });

  testWidgets('已有图片时入口禁用', (tester) async {
    final c = await pump(tester, preset: ContentType.daily);
    c.addImage(Uint8List.fromList([1, 2, 3]));
    await tester.pump();
    final btn = tester.widget<ButtonStyleButton>(entry);
    expect(btn.onPressed, isNull);
  });

  testWidgets('选到合规视频：出现预览格，成长日记 chip 禁用', (tester) async {
    final c = await pump(tester, pickedPath: '$fixtures/ok_avc_720x1280.mp4');
    expect(c.type, ContentType.growthMoment); // 有宠物档案 → 默认 Diary，此时没有视频入口
    c.setType(ContentType.daily);
    await tester.pump();
    await tester.runAsync(() async {
      await tester.tap(entry);
      await Future<void>.delayed(const Duration(milliseconds: 300));
    });
    await tester.pumpAndSettle();
    expect(c.video, isNotNull);
    expect(c.type, ContentType.daily);
    expect(find.byKey(const ValueKey('videoDraftTile')), findsOneWidget);
    expect(find.text('0:04 · 720×1280'), findsOneWidget);
    expect(find.byKey(const ValueKey('publishAddImage')), findsNothing);
    expect(entry, findsNothing);

    c.setType(ContentType.growthMoment);
    expect(c.type, ContentType.daily);

    await tester.tap(find.byKey(const ValueKey('videoDraftRemove')));
    await tester.pumpAndSettle();
    expect(c.video, isNull);
    expect(find.byKey(const ValueKey('publishAddImage')), findsOneWidget);
  });

  testWidgets('选到不合规视频：逐条列出，不进入后续流程', (tester) async {
    final c = await pump(tester, preset: ContentType.daily, pickedPath: '$fixtures/hevc_not_faststart.mp4');
    await tester.runAsync(() async {
      await tester.tap(entry);
      await Future<void>.delayed(const Duration(milliseconds: 300));
    });
    await tester.pumpAndSettle();
    expect(c.video, isNull);
    expect(find.byKey(const ValueKey('videoSpecErrorCard')), findsOneWidget);
    expect(find.byKey(const ValueKey('videoSpecFailure_videoCodec')), findsOneWidget);
    expect(find.byKey(const ValueKey('videoSpecFailure_faststart')), findsOneWidget);
    expect(find.textContaining('HEVC'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('videoSpecDismiss')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('videoSpecErrorCard')), findsNothing);
  });

  testWidgets('取消选择：无任何变化', (tester) async {
    final c = await pump(tester, preset: ContentType.daily);
    await tester.tap(entry);
    await tester.pumpAndSettle();
    expect(c.video, isNull);
    expect(find.byKey(const ValueKey('videoSpecErrorCard')), findsNothing);
  });

  testWidgets('选择器抛异常（already_active / 读文件失败）：按取消处理，不向 UI 抛（code review）', (tester) async {
    final c = await pump(tester, preset: ContentType.daily, picker: () async => throw StateError('already_active'));
    await tester.tap(entry);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(c.video, isNull);
  });

  testWidgets('选择器未返回前连点：只打开一次（code review）', (tester) async {
    var calls = 0;
    final pending = Completer<String?>();
    await pump(tester, preset: ContentType.daily, picker: () {
      calls++;
      return pending.future;
    });
    await tester.tap(entry);
    await tester.pump();
    await tester.tap(entry, warnIfMissed: false);
    await tester.pump();
    expect(calls, 1);
    pending.complete(null);
    await tester.pumpAndSettle();
  });
}
