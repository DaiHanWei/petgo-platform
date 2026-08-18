import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/mini_profile_repository.dart';
import 'package:tailtopia/features/social/data/account_report_repository.dart';
import 'package:tailtopia/features/social/data/blocked_users_repository.dart';
import 'package:tailtopia/features/social/domain/account_action_entry.dart';
import 'package:tailtopia/features/social/domain/account_report_reason.dart';
import 'package:tailtopia/features/social/domain/blocked_user.dart';
import 'package:tailtopia/features/social/presentation/blocked_users_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/mini_profile_sheet.dart';

/// Story 4.1 · L0：V1.1.4（社区管控）埋点清单。
///
/// **为什么值得写成测试**：埋点错了不会崩、不会报警，只会安静地产出错数据 —— 等看板上发现口径不对，
/// 往往已经攒了几周脏数据、无法回溯修复。这里锁死四件事：
/// 1. **四个事件名与属性词表**（事件名前后缀另由 `v112_events_test` 从源码提取后统一校验）；
/// 2. **两个维度不混在一个 key 里**：`origin`（产生来源 BLOCK/REPORT）与 `entry`（操作入口）；
/// 3. **上报点在成功之后** —— 拉黑失败、举报未提交都不许留下事件（V1.1.2 的教训：门控前上报会让指标系统性高估）；
/// 4. **用户自由文本不进属性**（举报的「其他」补充说明）。
///
/// 观察手段是 [Analytics.debugCaptureSink]（挂在 scrub 之后），断言看到的就是端上真正发出的形态。

class Recorded {
  const Recorded(this.event, this.props);
  final String event;
  final Map<String, Object>? props;
  @override
  String toString() => '$event $props';
}

const int _kViewerId = 5;
const int _kTargetId = 7;

class _FakeMiniRepo implements MiniProfileRepository {
  @override
  Future<MiniProfile> getMiniProfile(int userId) async => const MiniProfile(
      postCount: 3, isDeactivated: false, nickname: 'Rina', avatarUrl: null);
}

class _FakeBlockRepo implements BlockedUsersRepository {
  _FakeBlockRepo({this.failBlock = false, this.failUnblock = false, this.items = const []});
  final bool failBlock;
  final bool failUnblock;
  final List<BlockedUser> items;

  @override
  Future<void> block(int userId) async {
    if (failBlock) throw Exception('boom');
  }

  @override
  Future<void> unblock(int userId) async {
    if (failUnblock) throw Exception('boom');
  }

  @override
  Future<List<BlockedUser>> list() async => items;
}

class _FakeReportRepo implements AccountReportRepository {
  _FakeReportRepo({this.fail = false});
  final bool fail;
  @override
  Future<void> report(int targetUserId, AccountReportReason reason, {String? detail}) async {
    if (fail) throw Exception('boom');
  }
}

class _LoggedInAuth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: _kViewerId, nickname: 'Me', onboardingCompleted: true),
      );
}

BlockedUser _blocked({bool reported = false}) => BlockedUser(
      userId: _kTargetId,
      nickname: 'Rina',
      avatarUrl: null,
      deleted: false,
      reported: reported,
      blockedAt: DateTime.utc(2026, 8, 14),
    );

