import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/core/startup/startup_popup_ledger.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/presentation/brag_post_entry.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/media/data/oss_uploader.dart';
import 'package:tailtopia/features/media/domain/media_upload_use_case.dart';
import 'package:tailtopia/core/media/media_scope.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/domain/celebration_pet.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/milestone_compose_preset.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_badge.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_celebration.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_share_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_painted_image.dart';

/// L0 widget（Story 8.5）：统一庆祝页渲染 / 不自动消失。视觉/计时观感 L2 待本地。
/// V1.4.2 social-topic Story 3.2：D1 吸底按钮组、D2 加载态、D3 出图失败、两个分享按钮、宿主已销毁。
MilestoneItem _item(MilestoneLevel level, {String code = 'C-X'}) => MilestoneItem(
      code: code, title: '里程碑', level: level,
      trigger: MilestoneTrigger.userCheckin, completed: true, completedAt: DateTime(2026, 6, 1));

/// 记录分享服务调用的 fake。
class _ShareLog {
  final texts = <String>[];
  final origins = <Rect?>[];
  Completer<void>? gate;
  bool fail = false;

  Future<void> call(String text, {Rect? sharePositionOrigin}) async {
    texts.add(text);
    origins.add(sharePositionOrigin);
    if (gate != null) await gate!.future;
    if (fail) throw StateError('share sheet failed');
  }
}

class _Repo implements MilestoneRepository {
  _Repo({this.fail = false});
  final bool fail;

  @override
  Future<String> createShare(String code,
      {required String title,
      required String body,
      required String locale,
      required String collectionLevels,
      required List<String> collectionCodes,
      int? petId}) async {
    if (fail) throw Exception('backend down');
    return 'Tok3n';
  }

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

class _ContentRepo implements ContentRepository {
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

Uint8List _png() => Uint8List.fromList(img.encodePng(img.Image(width: 8, height: 8)));
Uint8List _jpeg() => Uint8List.fromList(img.encodeJpg(img.Image(width: 30, height: 40)));

late _ShareLog _share;
late List<(String, Map<String, Object>?)> _events;

List<Map<String, Object>?> _eventsNamed(String name) => [for (final e in _events) if (e.$1 == name) e.$2];

/// 宿主：按钮弹庆祝页；[hostAlive] 置 false → 宿主 Consumer 被拆掉（模拟宿主页已销毁，庆祝层仍在根导航上）。
Widget _host(
  MilestoneItem item, {
  String petName = 'Momo',
  int? petId,
  List<CelebrationPet> accountPets = const [],
  List<MilestoneItem> collection = const [],
  ValueNotifier<bool>? hostAlive,
  _Repo? repo,
  List<PetListItem> pets = const [PetListItem(id: 7, name: 'Momo'), PetListItem(id: 8, name: 'Kopi')],
}) {
  final alive = hostAlive ?? ValueNotifier(true);
  return ProviderScope(
    overrides: [
      shareServiceProvider.overrideWithValue(_share.call),
      milestoneRepositoryProvider.overrideWithValue(repo ?? _Repo()),
      mediaUploadUseCaseProvider.overrideWithValue(_Media()),
      contentRepositoryProvider.overrideWithValue(_ContentRepo()),
      authControllerProvider.overrideWith(_Auth.new),
      petListProvider.overrideWith((ref) async => pets),
    ],
    child: MaterialApp(
      navigatorKey: rootNavigatorKey,
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: ValueListenableBuilder<bool>(
          valueListenable: alive,
          builder: (_, on, _) => !on
              ? const Text('host-gone')
              : Consumer(
                  builder: (context, ref, _) => Center(
                    child: ElevatedButton(
                      onPressed: () => showMilestoneCelebration(context, item,
                          petName: petName,
                          petId: petId,
                          accountPets: accountPets,
                          collection: collection,
                          path: MilestoneCelebrationPath.instant),
                      child: const Text('go'),
                    ),
                  ),
                ),
        ),
      ),
    ),
  );
}

Future<void> _open(WidgetTester tester) async {
  await tester.tap(find.text('go'));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 300));
}

