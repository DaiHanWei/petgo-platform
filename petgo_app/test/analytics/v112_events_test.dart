import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/auth/domain/user_state.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/calendar_month.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/presentation/diary_guest_page.dart';
import 'package:tailtopia/features/profile/presentation/growth_archive_page.dart';
import 'package:tailtopia/features/profile/presentation/widgets/timeline_item_tile.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/bottom_tab_bar.dart';

import '../support/fake_feed_repository.dart';

/// Story 6.1 · L0：V1.1.2 埋点清单（T-1~T-12）的事件名、属性名与取值词表。
///
/// **为什么值得写成测试**：埋点错了不会崩、不会报警，只会安静地产出错数据 —— 等到看板上
/// 发现口径不对，往往已经攒了几周脏数据、无法回溯修复。所以这里锁死三件事：
/// 1. **事件名/属性名全是 snake_case**（AC8）；
/// 2. **取值来自受控词表**（`user_state` 取 [AppUserState.wire]、`item_type` 取后端 `itemType`）；
/// 3. **多入口事件不被拆开**（T-4 只有一个事件名 + `source` 属性 —— 拆了转化率分母就碎了）。
///
/// 观察手段是 [Analytics.debugCaptureSink]（挂在 scrub 之后），断言看到的就是端上真正发出的形态。
class Recorded {
  const Recorded(this.event, this.props);
  final String event;
  final Map<String, Object>? props;
  @override
  String toString() => '$event $props';
}

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

late List<Recorded> events;

List<Recorded> _of(String name) => events.where((e) => e.event == name).toList();

Recorded _one(String name) {
  final hits = _of(name);
  expect(hits, hasLength(1), reason: '$name 应恰好上报一次，实际：$events');
  return hits.single;
}

PetProfile _pet() => PetProfile(
      id: 7,
      name: 'Mochi',
      cardToken: 'TOKEN',
      petType: 'CAT',
      birthday: DateTime(2025, 1, 1),
    );

TimelineItem _post(int id) => TimelineItem(
      kind: TimelineKind.happyMoment,
      itemType: TimelineItemType.happyMoment,
      date: DateTime(2026, 5, 1),
      text: 'x',
      postId: id,
    );

/// 真实 Diary 页（已建档态）+ 一个能承接 `context.push` 的路由表。
Widget _archiveApp({List<TimelineItem> items = const []}) {
  final router = GoRouter(
    initialLocation: '/',
    routes: [
      GoRoute(path: '/', builder: (_, _) => const GrowthArchivePage()),
      for (final p in const ['/content/:id', '/profile/health', '/profile/milestones', '/profile/id-card'])
        GoRoute(path: p, builder: (_, _) => const Scaffold(body: Text('stub'))),
    ],
  );
  return ProviderScope(
    overrides: [
      authControllerProvider.overrideWith(() => _TestAuthController(
            AuthState(
              status: AuthStatus.authenticated,
              role: 'USER',
              profile: UserProfile(petStatus: 'HAS_PET', hasPetProfile: true),
            ),
          )),
      petProfileProvider.overrideWith((ref) async => _pet()),
      timelineFirstPageProvider.overrideWith((ref) async => TimelinePage(items: items)),
      archiveStatsProvider.overrideWith((ref) async => const ArchiveStats(
          happyMomentCount: 1, consultCount: 0, milestoneCompleted: 0, milestoneTotal: 30)),
      shareFabAnimatedShownProvider.overrideWith((ref) async => true),
      // 日历也要桩化：不桩就走真 dio，切到日历视图后留下未完成的网络 timer，测试收不了尾。
      calendarMonthProvider.overrideWith((ref, ym) async =>
          CalendarMonth(year: ym.year, month: ym.month, days: const [])),
    ],
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  );
}

Widget _guestApp() => ProviderScope(
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: const DiaryGuestPage(),
      ),
    );