void main() {
  late List<Recorded> events;

  setUp(() {
    events = <Recorded>[];
    Analytics.debugCaptureSink = (e, p) => events.add(Recorded(e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Recorded only(String name) {
    final hits = events.where((e) => e.event == name).toList();
    expect(hits, hasLength(1), reason: '期望恰好一条 $name，实际：$events');
    return hits.single;
  }

  /// 迷你卡入口（Feed / 详情页 = mini_profile；评论区 = comment）。
  Future<void> pumpMiniProfile(WidgetTester tester,
      {required AccountActionEntry entry,
      BlockedUsersRepository? blocks,
      AccountReportRepository? reports}) async {
    final container = ProviderContainer(overrides: [
      miniProfileRepositoryProvider.overrideWithValue(_FakeMiniRepo()),
      blockedUsersRepositoryProvider.overrideWithValue(blocks ?? _FakeBlockRepo()),
      accountReportRepositoryProvider.overrideWithValue(reports ?? _FakeReportRepo()),
      authControllerProvider.overrideWith(_LoggedInAuth.new),
    ]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Consumer(
          builder: (context, ref, _) => Scaffold(
            body: Center(
              child: ElevatedButton(
                key: const ValueKey('openMini'),
                onPressed: () => showMiniProfile(context, ref, _kTargetId, entry: entry),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('openMini')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('miniProfileMore')));
    await tester.pumpAndSettle();
  }

  Future<void> blockThrough(WidgetTester tester) async {
    await tester.tap(find.byKey(const ValueKey('miniProfileMenuBlock')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('confirmBlockUser')));
    await tester.pumpAndSettle();
  }

  group('AC1 / AC2：拉黑分来源、分入口', () {
    testWidgets('迷你卡拉黑成功 → origin=BLOCK, entry=mini_profile', (tester) async {
      await pumpMiniProfile(tester, entry: AccountActionEntry.miniProfile);
      await blockThrough(tester);

      final e = only('social_user_hide_submitted');
      expect(e.props!['origin'], 'BLOCK');
      expect(e.props!['entry'], 'mini_profile');
      await tester.pump(const Duration(seconds: 3)); // 放掉成功 toast 的定时器
    });

    testWidgets('评论区入口拉黑 → entry=comment（本版本主场景，量要能单独看）', (tester) async {
      await pumpMiniProfile(tester, entry: AccountActionEntry.comment);
      await blockThrough(tester);

      expect(only('social_user_hide_submitted').props!['entry'], 'comment');
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('⚠️ 拉黑失败 → 一条事件都不许留下（上报必须在成功之后）', (tester) async {
      await pumpMiniProfile(tester,
          entry: AccountActionEntry.miniProfile, blocks: _FakeBlockRepo(failBlock: true));
      await blockThrough(tester);

      expect(events.where((e) => e.event == 'social_user_hide_submitted'), isEmpty);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('中途取消（没确认）→ 不上报', (tester) async {
      await pumpMiniProfile(tester, entry: AccountActionEntry.miniProfile);
      await tester.tap(find.byKey(const ValueKey('miniProfileMenuBlock')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('confirmSheetCancel')));
      await tester.pumpAndSettle();

      expect(events, isEmpty);
    });
  });

  group('AC1 / AC3：举报', () {
    Future<void> reportThrough(WidgetTester tester) async {
      await tester.tap(find.byKey(const ValueKey('miniProfileMenuReport')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportReason_harassment')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportSubmit')));
      await tester.pumpAndSettle();
    }

    testWidgets('举报成功 → 两条事件：举报本身 + 它自动产生的隐藏关系（origin=REPORT）', (tester) async {
      await pumpMiniProfile(tester, entry: AccountActionEntry.miniProfile);
      await reportThrough(tester);

      final report = only('social_account_report_submitted');
      expect(report.props!['entry'], 'mini_profile');
      expect(report.props!['reason'], 'HARASSMENT'); // 受控词表，不是自由文本

      // ⚠️ 举报会在服务端同时建立一条 REPORT 隐藏关系；不分来源上报的话，
      // 隐藏关系总量会被举报量灌大，看不出主动拉黑的真实使用情况。
      final hide = only('social_user_hide_submitted');
      expect(hide.props!['origin'], 'REPORT');
      expect(hide.props!['entry'], 'report_flow');
    });

    testWidgets('⚠️ 举报失败 → 两条事件都不许留下', (tester) async {
      await pumpMiniProfile(tester,
          entry: AccountActionEntry.miniProfile, reports: _FakeReportRepo(fail: true));
      await reportThrough(tester);

      expect(events, isEmpty);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('⚠️ 「其他」的补充说明是用户自由文本，绝不进埋点属性', (tester) async {
      await pumpMiniProfile(tester, entry: AccountActionEntry.miniProfile);
      await tester.tap(find.byKey(const ValueKey('miniProfileMenuReport')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportReason_other')));
      await tester.pumpAndSettle();
      await tester.enterText(
          find.byKey(const ValueKey('accountReportDetail')), '他一直私信骚扰我，还冒充我朋友');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportSubmit')));
      await tester.pumpAndSettle();

      final props = only('social_account_report_submitted').props!;
      for (final v in props.values) {
        expect(v.toString().contains('私信'), isFalse, reason: '用户自由文本泄漏进埋点：$props');
      }
      expect(props['reason'], 'OTHER');
    });
  });

  group('AC3 / AC1：黑名单页', () {
    Future<ProviderContainer> pumpBlocklist(WidgetTester tester,
        {BlockedUsersRepository? blocks}) async {
      final container = ProviderContainer(overrides: [
        blockedUsersRepositoryProvider
            .overrideWithValue(blocks ?? _FakeBlockRepo(items: [_blocked()])),
        accountReportRepositoryProvider.overrideWithValue(_FakeReportRepo()),
        miniProfileRepositoryProvider.overrideWithValue(_FakeMiniRepo()),
        authControllerProvider.overrideWith(_LoggedInAuth.new),
      ]);
      addTearDown(container.dispose);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: BlockedUsersPage(),
        ),
      ));
      await tester.pumpAndSettle();
      return container;
    }

    testWidgets('解除拉黑成功 → origin=BLOCK, entry=blocklist', (tester) async {
      await pumpBlocklist(tester);
      await tester.tap(find.byKey(const ValueKey('blockedUnblock_7')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('confirmUnblockUser')));
      await tester.pumpAndSettle();

      final e = only('social_user_unhide_submitted');
      expect(e.props!['origin'], 'BLOCK'); // 举报隐藏没有解除入口，故恒为 BLOCK
      expect(e.props!['entry'], 'blocklist');
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('解除失败 → 不上报', (tester) async {
      await pumpBlocklist(tester,
          blocks: _FakeBlockRepo(failUnblock: true, items: [_blocked()]));
      await tester.tap(find.byKey(const ValueKey('blockedUnblock_7')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('confirmUnblockUser')));
      await tester.pumpAndSettle();

      expect(events.where((e) => e.event == 'social_user_unhide_submitted'), isEmpty);
      await tester.pump(const Duration(seconds: 3));
    });

    /// ⚠️ 这个数字直接验证「拉黑之后还需不需要举报入口」这个设计判断：
    /// **长期为 0 就说明这个入口可以撤掉**。所以它按「点击」记，不是按「提交成功」记。
    testWidgets('AC3：黑名单页点举报 → 单独一条事件（按点击记，不等提交）', (tester) async {
      await pumpBlocklist(tester);
      await tester.tap(find.byKey(const ValueKey('blockedMore_7')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('blockedMenuReport_7')));
      await tester.pumpAndSettle();

      expect(only('social_blocklist_report_tapped').props!['entry'], 'blocklist');
      // 只点开抽屉、没提交 → 不该有提交事件。
      expect(events.where((e) => e.event == 'social_account_report_submitted'), isEmpty);
    });
  });

  group('AC4 / AC9：清单与护栏', () {
    /// V1.1.4 的事件**必须都在这份清单里**，反过来清单里的也必须真的埋了 ——
    /// 文档说埋了、代码没埋（或反过来）是埋点最常见的失效方式，而它不会以任何形式报错。
    test('V1.1.4 清单与源码一致', () {
      const expected = {
        'social_user_hide_submitted',
        'social_user_unhide_submitted',
        'social_account_report_submitted',
        'social_blocklist_report_tapped',
      };
      final source = _captureNamesIn('lib/features/social')
        ..addAll(_captureNamesIn('lib/shared/widgets/mini_profile_sheet.dart'));
      expect(source.where((e) => e.startsWith('social_user_') || e.contains('_report_')).toSet(),
          expected);
    });

    /// ⚠️ AC4：**影子评论（R2）不做任何单独埋点**。
    /// 被拉黑方无感知是设计前提，任何针对他的行为埋点都不改变产品行为 —— 本版本明确不做。
    test('没有任何影子评论相关的事件', () {
      final all = _captureNamesIn('lib');
      expect(all.where((e) => e.contains('shadow') || e.contains('影子')), isEmpty);
    });

    /// 拉黑/举报是**治理行为、非归因事件**，不应进 AppsFlyer 白名单。
    test('四个事件都不在 AppsFlyer 白名单里', () {
      for (final e in [
        'social_user_hide_submitted',
        'social_user_unhide_submitted',
        'social_account_report_submitted',
        'social_blocklist_report_tapped',
      ]) {
        expect(Analytics.isAppsFlyerEvent(e), isFalse, reason: '$e 不该分发给 AppsFlyer');
      }
    });
  });
}

/// 从源码里正则提取 `Analytics.capture('xxx')` 的事件名字面量。
///
/// ⚠️ 与既有 `v112_events_test` 同一套治理手段：**手抄清单会与代码脱节，而脱节时测试仍绿**。
/// 也正因为如此，本版本的事件名<b>必须写成内联字面量</b>，不能抽成常量 —— 抽了这里就提取不到了。
Set<String> _captureNamesIn(String path) {
  final re = RegExp(r"""Analytics\.capture\(\s*'([A-Za-z0-9_]+)'""");
  final entity = FileSystemEntity.typeSync(path) == FileSystemEntityType.directory
      ? Directory(path).listSync(recursive: true).whereType<File>()
      : [File(path)];
  final names = <String>{};
  for (final f in entity) {
    if (!f.path.endsWith('.dart')) continue;
    for (final m in re.allMatches(f.readAsStringSync())) {
      names.add(m.group(1)!);
    }
  }
  return names;
}