const _celebration = ValueKey('milestoneCelebration');
const _community = ValueKey('milestoneShare');
const _outside = ValueKey('milestoneShareOutside');
const _seeAll = ValueKey('milestoneCelebrateSeeAll');
const _busy = ValueKey('milestoneShareBusy');

/// [key] 所在按钮外层的 D2 置灰不透明度（无置灰 → 1）。
double _opacityOf(WidgetTester tester, ValueKey<String> key) {
  final o = find.ancestor(of: find.byKey(key), matching: find.byType(Opacity));
  if (o.evaluate().isEmpty) return 1;
  return tester.widget<Opacity>(o.first).opacity;
}

void main() {
  setUp(() {
    StartupPopupLedger.instance.resetForTest();
    _share = _ShareLog();
    _events = [];
    Analytics.debugCaptureSink = (e, p) => _events.add((e, p));
    MilestoneBadgeAssets.debugOverride = const {};
    CardPaintedImage.precacheSvgForTest = (_) async {};
  });
  tearDown(() {
    Analytics.debugCaptureSink = null;
    MilestoneBadgeAssets.debugOverride = null;
    CardPaintedImage.precacheSvgForTest = null;
    MilestoneShareCardCapture.captureForTest = null;
    BragPostEntry.prepareForTest = null;
  });

  testWidgets('V1.3.4 Story 5.3：调用 showMilestoneCelebration 后 ledger 含 milestoneCelebration', (tester) async {
    await tester.pumpWidget(_host(_item(MilestoneLevel.s)));
    expect(StartupPopupLedger.instance.hasAny, isFalse);
    await _open(tester);
    expect(find.byKey(_celebration), findsOneWidget);
    expect(StartupPopupLedger.instance.recorded, {StartupPopupKind.milestoneCelebration});
  });

  testWidgets('统一 P-35 庆祝：S/M/L 都用同一全屏弹层（无半屏/全屏之分）', (tester) async {
    for (final level in MilestoneLevel.values) {
      await tester.pumpWidget(_host(_item(level)));
      await _open(tester);
      expect(find.byKey(_celebration), findsOneWidget);
      // 不再有分级 key / 开宝箱。
      expect(find.byKey(const ValueKey('milestoneChestTap')), findsNothing);
      // 关闭，进入下一级别。
      await tester.tap(find.byKey(_seeAll));
      await tester.pumpAndSettle();
    }
  });

  testWidgets('统一庆祝不自动消失（停留至用户关闭）', (tester) async {
    await tester.pumpWidget(_host(_item(MilestoneLevel.s)));
    await _open(tester);
    expect(find.byKey(_celebration), findsOneWidget);
    // 等远超旧的自动消失时长，仍在。
    await tester.pump(const Duration(seconds: 5));
    expect(find.byKey(_celebration), findsOneWidget);
    await tester.tap(find.byKey(_seeAll));
    await tester.pumpAndSettle();
    expect(find.byKey(_celebration), findsNothing);
  });

  testWidgets('卡片渲染庆祝标题 + 正文，并把 {name} 替换为宠物名（en）', (tester) async {
    await tester.pumpWidget(_host(
      MilestoneItem(
        code: 'C-S5',
        title: 'x',
        level: MilestoneLevel.s,
        trigger: MilestoneTrigger.userCheckin,
        completed: true,
        completedAt: DateTime(2026, 6, 1),
      ),
      petName: 'Momo',
    ));
    await _open(tester);
    // 标题（emoji 结尾）+ 正文均出现，且 {name} 已替换为 Momo。
    expect(find.text('First post is live! ✨'), findsOneWidget);
    expect(find.text("Momo's story can now be seen by everyone."), findsOneWidget);
  });

  // ===== V1.4.2 social-topic Story 3.2 =====

  group('D1 吸底按钮组', () {
    testWidgets('默认态：两个按钮 + 查看全部都在、文案正确、均在屏内；里程碑卡屏外预挂', (tester) async {
      await tester.pumpWidget(_host(_item(MilestoneLevel.m), petId: 7));
      await _open(tester);
      final l10n = lookupAppLocalizations(const Locale('en'));
      expect(find.byKey(_community), findsOneWidget);
      expect(find.byKey(_outside), findsOneWidget);
      expect(find.byKey(_seeAll), findsOneWidget);
      expect(find.text(l10n.milestoneCelebrateShare), findsOneWidget);
      expect(find.text(l10n.milestoneCelebrateShareOutside), findsOneWidget);
      expect(find.byKey(_busy), findsNothing);
      final screen = tester.getRect(find.byType(MaterialApp));
      for (final k in [_community, _outside, _seeAll]) {
        final r = tester.getRect(find.byKey(k));
        expect(screen.contains(r.topLeft) && r.bottom <= screen.bottom, isTrue, reason: '$k 在屏内');
      }
      // 次序：主按钮在上、次按钮居中、链接在下；两按钮同高 52。
      expect(tester.getRect(find.byKey(_community)).bottom, lessThan(tester.getRect(find.byKey(_outside)).top));
      expect(tester.getRect(find.byKey(_outside)).bottom, lessThan(tester.getRect(find.byKey(_seeAll)).top));
      expect(tester.getSize(find.byKey(_community)).height, 52);
      expect(tester.getSize(find.byKey(_outside)).height, 52);
      // 上沿渐隐层在。
      expect(find.byKey(const ValueKey('milestoneCelebrationFade')), findsOneWidget);
      // 3.1 交接：卡片弹出即屏外挂载（不等点按钮）。
      expect(find.byType(MilestoneShareCard), findsOneWidget);
      expect(tester.getRect(find.byType(MilestoneShareCard)).right, lessThan(0), reason: '挂在可视区外');
    });

    testWidgets('小屏 + L 级长内容 + KOLEKSI：内容可滚，按钮仍在首屏且不随内容滚动', (tester) async {
      await tester.binding.setSurfaceSize(const Size(360, 560));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final collection = [
        for (var i = 1; i <= 20; i++)
          MilestoneItem(
              code: 'C-S$i', title: 'x', level: MilestoneLevel.s, trigger: MilestoneTrigger.systemAuto, completed: true),
      ];
      await tester.pumpWidget(_host(_item(MilestoneLevel.l, code: 'C-L1'), collection: collection));
      await _open(tester);
      final scroll = find.descendant(of: find.byKey(_celebration), matching: find.byType(Scrollable));
      final pos = tester.state<ScrollableState>(scroll).position;
      expect(pos.maxScrollExtent, greaterThan(0), reason: '放不下 → 可滚');
      final before = tester.getRect(find.byKey(_outside));
      expect(before.bottom, lessThanOrEqualTo(560));
      expect(tester.getRect(find.byKey(_seeAll)).bottom, lessThanOrEqualTo(560));
      await tester.drag(scroll, const Offset(0, -300));
      await tester.pump();
      expect(pos.pixels, greaterThan(0));
      expect(tester.getRect(find.byKey(_outside)), before, reason: '吸底栏不随内容滚动');
    });

    testWidgets('B2 多宠态：按钮组同样吸底', (tester) async {
      await tester.pumpWidget(_host(_item(MilestoneLevel.s, code: 'C-S19'), accountPets: const [
        CelebrationPet(name: 'Momo', id: 7),
        CelebrationPet(name: 'Kopi', id: 8),
        CelebrationPet(name: 'Mochi', id: 9),
      ]));
      await _open(tester);
      expect(find.byKey(const ValueKey('milestoneCelebrationPetRow')), findsOneWidget);
      expect(find.byKey(_community), findsOneWidget);
      expect(find.byKey(_outside), findsOneWidget);
      // 吸底：按钮不在滚动区内。
      expect(find.descendant(of: find.byKey(_celebration), matching: find.byKey(_community)), findsNothing);
    });
  });

  group('D2 / D3 Bagikan ke Komunitas', () {
    testWidgets('点击 → 主按钮转圈、次按钮 40% / 链接 50% 且不可点；连点只触发一次；✕ 仍可关', (tester) async {
      final gate = Completer<Uint8List?>();
      var captures = 0;
      MilestoneShareCardCapture.captureForTest = (_) {
        captures++;
        return gate.future;
      };
      await tester.pumpWidget(_host(_item(MilestoneLevel.m), petId: 7));
      await _open(tester);
      await tester.tap(find.byKey(_community));
      await tester.pump();
      expect(find.descendant(of: find.byKey(_community), matching: find.byKey(_busy)), findsOneWidget);
      expect(_opacityOf(tester, _community), 1, reason: '主按钮保持紫底');
      expect(tester.widget<FilledButton>(find.byKey(_community)).onPressed, isNotNull, reason: '不变禁用灰');
      expect(_opacityOf(tester, _outside), 0.4);
      expect(_opacityOf(tester, _seeAll), 0.5);
      // 处理中再点任何一个都无效。
      await tester.tap(find.byKey(_community));
      await tester.tap(find.byKey(_outside), warnIfMissed: false);
      await tester.tap(find.byKey(_seeAll), warnIfMissed: false);
      await tester.pump();
      expect(captures, 1);
      expect(_share.texts, isEmpty);
      expect(find.byKey(_celebration), findsOneWidget);
      expect(_eventsNamed('brag_post_tapped'), hasLength(1));
      // ✕ 仍可点（关闭即放弃）。
      await tester.tap(find.byKey(const ValueKey('milestoneCelebrationClose')));
      await tester.pumpAndSettle();
      expect(find.byKey(_celebration), findsNothing);
      gate.complete(null);
      await tester.pumpAndSettle();
      expect(find.byType(PublishComposePage), findsNothing);
      expect(_eventsNamed('milestone_share_failed'), isEmpty, reason: '用户主动放弃不算失败');
    });

    testWidgets('D3 截图失败 → toast、庆祝页仍在、按钮恢复、milestone_share_failed{render_card}；可重试', (tester) async {
      MilestoneShareCardCapture.captureForTest = (_) async => null;
      await tester.pumpWidget(_host(_item(MilestoneLevel.s, code: 'C-S5'), petId: 7));
      await _open(tester);
      await tester.tap(find.byKey(_community));
      await tester.pump();
      await tester.pump();
      final l10n = lookupAppLocalizations(const Locale('en'));
      expect(find.text(l10n.shareCardExportError), findsOneWidget);
      expect(find.byKey(_celebration), findsOneWidget);
      expect(find.byType(PublishComposePage), findsNothing);
      expect(find.byKey(_busy), findsNothing);
      expect(_opacityOf(tester, _outside), 1);
      expect(_opacityOf(tester, _seeAll), 1);
      expect(_eventsNamed('milestone_share_failed'), [
        {'code': 'C-S5', 'stage': 'render_card'}
      ]);
      // 重试：再点仍会触发。
      await tester.tap(find.byKey(_community));
      await tester.pump();
      expect(_eventsNamed('brag_post_tapped'), hasLength(2));
      await tester.pump(const Duration(seconds: 5)); // toast 计时器
    });

    testWidgets('D3 JPEG 转换失败 → 同样不关页、render_card', (tester) async {
      MilestoneShareCardCapture.captureForTest = (_) async => _png();
      BragPostEntry.prepareForTest = (_) async => throw Exception('decode');
      await tester.pumpWidget(_host(_item(MilestoneLevel.s, code: 'C-S5'), petId: 7));
      await _open(tester);
      await tester.tap(find.byKey(_community));
      await tester.pump();
      await tester.pump();
      expect(find.byKey(_celebration), findsOneWidget);
      expect(find.text(lookupAppLocalizations(const Locale('en')).shareCardExportError), findsOneWidget);
      expect(_eventsNamed('milestone_share_failed').single!['stage'], 'render_card');
      await tester.pump(const Duration(seconds: 5));
    });

    testWidgets('复审 3-2 (a)：截图抛异常 → 按出图失败处理（toast + render_card + 按钮复位）', (tester) async {
      MilestoneShareCardCapture.captureForTest = (_) async => throw StateError('toImage failed');
      await tester.pumpWidget(_host(_item(MilestoneLevel.s, code: 'C-S5'), petId: 7));
      await _open(tester);
      await tester.tap(find.byKey(_community));
      await tester.pump();
      await tester.pump();
      expect(tester.takeException(), isNull);
      expect(find.text(lookupAppLocalizations(const Locale('en')).shareCardExportError), findsOneWidget);
      expect(find.byKey(_celebration), findsOneWidget);
      expect(find.byKey(_busy), findsNothing, reason: '不卡在转圈');
      expect(_opacityOf(tester, _outside), 1);
      expect(_eventsNamed('milestone_share_failed'), [
        {'code': 'C-S5', 'stage': 'render_card'}
      ]);
      await tester.pump(const Duration(seconds: 5));
    });

    testWidgets('复审 3-2 CONFIRMED：出图期间点 ✕，截图在退场动画内完成 → 不再 pop，宿主路由仍在、不开发布页', (tester) async {
      final gate = Completer<Uint8List?>();
      MilestoneShareCardCapture.captureForTest = (_) => gate.future;
      BragPostEntry.prepareForTest = (_) async => _jpeg();
      final item = _item(MilestoneLevel.m);
      await tester.pumpWidget(ProviderScope(
        overrides: [shareServiceProvider.overrideWithValue(_share.call)],
        child: MaterialApp(
          navigatorKey: rootNavigatorKey,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Builder(
            builder: (ctx) => ElevatedButton(
              onPressed: () => Navigator.of(ctx).push(MaterialPageRoute<void>(
                  builder: (c) => Scaffold(
                        body: Center(
                          child: ElevatedButton(
                            onPressed: () => showMilestoneCelebration(c, item,
                                petName: 'Momo', petId: 7, path: MilestoneCelebrationPath.instant),
                            child: const Text('host'),
                          ),
                        ),
                      ))),
              child: const Text('home'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('home'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('host'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      await tester.tap(find.byKey(_community));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('milestoneCelebrationClose')));
      await tester.pump(const Duration(milliseconds: 50)); // 退场动画（240ms）进行中，庆祝层仍 mounted
      expect(find.byKey(_celebration), findsOneWidget);
      gate.complete(_png());
      await tester.pump();
      await tester.pump();
      await tester.pumpAndSettle();
      expect(find.text('host'), findsOneWidget, reason: '宿主路由不能被连带弹掉');
      expect(find.byKey(_celebration), findsNothing);
      expect(find.byType(PublishComposePage), findsNothing);
      expect(_eventsNamed('milestone_share_failed'), isEmpty, reason: '用户主动放弃不算失败');
    });

    testWidgets('复审 3-2：退场动画期间连点 ✕ / 查看全部 → 只关庆祝层一次', (tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [shareServiceProvider.overrideWithValue(_share.call)],
        child: MaterialApp(
          navigatorKey: rootNavigatorKey,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Builder(
            builder: (ctx) => ElevatedButton(
              onPressed: () => Navigator.of(ctx).push(MaterialPageRoute<void>(
                  builder: (c) => Scaffold(
                        body: Center(
                          child: ElevatedButton(
                            onPressed: () => showMilestoneCelebration(c, _item(MilestoneLevel.s),
                                petName: 'Momo', path: MilestoneCelebrationPath.instant),
                            child: const Text('host'),
                          ),
                        ),
                      ))),
              child: const Text('home'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('home'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('host'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      await tester.tap(find.byKey(const ValueKey('milestoneCelebrationClose')));
      await tester.pump(const Duration(milliseconds: 50));
      await tester.tap(find.byKey(const ValueKey('milestoneCelebrationClose')), warnIfMissed: false);
      await tester.tap(find.byKey(_seeAll), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(find.text('host'), findsOneWidget);
    });

    Future<void> tapCommunityAndSettle(WidgetTester tester) async {
      MilestoneShareCardCapture.captureForTest = (_) async => _png();
      BragPostEntry.prepareForTest = (_) async => _jpeg();
      await tester.tap(find.byKey(_community));
      await tester.pumpAndSettle();
    }

    PublishComposePage page(WidgetTester tester) => tester.widget<PublishComposePage>(find.byType(PublishComposePage));

    testWidgets('成功（单宠）：庆祝页关闭 → 发布页 Diary + 预选该宠物 + 1 张图 + 预填文案；brag_post_tapped 属性', (tester) async {
      tester.view.physicalSize = const Size(1200, 3200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      final item = _item(MilestoneLevel.m, code: 'C-M2');
      await tester.pumpWidget(_host(item, petId: 7));
      await _open(tester);
      await tapCommunityAndSettle(tester);
      expect(find.byKey(_celebration), findsNothing);
      expect(find.byType(PublishComposePage), findsOneWidget);
      final l10n = lookupAppLocalizations(const Locale('en'));
      final preset = milestoneComposePreset(
          item: item, petId: 7, petName: 'Momo', accountPets: const [], l10n: l10n, locale: const Locale('en'));
      expect(page(tester).preset, ContentType.growthMoment);
      expect(page(tester).initialPetIds, [7]);
      expect(page(tester).initialImages, hasLength(1));
      expect(page(tester).entrySource, BragPostSource.milestoneCelebration);
      expect(page(tester).milestoneCode, isNull);
      expect(page(tester).initialText, preset.text);
      final c = ProviderScope.containerOf(tester.element(find.byType(PublishComposePage)))
          .read(publishControllerProvider);
      expect(c.type, ContentType.growthMoment);
      expect(c.text, preset.text);
      expect(c.items, hasLength(1));
      expect(_eventsNamed('brag_post_tapped'), [
        {'code': 'C-M2', 'level': 'M', 'source': 'milestone_celebration'}
      ]);
      expect(_eventsNamed('milestone_share_failed'), isEmpty);
      // 发布页取消 → 回原页面，不再弹回庆祝页。
      await tester.tap(find.byKey(const ValueKey('publishClose')));
      await tester.pumpAndSettle();
      expect(find.byType(PublishComposePage), findsNothing);
      expect(find.byKey(_celebration), findsNothing);
      expect(find.text('go'), findsOneWidget);
    });

    testWidgets('成功（账号级多宠）：预选全部账号宠物 + 「We」句式', (tester) async {
      tester.view.physicalSize = const Size(1200, 3200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      final item = _item(MilestoneLevel.s, code: 'C-S19');
      const pets = [CelebrationPet(name: 'Momo', id: 7), CelebrationPet(name: 'Kopi', id: 8)];
      await tester.pumpWidget(_host(item, petId: 7, accountPets: pets));
      await _open(tester);
      await tapCommunityAndSettle(tester);
      final l10n = lookupAppLocalizations(const Locale('en'));
      expect(page(tester).initialPetIds, [7, 8]);
      expect(page(tester).initialText,
          milestoneComposePreset(item: item, petId: 7, petName: 'Momo', accountPets: pets, l10n: l10n, locale: const Locale('en')).text);
      expect(page(tester).initialText, startsWith(l10n.milestoneShareComposeMulti('').split(' ').first),
          reason: '≥ 2 只 → 多宠句式');
    });
  });

  group('Bagikan ke luar', () {
    testWidgets('点击 → 次按钮转圈、主按钮 40% / 链接 50%；带链接拉面板 + 透传按钮矩形；面板返回后关闭', (tester) async {
      _share.gate = Completer<void>();
      var captures = 0;
      MilestoneShareCardCapture.captureForTest = (_) async {
        captures++;
        return null;
      };
      await tester.pumpWidget(_host(_item(MilestoneLevel.s, code: 'C-S5'), petId: 7));
      await _open(tester);
      final rect = tester.getRect(find.byKey(_outside));
      await tester.tap(find.byKey(_outside));
      await tester.pump();
      await tester.pump();
      expect(find.descendant(of: find.byKey(_outside), matching: find.byKey(_busy)), findsOneWidget);
      expect(_opacityOf(tester, _community), 0.4);
      expect(_opacityOf(tester, _seeAll), 0.5);
      await tester.tap(find.byKey(_community), warnIfMissed: false);
      await tester.tap(find.byKey(_outside));
      await tester.pump();
      expect(captures, 0);
      expect(_share.texts, hasLength(1));
      expect(_share.texts.single, endsWith('/m/Tok3n'));
      expect(_share.origins.single, rect);
      expect(find.byKey(_celebration), findsOneWidget);
      _share.gate!.complete();
      await tester.pumpAndSettle();
      expect(find.byKey(_celebration), findsNothing);
      expect(_eventsNamed('milestone_share_created'), hasLength(1));
      expect(_eventsNamed('milestone_share_failed'), isEmpty);
    });

    testWidgets('建链失败 → 纯文案照常拉面板 + milestone_share_failed{create_link}，随后关闭', (tester) async {
      await tester.pumpWidget(_host(_item(MilestoneLevel.s, code: 'C-S5'), petId: 7, repo: _Repo(fail: true)));
      await _open(tester);
      await tester.tap(find.byKey(_outside));
      await tester.pumpAndSettle();
      expect(_share.texts, hasLength(1));
      expect(_share.texts.single, isNot(contains('/m/')));
      expect(_eventsNamed('milestone_share_failed'), [
        {'code': 'C-S5', 'stage': 'create_link'}
      ]);
      expect(find.byKey(_celebration), findsNothing);
    });

    testWidgets('分享服务抛错 → 不崩、open_sheet、庆祝页仍在、按钮恢复', (tester) async {
      _share.fail = true;
      await tester.pumpWidget(_host(_item(MilestoneLevel.s, code: 'C-S5'), petId: 7));
      await _open(tester);
      await tester.tap(find.byKey(_outside));
      await tester.pump();
      await tester.pump();
      expect(tester.takeException(), isNull);
      expect(_eventsNamed('milestone_share_failed'), [
        {'code': 'C-S5', 'stage': 'open_sheet'}
      ]);
      expect(find.byKey(_celebration), findsOneWidget);
      expect(find.byKey(_busy), findsNothing);
      expect(_opacityOf(tester, _community), 1);
    });
  });

  group('宿主已销毁（D-21）', () {
    testWidgets('Bagikan ke luar 仍有效、不抛 StateError', (tester) async {
      final alive = ValueNotifier(true);
      await tester.pumpWidget(_host(_item(MilestoneLevel.s, code: 'C-S5'), hostAlive: alive));
      await _open(tester);
      alive.value = false;
      await tester.pump();
      expect(find.text('host-gone'), findsOneWidget);
      expect(find.byKey(_celebration), findsOneWidget);
      await tester.tap(find.byKey(_outside));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(_share.texts, hasLength(1));
      expect(find.byKey(_celebration), findsNothing);
    });

    testWidgets('Bagikan ke Komunitas 仍能打开发布页', (tester) async {
      tester.view.physicalSize = const Size(1200, 3200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      final alive = ValueNotifier(true);
      await tester.pumpWidget(_host(_item(MilestoneLevel.s, code: 'C-S5'), petId: 7, hostAlive: alive));
      await _open(tester);
      alive.value = false;
      await tester.pump();
      MilestoneShareCardCapture.captureForTest = (_) async => _png();
      BragPostEntry.prepareForTest = (_) async => _jpeg();
      await tester.tap(find.byKey(_community));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.byKey(_celebration), findsNothing);
      expect(find.byType(PublishComposePage), findsOneWidget);
      expect(_eventsNamed('milestone_share_failed'), isEmpty);
    });
  });
}
