import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/push/push_service.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/notify/data/push_settings_repository.dart';
import 'package:tailtopia/features/notify/domain/push_settings.dart';
import 'package:tailtopia/features/notify/presentation/push_settings_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 ④ Story 1.3 · AC5–AC9：通知设置页（UI 稿 B1–B6）。结构断言，不写 golden。
class _Auth extends AuthController {
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

class _FakePush extends PushService {
  _FakePush(super.ref);

  int syncCalls = 0;

  @override
  Future<void> syncRegistration({required bool isVet}) async => syncCalls++;
}

class _FakeRepo extends PushSettingsRepository {
  _FakeRepo() : super(dio: Dio());

  Map<String, bool> server = {};
  Future<PushSettings> Function()? fetchOverride;
  Future<PushSettings> Function(String code, bool enabled)? updateOverride;
  final List<(String, bool)> updates = [];
  int fetchCalls = 0;

  PushSettings _current() => PushSettings.fromJson({
        'categories': [
          for (final c in PushCategoryCode.all)
            {'code': c, 'enabled': server[c] ?? true, 'lockable': c != PushCategoryCode.orderAccount},
        ],
      });

  @override
  Future<PushSettings> fetch() async {
    fetchCalls++;
    return fetchOverride != null ? fetchOverride!() : _current();
  }