Widget _composeApp() => ProviderScope(
      overrides: [
        authControllerProvider.overrideWith(() => _TestAuthController(
              AuthState(
                status: AuthStatus.authenticated,
                role: 'USER',
                profile: UserProfile(petStatus: 'HAS_PET', hasPetProfile: true),
              ),
            )),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: const Scaffold(body: PublishComposePage()),
      ),
    );

Finder _tabButton(String label) =>
    find.descendant(of: find.byType(BottomTabBar), matching: find.text(label));

/// 扫 `lib/` 下所有 dart 源，把 `Analytics.capture('…')` 的**事件名字面量**提出来。
///
/// 为什么要从源码提取而不是维护一份清单：清单会与代码脱节，而脱节时测试是绿的
/// （code-review 2026-08-04）。只认字面量 —— 目前全部上报点都是字面量，
/// 哪天有人改成变量，下面的「清单里的事件必须在源码里找得到」会红，正好逼他解释。
Set<String> _literalsOf(RegExp pattern) {
  final result = <String>{};
  final dir = Directory('lib');
  for (final entity in dir.listSync(recursive: true)) {
    if (entity is! File || !entity.path.endsWith('.dart')) continue;
    for (final m in pattern.allMatches(entity.readAsStringSync())) {
      result.add(m.group(1)!);
    }
  }
  return result;
}

@visibleForTesting
Set<String> eventNamesInSource() =>
    _literalsOf(RegExp(r"""Analytics\.capture\(\s*'([A-Za-z0-9_]+)'"""));

@visibleForTesting
Set<String> screenNamesInSource() =>
    _literalsOf(RegExp(r"""Analytics\.screen\(\s*'([A-Za-z0-9_]+)'"""));

