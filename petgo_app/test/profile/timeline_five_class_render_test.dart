import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/place/domain/checkin_place_ref.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/diary_demo_data.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/presentation/growth_archive_page.dart';
import 'package:tailtopia/features/profile/presentation/widgets/timeline_item_tile.dart';
import 'package:tailtopia/features/profile/presentation/pet_insights_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 3.3 · L0：真实时间线的五类渲染与跳转。
///
/// 两条主线：
/// 1. **NFR-7**：真实时间线与游客示例本用的是**同一个** [TimelineItemTile] —— 同一份条目数据
///    从「内置常量」与「后端下发」两条路径喂进去，产出的 tile 类型必须一致；
/// 2. **AC5/AC6**：组件不持有跳转，真实态由本页按 `itemType` 注入四类目标 —— 逐类点击核对落点。
class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

const _profile = PetProfile(id: 1, name: 'Momo', cardToken: 'T', breed: 'Shiba');

TimelineItem _item(TimelineItemType type,
        {int? postId,
        String? milestoneCode,
        String? milestoneLevel,
        String? healthRecordType,
        String? sourceType,
        String? sourceRef,
        String? idCardSerial,
        List<String> imageUrls = const []}) =>
    TimelineItem(
      kind: TimelineKind.unknown,
      itemType: type,
      date: DateTime.utc(2026, 5, 20, 9),
      eventDate: DateTime.utc(2026, 5, 20),
      postId: postId,
      imageUrls: imageUrls,
      text: '配文',
      milestoneCode: milestoneCode,
      milestoneLevel: milestoneLevel,
      healthRecordType: healthRecordType,
      sourceType: sourceType,
      sourceRef: sourceRef,
      idCardSerial: idCardSerial,
    );

/// 把成长档案页挂在一个迷你 router 上：四个跳转目标各渲染一个可断言的标记页。
Widget _wrapRouted(TimelinePage page) {
  final router = GoRouter(
    initialLocation: '/profile',
    routes: [
      GoRoute(path: '/profile', builder: (_, _) => const GrowthArchivePage()),
      GoRoute(path: '/content/:id', builder: (_, s) => Text('content:${s.pathParameters['id']}')),
      GoRoute(path: '/profile/milestones', builder: (_, _) => const Text('milestones')),
      GoRoute(path: '/profile/health', builder: (_, _) => const Text('health-list')),
      // V1.3.0 Story 5.1：KTP 整体平移到聚合页之下。时间线的「证件卡」条目
      // **直达身份证页**，不绕聚合页 —— 点一条具体记录却落在功能列表上是走回头路。
      GoRoute(
          path: PetInsightsRoutes.idCard, builder: (_, _) => const Text('id-card')),
      GoRoute(path: '/triage/result/:id', builder: (_, s) => Text('triage:${s.pathParameters['id']}')),
      GoRoute(
          path: '/places/:token',
          builder: (_, s) =>
              Text('place:${s.pathParameters['token']}:${s.uri.queryParameters['from']}')),
    ],
  );
  return ProviderScope(
    overrides: [
      authControllerProvider.overrideWith(() => _TestAuthController(const AuthState(
            status: AuthStatus.authenticated,
            role: 'USER',
            profile: UserProfile(petStatus: 'HAS_PET', hasPetProfile: true),
          ))),
      petProfileProvider.overrideWith((ref) async => _profile),
      timelineFirstPageProvider.overrideWith((ref) async => page),
      archiveStatsProvider.overrideWith((ref) async => const ArchiveStats(
          happyMomentCount: 1, consultCount: 0, milestoneCompleted: 1, milestoneTotal: 30)),
      shareFabAnimatedShownProvider.overrideWith((ref) async => true),
    ],
    child: MaterialApp.router(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      routerConfig: router,
    ),
  );
}

Future<void> _pump(WidgetTester tester, TimelinePage page) async {
  await tester.binding.setSurfaceSize(const Size(500, 2400));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(_wrapRouted(page));
  await tester.pumpAndSettle();
}