  @override
  Future<PushSettings> update(String code, bool enabled) async {
    updates.add((code, enabled));
    if (updateOverride != null) return updateOverride!(code, enabled);
    server[code] = enabled;
    return _current();
  }
}

void main() {
  late _FakeRepo repo;
  late List<MapEntry<String, Map<String, Object>?>> events;
  late bool granted;
  late int openCalls;
  late bool requestResult;
  late int requestCalls;

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    repo = _FakeRepo();
    events = [];
    granted = true;
    openCalls = 0;
    requestResult = false;
    requestCalls = 0;
    Analytics.debugCaptureSink = (e, p) => events.add(MapEntry(e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<void> pump(WidgetTester tester) async {
    await tester.binding.setSurfaceSize(const Size(440, 1800));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(ProviderScope(
      overrides: [
        pushSettingsRepositoryProvider.overrideWithValue(repo),
        authControllerProvider.overrideWith(_Auth.new),
        pushServiceProvider.overrideWith(_FakePush.new),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        home: PushSettingsPage(
          isNotificationGrantedForTest: () async => granted,
          openSettingsForTest: () async {
            openCalls++;
            return true;
          },
          requestPermissionForTest: () async {
            requestCalls++;
            if (requestResult) granted = true;
            return requestResult;
          },
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  Switch switchOf(WidgetTester tester, String code) =>
      tester.widget<Switch>(find.byKey(ValueKey('pushSettingsSwitch_$code')));

  List<MapEntry<String, Map<String, Object>?>> named(String e) => events.where((x) => x.key == e).toList();

  // ===== V1.4.0 push-collect Story 3.1 · AC6：设置页图标与通知中心 _iconStyle 对齐（防漂移）=====
  /// 设置行的首个图标（36 圆底里的那个）。
  Icon leadIcon(WidgetTester tester, String code) {
    final row = find.ancestor(of: find.byKey(ValueKey('pushSettingsSwitch_$code')), matching: find.byType(Row)).first;
    return tester.widget<Icon>(find.descendant(of: row, matching: find.byType(Icon)).first);
  }

  testWidgets('3.1 AC6：Hadiah = 收礼金色 redeem；Tonggak & level = 里程碑奖杯绿（不是升级紫）', (tester) async {
    await pump(tester);
    final gift = leadIcon(tester, PushCategoryCode.gift);
    expect(gift.icon, Icons.redeem_rounded);
    expect(gift.color, AppColors.gold);
    final achievement = leadIcon(tester, PushCategoryCode.achievement);
    expect(achievement.icon, Icons.emoji_events_rounded);
    expect(achievement.color, AppColors.triageGreen);
    expect(achievement.icon, isNot(Icons.workspace_premium_rounded));
  });

  test('3.1 AC6.3：设置页代表类型的三元组与通知中心 _iconStyle 源码一致', () {
    String norm(String x) => x.replaceAll(RegExp(r'\s+'), '').replaceAll(RegExp(r',\)$'), ')');
    final center = File('lib/features/notify/presentation/notification_center_page.dart').readAsStringSync();
    final settings = File('lib/features/notify/presentation/push_settings_page.dart').readAsStringSync();
    String centerStyle(String type) {
      final m = RegExp("'$type' => \\(([^)]*)\\)").firstMatch(center);
      expect(m, isNotNull, reason: '_iconStyle 缺 $type');
      return norm('(${m!.group(1)})');
    }
    String settingsStyle(String field) {
      final m = RegExp('_Row\\(PushCategoryCode\\.$field, ([^)]*)\\)').firstMatch(settings);
      expect(m, isNotNull, reason: '设置页缺 $field 行');
      return norm('(${m!.group(1)})');
    }
    // 代表类型：Suka → CONTENT_LIKED、Komentar → CONTENT_COMMENTED、Hadiah → PET_GIFT_RECEIVED、
    // Pengingat → PET_BIRTHDAY、Tonggak & level → MILESTONE_NODE（按 ④ UI B1 保持奖杯绿，见 3.1 待确认 1）。
    const pairs = {
      'like': 'CONTENT_LIKED',
      'comment': 'CONTENT_COMMENTED',
      'gift': 'PET_GIFT_RECEIVED',
      'petReminder': 'PET_BIRTHDAY',
      'achievement': 'MILESTONE_NODE',
    };
    pairs.forEach((field, type) => expect(settingsStyle(field), centerStyle(type), reason: '$field ↔ $type'));
  });

  testWidgets('B1：4 组 9 行，8 个开关开、订单与账号为锁定胶囊；状态卡开启', (tester) async {
    await pump(tester);
    final titles = ['INTERAKSI', 'HEWAN & PENCAPAIAN', 'INFO & PROMO', 'SELALU AKTIF'];
    final dy = [for (final t in titles) tester.getTopLeft(find.text(t)).dy];
    expect(dy, orderedEquals([...dy]..sort()));
    for (final c in PushCategoryCode.all.where((c) => c != PushCategoryCode.orderAccount)) {
      expect(switchOf(tester, c).value, isTrue, reason: c);
    }
    expect(find.byKey(const ValueKey('pushSettingsSwitch_ORDER_ACCOUNT')), findsNothing);
    final locked = find.byKey(const ValueKey('pushSettingsLocked_ORDER_ACCOUNT'));
    expect(locked, findsOneWidget);
    expect(find.descendant(of: locked, matching: find.text('Selalu aktif')), findsOneWidget);
    expect(find.text('Notifikasi yang dimatikan tetap masuk ke kotak Notifikasi di aplikasi.'), findsOneWidget);
    expect(find.byKey(const ValueKey('pushSettingsStatusOn')), findsOneWidget);
  });

  testWidgets('B2：服务端 ACHIEVEMENT / PROMO 关 → 两个开关为关', (tester) async {
    repo.server = {'ACHIEVEMENT': false, 'PROMO': false};
    await pump(tester);
    expect(switchOf(tester, 'ACHIEVEMENT').value, isFalse);
    expect(switchOf(tester, 'PROMO').value, isFalse);
    expect(switchOf(tester, 'LIKE').value, isTrue);
  });

  testWidgets('B3：系统权限关 → 浅红卡 + 去设置；开关照常可切换', (tester) async {
    granted = false;
    await pump(tester);
    expect(find.byKey(const ValueKey('pushSettingsStatusOff')), findsOneWidget);
    expect(find.text('Notifikasi sistem nonaktif'), findsOneWidget);
    expect(find.text('Setelah diaktifkan, notifikasi dikirim sesuai pengaturan di bawah.'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('pushSettingsOpenSystem')));
    await tester.pumpAndSettle();
    expect(requestCalls, 1, reason: '先申请一次（已拒绝时立即返回 false）');
    expect(openCalls, 1, reason: '没拿到 → 去系统设置');
    await tester.tap(find.byKey(const ValueKey('pushSettingsSwitch_LIKE')));
    await tester.pumpAndSettle();
    expect(repo.updates, [('LIKE', false)]);
  });

  testWidgets('resume 刷新：权限 false → true 后状态卡变开启；viewed 不重报', (tester) async {
    granted = false;
    await pump(tester);
    expect(find.byKey(const ValueKey('pushSettingsStatusOff')), findsOneWidget);
    granted = true;
    for (final st in [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed,
    ]) {
      tester.binding.handleAppLifecycleStateChanged(st);
    }
    // 权限 false → true 是一次真跃迁：reporter 会走 Analytics.capture 的真实 SDK 通路（平台调用），
    // 在 fake async 里不会完成 —— 给它一段真实时间。
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 200)));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('pushSettingsStatusOn')), findsOneWidget);
    expect(named('push_settings_viewed'), hasLength(1));
    expect(named('push_settings_viewed').single.value, {'system_permission': 'off'});
  });

  testWidgets('B4：保存失败 → 弹回 + Toast，不报埋点', (tester) async {
    final gate = Completer<PushSettings>();
    repo.updateOverride = (_, _) => gate.future;
    await pump(tester);
    await tester.tap(find.byKey(const ValueKey('pushSettingsSwitch_LIKE')));
    await tester.pump();
    expect(switchOf(tester, 'LIKE').value, isFalse, reason: '乐观更新');
    gate.completeError(Exception('net'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(switchOf(tester, 'LIKE').value, isTrue, reason: '失败回弹');
    expect(find.text('Gagal menyimpan, coba lagi'), findsOneWidget);
    expect(named('push_category_toggled'), isEmpty);
    await tester.pump(const Duration(seconds: 3)); // 让 Toast 计时器走完
  });

  testWidgets('成功：保持关闭 + push_category_toggled 恰一次（小写 code）', (tester) async {
    await pump(tester);
    await tester.tap(find.byKey(const ValueKey('pushSettingsSwitch_PROMO')));
    await tester.pumpAndSettle();
    expect(repo.updates, [('PROMO', false)]);
    expect(switchOf(tester, 'PROMO').value, isFalse);
    expect(named('push_category_toggled'), hasLength(1));
    expect(named('push_category_toggled').single.value, {'category': 'promo', 'enabled': false});
  });

  testWidgets('在途防连点：同一开关连点两次只发一次请求', (tester) async {
    final gate = Completer<PushSettings>();
    repo.updateOverride = (_, _) => gate.future;
    await pump(tester);
    await tester.tap(find.byKey(const ValueKey('pushSettingsSwitch_LIKE')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('pushSettingsSwitch_LIKE')));
    await tester.pump();
    expect(repo.updates, hasLength(1));
    repo.server['LIKE'] = false;
    gate.complete(PushSettings.fromJson({
      'categories': [
        {'code': 'LIKE', 'enabled': false, 'lockable': true},
      ],
    }));
    await tester.pumpAndSettle();
    expect(switchOf(tester, 'LIKE').value, isFalse);
  });

  testWidgets('B5：加载中只见骨架', (tester) async {
    final never = Completer<PushSettings>();
    repo.fetchOverride = () => never.future;
    await tester.pumpWidget(ProviderScope(
      overrides: [
        pushSettingsRepositoryProvider.overrideWithValue(repo),
        authControllerProvider.overrideWith(_Auth.new),
        pushServiceProvider.overrideWith(_FakePush.new),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        home: PushSettingsPage(isNotificationGrantedForTest: () async => true),
      ),
    ));
    await tester.pump();
    expect(find.byKey(const ValueKey('pushSettingsSkeleton')), findsOneWidget);
    expect(find.byType(Switch), findsNothing);
  });

  testWidgets('B6：加载失败 → 云图标 + 重试；无状态卡无开关；重试成功出 9 行', (tester) async {
    var fail = true;
    repo.fetchOverride = () async {
      if (fail) throw Exception('net');
      return PushSettings.fromJson(null);
    };
    await pump(tester);
    expect(find.text('Gagal memuat pengaturan'), findsOneWidget);
    expect(find.byKey(const ValueKey('emptyStateAction')), findsOneWidget);
    expect(find.byKey(const ValueKey('pushSettingsStatusOn')), findsNothing);
    expect(find.byType(Switch), findsNothing);
    fail = false;
    await tester.tap(find.byKey(const ValueKey('emptyStateAction')));
    await tester.pumpAndSettle();
    expect(find.byType(Switch), findsNWidgets(8));
    expect(find.byKey(const ValueKey('pushSettingsLocked_ORDER_ACCOUNT')), findsOneWidget);
  });

  testWidgets('埋点：进页报 push_settings_viewed{on} 一次', (tester) async {
    await pump(tester);
    expect(named('push_settings_viewed'), hasLength(1));
    expect(named('push_settings_viewed').single.value, {'system_permission': 'on'});
  });

  testWidgets('iOS 从未申请过：点「Buka Pengaturan」直接弹系统窗授权成功 → 卡片变开启、不去系统设置', (tester) async {
    granted = false;
    requestResult = true;
    await pump(tester);
    await tester.tap(find.byKey(const ValueKey('pushSettingsOpenSystem')));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 200)));
    await tester.pumpAndSettle();
    expect(openCalls, 0);
    expect(find.byKey(const ValueKey('pushSettingsStatusOn')), findsOneWidget);
  });
}