void main() {
  setUp(() {
    events = <Recorded>[];
    Analytics.debugCaptureSink = (e, p) => events.add(Recorded(e, p));
    resetDiaryGuestViewSession();
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  group('AC8 命名规范（全部事件与属性 snake_case）', () {
    // ⚠️ 断言的对象是**从 `lib/` 真提取出来的字面量**，不是这里手抄的一份清单
    // （code-review 2026-08-04）。原先遍历的是测试文件内的常量数组，于是
    // 「起名不合规会红」根本不成立：新增或改名任何事件，只要没人同步手改那份数组，测试恒绿 ——
    // 而那时它检查的也只是自己刚写下的字符串。

    /// V1.0.x 遗留事件：埋点文档 §8.9 明确**不改名**（改了会断掉已有看板与历史数据）。
    /// 新事件绝不允许加进这份名单 —— 往这里加东西本身就是评审信号。
    const legacyEvents = <String>{
      'login_tapped',
      'signup_completed',
      'onboarding_completed',
      'onboarding_nickname_submitted',
      'pet_profile_create_submitted',
      'content_publish_submitted',
      'post_like_tapped',
      'triage_submitted',
      'consult_started',
      'milestone_share_created',
      // AppsFlyer 约定名（af_ 前缀由第三方规定，不受本项目命名规范约束）
      'af_complete_registration',
      'af_initiated_checkout',
      'af_purchase',
    };

    test('lib/ 里所有事件名都是 snake_case', () {
      final naming = RegExp(r'^[a-z][a-z0-9_]*$');
      final inSource = eventNamesInSource();
      expect(inSource, isNotEmpty, reason: '一个事件都没提取到 —— 提取逻辑坏了，不是代码真没埋点');
      for (final e in inSource) {
        expect(naming.hasMatch(e), isTrue, reason: '$e 不是 snake_case');
      }
    });

    test('lib/ 里每个非遗留事件都符合「模块前缀 + 对象 + 动作」', () {
      // **命名可读性**（用户 2026-08-04 要求）：产品要能从事件名一眼看出「哪个页面的哪个
      // 按钮/功能」。⚠️ 反例（本轮修掉的）：`tab_switched` 看不出是底部导航还是页内 Tab；
      // `diary_sync_toggled` 听着像 Diary 页上的开关，其实在发布页。
      const allowedPrefixes = <String>[
        'app_', 'bottom_nav_', 'diary_', 'social_', 'health_', 'publish_', 'me_',
        'signup_', 'milestone_',
        // 问诊双线漏斗（2026-08-06：PostHog 区分 AI/VET）——事件必带 consult_type 属性。
        'consult_', 'ai_',
        // 推送权限（V1.1.6 FR-85 / Story 8.1）。模块是「推送权限」而非某个页面 ——
        // 它跨冷启动与四个触发点，本来就不属于任何单页；`push_permission_*` 一眼可读，
        // 符合本规则的**用意**（产品看得出是哪个功能）。
        // ⚠️ 这是**新模块**入表，不是为遗留事件放宽规则（那种情况请加 legacyEvents）。
        'push_',
      ];
      // 动作必须落在词尾（过去式/被动），这样一眼分得清「曝光」与「点击」。
      const allowedSuffixes = <String>[
        '_viewed', '_shown', '_tapped', '_selected', '_toggled', '_switched',
        '_succeeded', '_completed', '_landed_on_tab', '_achieved',
        // 问诊漏斗节点（2026-08-06）：下单提交 / 流程开始。
        '_submitted', '_started',
        // 用户对提示的响应（V1.1.6 Story 8.2）：`_responded` 与 `_tapped` 的区别在于
        // **它是对一个「被问」的回答**，取值有多档（granted / denied / settings_opened /
        // dismissed），而不是单一动作。分母是提示曝光数（`_shown`），配对使用。
        // 🔴 8-1 刻意**没有**提前把它加进来 —— 白名单里放尚未用到的条目，
        //    就失去了「改动时被迫想一次」的作用。本 story 用到了才加。
        '_responded',
        // 状态上报（V1.1.6 Story 8.1）：`_reported` = 端上主动上报一次当前状态，
        // 与「用户做了什么」的 _tapped/_selected 区分开 —— 这类事件没有用户动作，
        // 分母是启动数而不是曝光数，混在一起会让判读口径错位。
        // 🔴 产品 2026-08-18 定名时正是把旧名 `..._state_snapshot` 改成了它
        //    （snapshot 是名词，不满足「动作在词尾且须是动词」），OA-7 已闭合。
        '_reported',
      ];
      for (final e in eventNamesInSource()) {
        if (legacyEvents.contains(e)) continue;
        expect(allowedPrefixes.any(e.startsWith), isTrue,
            reason: '$e 缺少可读的模块前缀 —— 产品看不出这是哪个页面的事件；'
                '若它是 V1.0.x 遗留事件，请显式加入 legacyEvents 而不是放宽规则');
        expect(allowedSuffixes.any(e.endsWith), isTrue,
            reason: '$e 的动作词不明确（动作要落在词尾，如 _tapped / _viewed）');
      }
    });

    test('本版本清单 T-1~T-12 的事件在源码里确实存在（声明与实现不许脱节）', () {
      // 这一条守的是反方向：文档/story 说埋了，代码里却没有。
      const v112Events = <String>[
        'app_launch_landed_on_tab',
        'bottom_nav_tab_switched',
        'diary_guest_page_viewed',
        'diary_guest_create_profile_cta_tapped',
        'social_soft_login_sheet_shown',
        'social_soft_login_sheet_login_tapped',
        'signup_succeeded',
        'publish_page_content_type_selected',
        'publish_page_sync_to_moment_toggled',
        'diary_timeline_item_tapped',
        'diary_view_mode_switched',
      ];
      // T-5 已删且编号不重分配 —— 这里断言我们没有偷偷复用它。
      expect(v112Events.length, 11,
          reason: 'T-1~T-12 去掉已删的 T-5，`_shown`/`_tapped` 合计为 11 项（T-12 在后端）');
      final inSource = eventNamesInSource();
      for (final e in v112Events) {
        expect(inSource, contains(e), reason: '$e 在清单里但 lib/ 里找不到上报点');
      }
    });

    test('属性名均为 snake_case', () {
      final naming = RegExp(r'^[a-z][a-z0-9_]*$');
      const propNames = <String>[
        'tab', 'user_state', 'from_tab', 'to_tab', 'session_first', 'source', 'method',
        'entry_source', 'type', 'is_default', 'has_pet_profile', 'enabled', 'item_type',
        'to_view',
        // V1.1.2 Story 7.4 · FR-91（code-review 2026-08-04 补入契约清单）：
        // 兜底那次带 restore_timeout、纠正那次带 corrected_from。实际上报形态由
        // test/shared/splash_landing_budget_test.dart 行为级断言把守。
        'restore_timeout', 'corrected_from',
        // 问诊双线漏斗（2026-08-06）：consult_type ∈ {AI, VET} 是区分两线的唯一维度。
        'consult_type', 'price_idr',
      ];
      for (final n in propNames) {
        expect(naming.hasMatch(n), isTrue, reason: '$n 不是 snake_case');
      }
    });

    test('手工上报的屏名统一以 _page 结尾（与 Tab 根页同一套命名）', () {
      for (final s in screenNamesInSource()) {
        expect(s.endsWith('_page'), isTrue,
            reason: '$s 不是 <产品叫法>_page —— 冷启动落地与切 Tab 必须用同一套屏名，'
                '否则同一个页面会在看板上被算成两个');
      }
    });
  });

  group('T-1/T-2 落地与 Tab 切换（AC2：此前完全无埋点的 P0 缺口）', () {
    testWidgets(r'冷启动落地上报 app_launch_landed_on_tab + 落地页 $screen（游客 → Diary）', (tester) async {
      final container = ProviderContainer(
        overrides: [feedRepositoryProvider.overrideWithValue(FakeFeedRepository())],
      );
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(container: container, child: const TailTopiaApp()),
      );
      await tester.pumpAndSettle();

      final landing = _one('app_launch_landed_on_tab');
      expect(landing.props!['tab'], 'diary');
      expect(landing.props!['user_state'], AppUserState.guest.wire,
          reason: 'user_state 必须取落地矩阵的同一枚举，不得另写一份判定');
      expect(_of(r'$screen').map((e) => e.props![r'$screen_name']), contains('diary_page'));
    });

    testWidgets(r'点 Tab 上报 bottom_nav_tab_switched（from/to/user_state）并补一条 $screen', (tester) async {
      final container = ProviderContainer(
        overrides: [feedRepositoryProvider.overrideWithValue(FakeFeedRepository())],
      );
      addTearDown(container.dispose);
      await tester.pumpWidget(
        UncontrolledProviderScope(container: container, child: const TailTopiaApp()),
      );
      await tester.pumpAndSettle();
      events.clear();

      await tester.tap(_tabButton('Social'));
      await tester.pumpAndSettle();

      final ev = _one('bottom_nav_tab_switched');
      expect(ev.props!['from_tab'], 'diary', reason: '游客落地在 Diary');
      expect(ev.props!['to_tab'], 'social');
      expect(ev.props!['user_state'], AppUserState.guest.wire);
      // goBranch 不 push 根路由 → PosthogObserver 收不到；缺的这条浏览事件由我们自己补。
      expect(_of(r'$screen').map((e) => e.props![r'$screen_name']), contains('social_page'));
    });
  });

  group('T-3/T-4 游客态（AC3）', () {
    testWidgets('曝光上报 diary_guest_page_viewed，session 内第二次 session_first=false', (tester) async {
      await tester.binding.setSurfaceSize(const Size(500, 2400));
      addTearDown(() => tester.binding.setSurfaceSize(null));

      await tester.pumpWidget(_guestApp());
      await tester.pumpAndSettle();
      expect(_one('diary_guest_page_viewed').props!['session_first'], isTrue);

      events.clear();
      await tester.pumpWidget(const SizedBox());
      await tester.pumpWidget(_guestApp());
      await tester.pumpAndSettle();
      expect(_one('diary_guest_page_viewed').props!['session_first'], isFalse,
          reason: '重复曝光要能与首次区分，否则算不出「看过一次就走」的比例');
    });

    testWidgets('主 CTA → 单一事件 diary_guest_create_profile_cta_tapped，source=main_cta', (tester) async {
      await tester.binding.setSurfaceSize(const Size(500, 2400));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(_guestApp());
      await tester.pumpAndSettle();
      events.clear();

      await tester.tap(find.byKey(const ValueKey('diaryGuestPrimaryCta')));
      await tester.pump();

      expect(_one('diary_guest_create_profile_cta_tapped').props!['source'], 'bottom_sticky_cta');
      // 反向断言：不得为不同入口另起事件名（分母会碎）。
      expect(events.where((e) => e.event.startsWith('diary_guest_create_profile')), hasLength(1));
    });

    testWidgets('页头入口与时间线条目 → 同一事件，只靠 source 区分', (tester) async {
      await tester.binding.setSurfaceSize(const Size(500, 2400));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(_guestApp());
      await tester.pumpAndSettle();
      events.clear();

      // 页头入口（Story 2.2 列举三类入口时漏掉的第四个引导点）。
      await tester.tap(find.byKey(const ValueKey('diaryIdCardButton')));
      await tester.pump();
      expect(_one('diary_guest_create_profile_cta_tapped').props!['source'], 'header_entry');

      // 时间线上的非图条目（示例本里带图的 3 条走详情页、不弹引导）。
      events.clear();
      await tester.tapAt(const Offset(4, 4)); // 关掉上一步弹出的强登录引导
      await tester.pumpAndSettle();
      final tiles = find.byType(TimelineItemTile);
      var tapped = false;
      for (var i = 0; i < tester.widgetList(tiles).length && !tapped; i++) {
        final item = tester.widget<TimelineItemTile>(tiles.at(i)).item;
        if (item.imageUrls.isEmpty) {
          await tester.tap(tiles.at(i));
          await tester.pump();
          tapped = true;
        }
      }
      expect(tapped, isTrue, reason: '示例本里必须存在非图条目，否则 T-4 的 timeline_item 分支无从触发');
      expect(_one('diary_guest_create_profile_cta_tapped').props!['source'], 'timeline_item');
    });
  });

  group('T-8/T-9 发布页（AC3）', () {
    testWidgets('切换类型上报 publish_page_content_type_selected（type/is_default/has_pet_profile）', (tester) async {
      tester.view.physicalSize = const Size(1200, 3200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(_composeApp());
      await tester.pumpAndSettle();
      events.clear();

      await tester.tap(find.byKey(const ValueKey('seg_DAILY')));
      await tester.pumpAndSettle();

      final ev = _one('publish_page_content_type_selected');
      expect(ev.props!['type'], ContentType.daily.wire);
      expect(ev.props!['is_default'], isFalse, reason: '已建档用户默认是 Diary，选 Moment 不是默认值');
      expect(ev.props!['has_pet_profile'], isTrue);
    });

    /// 图片来源选择（2026-08-20 用户要求）：发布页点「Add」后弹的 sheet 里，
    /// 用户选了相机还是相册。
    ///
    /// **为什么值得埋**：这两条路的成本完全不同 —— 相册是"我已经有照片了"，
    /// 相机是"我现在为发帖专门拍一张"。后者说明发布意愿更强、但也更容易在拍摄这一步流失。
    /// 一个事件 + `source` 属性（不是两个事件），与同页 `publish_page_content_type_selected`
    /// 的形状一致，看板里可直接对比占比。
    testWidgets('选相机 / 选相册上报 publish_page_image_source_selected（source）', (tester) async {
      tester.view.physicalSize = const Size(1200, 3200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(_composeApp());
      await tester.pumpAndSettle();

      // 相册
      events.clear();
      await tester.tap(find.byKey(const ValueKey('publishAddImage')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('publishPickGallery')));
      await tester.pumpAndSettle();
      expect(_one('publish_page_image_source_selected').props!['source'], 'gallery');

      // 相机
      events.clear();
      await tester.tap(find.byKey(const ValueKey('publishAddImage')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('publishPickCamera')));
      await tester.pumpAndSettle();
      expect(_one('publish_page_image_source_selected').props!['source'], 'camera');
    });

    /// 🛡 关掉 sheet 而不选 —— **不该**产生这个事件。
    /// 否则「选了哪个来源」的分母会混进"打开又关掉"的人，占比失真。
    testWidgets('关掉 sheet 不选来源 → 不报事件', (tester) async {
      tester.view.physicalSize = const Size(1200, 3200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(_composeApp());
      await tester.pumpAndSettle();
      events.clear();

      await tester.tap(find.byKey(const ValueKey('publishAddImage')));
      await tester.pumpAndSettle();
      // 点 sheet 外的遮罩关掉
      await tester.tapAt(const Offset(10, 10));
      await tester.pumpAndSettle();

      expect(events.where((e) => e.event == 'publish_page_image_source_selected'), isEmpty);
    });

    testWidgets('关同步开关上报 publish_page_sync_to_moment_toggled(enabled=false)——本版本最关键的假设验证', (tester) async {
      tester.view.physicalSize = const Size(1200, 3200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(_composeApp());
      await tester.pumpAndSettle();
      events.clear();

      await tester.tap(find.byKey(const ValueKey('publishSyncToggle')));
      await tester.pumpAndSettle();
      expect(_one('publish_page_sync_to_moment_toggled').props!['enabled'], isFalse);

      events.clear();
      await tester.tap(find.byKey(const ValueKey('publishSyncToggle')));
      await tester.pumpAndSettle();
      expect(_one('publish_page_sync_to_moment_toggled').props!['enabled'], isTrue);
    });
  });

  group('T-10/T-11 成长档案（AC3/AC4）', () {
    testWidgets('AC4：diary_timeline_item_tapped 的 item_type 直取后端 itemType 词表', (tester) async {
      await tester.binding.setSurfaceSize(const Size(500, 2400));
      addTearDown(() => tester.binding.setSurfaceSize(null));

      await tester.pumpWidget(_archiveApp(items: [_post(42)]));
      await tester.pumpAndSettle();
      events.clear();

      await tester.tap(find.byType(TimelineItemTile).first);
      await tester.pumpAndSettle();

      final ev = _one('diary_timeline_item_tapped');
      expect(ev.props!['item_type'], TimelineItemType.happyMoment.wire,
          reason: 'item_type 必须与后端 itemType 逐字一致，不得在前端另行推断（AD-2）');
    });

    testWidgets('切日历视图上报 diary_view_mode_switched(to_view)，重复点同一视图不重复上报', (tester) async {
      await tester.binding.setSurfaceSize(const Size(500, 2400));
      addTearDown(() => tester.binding.setSurfaceSize(null));

      await tester.pumpWidget(_archiveApp());
      await tester.pumpAndSettle();
      events.clear();

      await tester.tap(find.byKey(const ValueKey('archiveViewCalendar')));
      await tester.pumpAndSettle();
      expect(_one('diary_view_mode_switched').props!['to_view'], 'calendar');

      events.clear();
      await tester.tap(find.byKey(const ValueKey('archiveViewCalendar')));
      await tester.pumpAndSettle();
      expect(_of('diary_view_mode_switched'), isEmpty, reason: '点当前视图无状态变化，不应制造噪声事件');
    });
  });

  group('T-6/T-7 软登录浮层与注册归因（AC3）', () {
    LoginResponse newUser() => const LoginResponse(
          accessToken: 'a',
          refreshToken: 'r',
          role: 'USER',
          isNewUser: true,
          onboardingCompleted: false,
        );

    Widget guideApp(LoginGuideController controller) {
      final router = GoRouter(
        initialLocation: '/',
        routes: [
          GoRoute(
            path: '/',
            builder: (ctx, _) => Scaffold(
              body: Builder(
                builder: (inner) => TextButton(
                  onPressed: () => controller.showSoftSheet(inner),
                  child: const Text('trigger'),
                ),
              ),
            ),
          ),
          GoRoute(path: '/onboarding', builder: (_, _) => const Scaffold(body: Text('onboarding'))),
        ],
      );
      return ProviderScope(
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
        ),
      );
    }

    testWidgets('浮层曝光 → shown；点主 CTA → tapped；注册成功 → signup_succeeded(soft_login)',
        (tester) async {
      final controller = LoginGuideController(() async => newUser());
      await tester.pumpWidget(guideApp(controller));
      await tester.pumpAndSettle();

      await tester.tap(find.text('trigger'));
      await tester.pumpAndSettle();
      expect(_of('social_soft_login_sheet_shown'), hasLength(1));

      events.clear();
      await tester.tap(find.byKey(const ValueKey('softSheetGoogleCta')));
      await tester.pumpAndSettle();

      expect(_one('social_soft_login_sheet_login_tapped').props!['method'], 'google');
      expect(_one('signup_succeeded').props!['entry_source'], 'social_soft_login',
          reason: 'T-7 的价值全在 entry_source —— 转化路径构成是本版本仅剩的两个可用指标之一');
    });

    testWidgets('session 内第二次触发浮层是 no-op → 不得重复上报曝光', (tester) async {
      final controller = LoginGuideController(() async => newUser());
      await tester.pumpWidget(guideApp(controller));
      await tester.pumpAndSettle();

      await tester.tap(find.text('trigger'));
      await tester.pumpAndSettle();
      await tester.tapAt(const Offset(4, 4)); // 关掉浮层
      await tester.pumpAndSettle();
      await tester.tap(find.text('trigger'));
      await tester.pumpAndSettle();

      expect(_of('social_soft_login_sheet_shown'), hasLength(1),
          reason: '曝光埋点必须在 session 去重之后 —— 否则曝光数会被没弹出的那次虚高');
    });

    testWidgets('登录被取消 → 不报 signup_succeeded（只有真正成功才算注册）', (tester) async {
      final controller = LoginGuideController(() async => null); // 用户取消 Google 弹窗
      await tester.pumpWidget(guideApp(controller));
      await tester.pumpAndSettle();

      await tester.tap(find.text('trigger'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('softSheetGoogleCta')));
      await tester.pumpAndSettle();

      expect(_of('signup_succeeded'), isEmpty);
      expect(_of('social_soft_login_sheet_login_tapped'), hasLength(1), reason: '点击照记，成功与否是另一回事');
    });
  });

  group('T-12 前后端同一个人（跨语言契约）', () {
    test('distinctIdFor 与后端 AnalyticsDistinctId 同一个已知向量', () {
      // 后端 MilestoneAnalyticsTest 钉了同一个值。两端差一个字节，
      // 「点了按钮」（前端）与「达成里程碑」（后端）就拼不到同一个人身上，漏斗白做。
      expect(Analytics.distinctIdFor(42),
          'f9514799a33d2a201721f3ffc7fa376a077e517c546e2692b45f9a778e3fb4b2');
    });
  });

  group('AC6 FR-0H 埋点已下线', () {
    test('全前端不再有任何 FR-0H 提示条相关事件名', () {
      // 提示条本体已在 Story 2.3 整条废止；这里锁住「事件也没留下」。
      const banned = <String>[
        'profile_prompt_shown', 'profile_prompt_tapped', 'profile_prompt_dismissed',
        'profile_banner_shown', 'profile_banner_tapped', 'profile_banner_closed',
      ];
      for (final e in banned) {
        expect(Analytics.isAppsFlyerEvent(e), isFalse);
      }
    });
  });
}