void main() {
  group('AC1 复用同一套组件（NFR-7）', () {
    testWidgets('真实时间线渲染的是共用组件 TimelineItemTile', (tester) async {
      await _pump(tester, TimelinePage(items: [_item(TimelineItemType.happyMoment, postId: 9)]));

      expect(find.byType(TimelineItemTile), findsOneWidget);
      expect(find.byKey(const ValueKey('timelineHappyCard')), findsOneWidget);
    });

    testWidgets('同一份数据经「内置常量」与「后端下发」两条路径 → 产出同类型 tile', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      // 内置常量侧（游客示例本）的五类构成
      final demoTypes = DiaryDemoData.items(l10n).map((e) => e.resolvedType).toSet();

      // 后端下发侧：用同样的五类喂真实时间线
      final page = TimelinePage(items: [
        _item(TimelineItemType.milestoneBanner, milestoneCode: 'C-L2', milestoneLevel: 'L'),
        _item(TimelineItemType.idCardIssued, idCardSerial: '#00842'),
        _item(TimelineItemType.happyMomentMilestone,
            postId: 3, milestoneCode: 'D-S13', imageUrls: const ['asset:assets/demo_diary/demo_diary_night.jpg']),
        _item(TimelineItemType.healthRecord, healthRecordType: 'VACCINE'),
        _item(TimelineItemType.happyMoment, postId: 7),
      ]);
      await _pump(tester, page);

      // 五类各自的 tile 形态都出现了，且与游客侧是同一批 key（同一组件产出）
      // V1.3.2 Story 1.6：打卡条目（placeCheckinBanner）**不进**游客示例——示例以 UI 稿 A1 的 9 条为权威，
      // A1 没有打卡条目；组件复用已由同一个 TimelineItemTile 保证（见下方「打卡条目」组）。
      // V1.3.2 Story 3.3：Tailsonality 条目同理不进游客示例（本 story 不新增示例数据）。
      const notInDemo = {TimelineItemType.placeCheckinBanner, TimelineItemType.tailsonalityBanner};
      expect(demoTypes, containsAll(TimelineItemType.values.where((t) => !notInDemo.contains(t))));
      for (final t in notInDemo) {
        expect(demoTypes, isNot(contains(t)));
      }
      expect(find.byKey(const ValueKey('timelineMilestoneBanner')), findsOneWidget);
      expect(find.byKey(const ValueKey('timelineIdCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('timelineMilestoneStamp')), findsOneWidget);
      expect(find.byKey(const ValueKey('timelineHealthCapsule')), findsOneWidget);
      expect(find.byKey(const ValueKey('timelineHappyCard')), findsNWidgets(2)); // ① + ②
    });
  });

  group('AC5/AC6 真实态跳转由本页注入（组件本身不持有跳转）', () {
    testWidgets('类① 照片卡 → 内容详情页', (tester) async {
      await _pump(tester, TimelinePage(items: [_item(TimelineItemType.happyMoment, postId: 42)]));

      await tester.tap(find.byKey(const ValueKey('timelineTileTap_HAPPY_MOMENT')));
      await tester.pumpAndSettle();
      expect(find.text('content:42'), findsOneWidget);
    });

    testWidgets('类② 卡片 → 内容详情；金徽章 → 里程碑列表（两个独立可点区域）', (tester) async {
      final page = TimelinePage(items: [
        _item(TimelineItemType.happyMomentMilestone, postId: 42, milestoneCode: 'D-S13')
      ]);

      await _pump(tester, page);
      await tester.tap(find.byKey(const ValueKey('timelineMilestoneStampTap')));
      await tester.pumpAndSettle();
      expect(find.text('milestones'), findsOneWidget, reason: '徽章跳里程碑列表');

      await _pump(tester, page);
      await tester.tap(find.byKey(const ValueKey('timelineTileTap_HAPPY_MOMENT_MILESTONE')));
      await tester.pumpAndSettle();
      expect(find.text('content:42'), findsOneWidget, reason: '卡片本体跳内容详情');
    });

    testWidgets('类③ banner → 里程碑列表', (tester) async {
      await _pump(
          tester,
          TimelinePage(items: [
            _item(TimelineItemType.milestoneBanner, milestoneCode: 'C-L2', milestoneLevel: 'L')
          ]));

      await tester.tap(find.byKey(const ValueKey('timelineTileTap_MILESTONE_BANNER')));
      await tester.pumpAndSettle();
      expect(find.text('milestones'), findsOneWidget);
    });

    testWidgets('类④ 结构化健康记录 → 健康记录列表（时间线内无编辑入口）', (tester) async {
      await _pump(tester,
          TimelinePage(items: [_item(TimelineItemType.healthRecord, healthRecordType: 'VACCINE')]));

      await tester.tap(find.byKey(const ValueKey('timelineTileTap_HEALTH_RECORD')));
      await tester.pumpAndSettle();
      expect(find.text('health-list'), findsOneWidget);
    });

    testWidgets('类④ 问诊存档 → 仍跳对应问诊结果页（bug 20260706-259 行为不回退）', (tester) async {
      await _pump(
          tester,
          TimelinePage(items: [
            _item(TimelineItemType.healthRecord,
                healthRecordType: 'CONSULT', sourceType: 'AI_TRIAGE', sourceRef: 'triage:88')
          ]));

      await tester.tap(find.byKey(const ValueKey('timelineTileTap_HEALTH_RECORD')));
      await tester.pumpAndSettle();
      expect(find.text('triage:88'), findsOneWidget);
    });

    testWidgets('类⑤ 证件卡 → 身份证页', (tester) async {
      await _pump(tester,
          TimelinePage(items: [_item(TimelineItemType.idCardIssued, idCardSerial: '#00842')]));

      await tester.tap(find.byKey(const ValueKey('timelineTileTap_ID_CARD_ISSUED')));
      await tester.pumpAndSettle();
      expect(find.text('id-card'), findsOneWidget);
    });
  });

  group('AC2/AC3 空态与失败态', () {
    testWidgets('近空态：只有一条也不塌陷，页头与条目都在', (tester) async {
      await _pump(tester, TimelinePage(items: [_item(TimelineItemType.happyMoment, postId: 1)]));

      expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('diaryHealthEntry')), findsOneWidget);
      expect(find.byType(TimelineItemTile), findsOneWidget);
    });

    testWidgets('空态：引导 CTA 在，且不出现任何条目', (tester) async {
      await _pump(tester, const TimelinePage(items: []));

      expect(find.byKey(const ValueKey('timelineEmptyCta')), findsOneWidget);
      expect(find.byType(TimelineItemTile), findsNothing);
    });

    testWidgets('首屏失败：整页不白屏，页头保留 + 内容区给重试入口（F13 口径）', (tester) async {
      await tester.binding.setSurfaceSize(const Size(500, 2400));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(ProviderScope(
        overrides: [
          authControllerProvider.overrideWith(() => _TestAuthController(const AuthState(
                status: AuthStatus.authenticated,
                role: 'USER',
                profile: UserProfile(petStatus: 'HAS_PET', hasPetProfile: true),
              ))),
          petProfileProvider.overrideWith((ref) async => _profile),
          timelineFirstPageProvider.overrideWith((ref) async => throw Exception('boom')),
          archiveStatsProvider.overrideWith((ref) async => const ArchiveStats(
              happyMomentCount: 0, consultCount: 0, milestoneCompleted: 0, milestoneTotal: 30)),
          shareFabAnimatedShownProvider.overrideWith((ref) async => true),
        ],
        child: const MaterialApp(
          locale: Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: GrowthArchivePage(),
        ),
      ));
      await tester.pumpAndSettle();

      // 只有时间线区降级：页头（信息卡 / 入口 / 里程碑条）仍在 —— 不整页白屏
      expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
      expect(find.byKey(const ValueKey('timelineError')), findsOneWidget);
      expect(find.byKey(const ValueKey('timelineRetry')), findsOneWidget);
    });
  });

  group('V1.3.2 Story 1.6 打卡条目', () {
    TimelineItem checkin({required bool available}) => TimelineItem(
          kind: TimelineKind.placeCheckin,
          itemType: TimelineItemType.placeCheckinBanner,
          date: DateTime.utc(2026, 5, 20, 9),
          eventDate: DateTime.utc(2026, 5, 20),
          checkinPlace: CheckinPlaceRef(token: 'p' * 32, name: 'Kopi Kucing', available: available),
        );

    testWidgets('ACTIVE：通栏 + 场所名 + 定位图标（非 emoji）；点击 → 场所详情 from=diary', (tester) async {
      final semantics = tester.ensureSemantics();
      await _pump(tester, TimelinePage(items: [checkin(available: true)]));
      expect(find.byKey(const ValueKey('timelineCheckinBanner')), findsOneWidget);
      expect(find.text('Kopi Kucing'), findsOneWidget);
      expect(find.byIcon(Icons.place_outlined), findsOneWidget);
      expect(find.text('📍'), findsNothing);
      expect(find.byKey(const ValueKey('timelineHappyCard')), findsNothing, reason: '不得回落照片卡');
      expect(find.bySemanticsLabel(RegExp('Check-in di Kopi Kucing')), findsOneWidget);
      semantics.dispose();

      await tester.tap(find.byKey(const ValueKey('timelineCheckinBanner')));
      await tester.pumpAndSettle();
      expect(find.text('place:${'p' * 32}:diary'), findsOneWidget);
    });

    testWidgets('UNAVAILABLE：点击只提示 Tempat tidak ditemukan、不跳转', (tester) async {
      await _pump(tester, TimelinePage(items: [checkin(available: false)]));
      await tester.tap(find.byKey(const ValueKey('timelineCheckinBanner')));
      await tester.pump();
      expect(find.text('Tempat tidak ditemukan'), findsOneWidget);
      expect(find.textContaining('place:'), findsNothing);
      await tester.pumpAndSettle(const Duration(seconds: 5));
    });
  });
}
