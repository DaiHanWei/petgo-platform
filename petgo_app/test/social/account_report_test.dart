import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/mini_profile_repository.dart';
import 'package:tailtopia/features/social/data/account_report_repository.dart';
import 'package:tailtopia/features/social/data/blocked_users_repository.dart';
import 'package:tailtopia/features/social/domain/account_report_reason.dart';
import 'package:tailtopia/features/social/domain/blocked_user.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/mini_profile_sheet.dart';

/// V1.1.4 Story 2.2：账号举报抽屉全流程 + 迷你卡菜单的举报项 / 已举报态。

const int _kViewerId = 5;
const int _kTargetId = 7;

class _FakeMiniRepo implements MiniProfileRepository {
  _FakeMiniRepo(this.profile);
  final MiniProfile profile;
  @override
  Future<MiniProfile> getMiniProfile(int userId) async => profile;
}

class _FakeReportRepo implements AccountReportRepository {
  _FakeReportRepo({this.fail = false, this.gate});

  final bool fail;
  final Completer<void>? gate;
  final List<({int userId, AccountReportReason reason, String? detail})> sent = [];

  @override
  Future<void> report(int targetUserId, AccountReportReason reason, {String? detail}) async {
    if (gate != null) await gate!.future;
    if (fail) throw Exception('boom');
    sent.add((userId: targetUserId, reason: reason, detail: detail));
  }
}

class _NoopBlockRepo implements BlockedUsersRepository {
  @override
  Future<void> block(int userId) async {}
  @override
  Future<List<BlockedUser>> list() async => const <BlockedUser>[];
  @override
  Future<void> unblock(int userId) async {}
}

class _LoggedInAuth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: _kViewerId, nickname: 'Me', onboardingCompleted: true),
      );
}

MiniProfile _profile({bool reported = false}) => MiniProfile(
      postCount: 3,
      isDeactivated: false,
      nickname: 'Rina',
      avatarUrl: null,
      reported: reported,
    );

/// 打开迷你卡 →「⋯」菜单（举报抽屉的唯一入口）。
Future<_FakeReportRepo> _pumpMenu(WidgetTester tester,
    {bool reported = false, _FakeReportRepo? repo}) async {
  final reportRepo = repo ?? _FakeReportRepo();
  final container = ProviderContainer(overrides: [
    miniProfileRepositoryProvider.overrideWithValue(_FakeMiniRepo(_profile(reported: reported))),
    accountReportRepositoryProvider.overrideWithValue(reportRepo),
    blockedUsersRepositoryProvider.overrideWithValue(_NoopBlockRepo()),
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
              onPressed: () => showMiniProfile(context, ref, _kTargetId),
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
  return reportRepo;
}

/// 一路点到举报抽屉。
Future<_FakeReportRepo> _pumpSheet(WidgetTester tester,
    {bool reported = false, _FakeReportRepo? repo}) async {
  final r = await _pumpMenu(tester, reported: reported, repo: repo);
  await tester.tap(find.byKey(const ValueKey('miniProfileMenuReport')));
  await tester.pumpAndSettle();
  return r;
}

void main() {
  late AppLocalizations l10n;

  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
  });

  group('AC1 / AC2：菜单里的举报项', () {
    testWidgets('未举报过 → 「举报」项可点，与「拉黑」并列', (tester) async {
      await _pumpMenu(tester);

      expect(find.text(l10n.accountReportAction), findsOneWidget);
      expect(find.text(l10n.blockUserAction), findsOneWidget);
    });

    testWidgets('⚠️ 已举报过 → 文案换成「已举报 / 点击可再次举报」，且拉黑项照常可点不置灰', (tester) async {
      await _pumpMenu(tester, reported: true);

      expect(find.text(l10n.accountReportedAction), findsOneWidget);
      expect(find.text(l10n.accountReportedActionSub), findsOneWidget);
      expect(find.text(l10n.accountReportAction), findsNothing);
      // 举报之后拉黑仍然有意义（拉黑还能禁止进对方主页），不得以「已举报」为由禁掉。
      final block = tester.widget<InkWell>(find.byKey(const ValueKey('miniProfileMenuBlock')));
      expect(block.onTap, isNotNull);
    });

    testWidgets('点举报项 → 弹出账号举报抽屉（标题是「举报这个账号」不是内容）', (tester) async {
      await _pumpSheet(tester);

      expect(find.text(l10n.accountReportTitle), findsOneWidget);
      expect(find.text(l10n.reportTitle), findsNothing); // 内容维度的标题不该出现
    });
  });

  group('AC3：表单', () {
    testWidgets('五类账号维度选项都在（不是内容维度那五类）', (tester) async {
      await _pumpSheet(tester);

      expect(find.text(l10n.accountReportReasonSpam), findsOneWidget);
      expect(find.text(l10n.accountReportReasonImpersonation), findsOneWidget);
      expect(find.text(l10n.accountReportReasonHarassment), findsOneWidget);
      expect(find.text(l10n.accountReportReasonViolatingContent), findsOneWidget);
      expect(find.text(l10n.accountReportReasonOther), findsOneWidget);
      // 内容维度的「虚假信息」不该出现在账号举报里。
      expect(find.text(l10n.reportReasonMisinfo), findsNothing);
    });

    testWidgets('未选类型 → 提交按钮禁用', (tester) async {
      await _pumpSheet(tester);

      final submit =
          tester.widget<FilledButton>(find.byKey(const ValueKey('accountReportSubmit')));
      expect(submit.onPressed, isNull);
    });

    testWidgets('选了普通类型 → 提交可用，且不展开补充说明框', (tester) async {
      await _pumpSheet(tester);
      await tester.tap(find.byKey(const ValueKey('accountReportReason_harassment')));
      await tester.pumpAndSettle();

      expect(tester.widget<FilledButton>(
              find.byKey(const ValueKey('accountReportSubmit'))).onPressed,
          isNotNull);
      expect(find.byKey(const ValueKey('accountReportDetail')), findsNothing);
    });

    testWidgets('只有「其他」展开必填框；没填之前提交仍禁用，填了才放行', (tester) async {
      await _pumpSheet(tester);
      await tester.tap(find.byKey(const ValueKey('accountReportReason_other')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('accountReportDetail')), findsOneWidget);
      expect(find.text('0 / 200'), findsOneWidget);
      expect(tester.widget<FilledButton>(
              find.byKey(const ValueKey('accountReportSubmit'))).onPressed,
          isNull);

      await tester.enterText(find.byKey(const ValueKey('accountReportDetail')), '他冒充我朋友');
      await tester.pumpAndSettle();

      expect(find.text('6 / 200'), findsOneWidget);
      expect(tester.widget<FilledButton>(
              find.byKey(const ValueKey('accountReportSubmit'))).onPressed,
          isNotNull);
    });
  });

  group('AC4：「提交后无法撤销」六个表单态都在', () {
    testWidgets('B1 未选 / B2 其他展开 / B3 已选 / B7 重复举报', (tester) async {
      await _pumpSheet(tester);
      expect(find.byKey(const ValueKey('accountReportIrreversible')), findsOneWidget); // B1

      await tester.tap(find.byKey(const ValueKey('accountReportReason_other')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('accountReportIrreversible')), findsOneWidget); // B2

      await tester.tap(find.byKey(const ValueKey('accountReportReason_spam')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('accountReportIrreversible')), findsOneWidget); // B3
    });

    testWidgets('B7 重复举报态也有', (tester) async {
      await _pumpSheet(tester, reported: true);
      expect(find.byKey(const ValueKey('accountReportIrreversible')), findsOneWidget);
    });

    testWidgets('B4 提交中 / B6 失败态也有', (tester) async {
      final gate = Completer<void>();
      await _pumpSheet(tester, repo: _FakeReportRepo(fail: true, gate: gate));
      await tester.tap(find.byKey(const ValueKey('accountReportReason_spam')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportSubmit')));
      await tester.pump();

      expect(find.byKey(const ValueKey('accountReportIrreversible')), findsOneWidget); // B4

      gate.complete();
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('accountReportIrreversible')), findsOneWidget); // B6
      await tester.pump(const Duration(seconds: 3));
    });
  });

  group('AC5 / AC6 / AC7：提交中 · 成功 · 失败', () {
    testWidgets('提交中 → 选项与取消一并禁用 + 按钮内转圈，抽屉不关', (tester) async {
      final gate = Completer<void>();
      await _pumpSheet(tester, repo: _FakeReportRepo(gate: gate));
      await tester.tap(find.byKey(const ValueKey('accountReportReason_spam')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportSubmit')));
      await tester.pump();

      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      expect(tester.widget<OutlinedButton>(
              find.byKey(const ValueKey('accountReportCancel'))).onPressed,
          isNull);
      expect(tester.widget<InkWell>(
              find.byKey(const ValueKey('accountReportReason_spam'))).onTap,
          isNull);
      expect(find.byKey(const ValueKey('accountReportSubmit')), findsOneWidget); // 抽屉还开着

      gate.complete();
      await tester.pumpAndSettle();
    });

    testWidgets('成功 → 抽屉内原地切成功态（不 Toast、不自动收起）；点关闭两层一并收起', (tester) async {
      final repo = await _pumpSheet(tester);
      await tester.tap(find.byKey(const ValueKey('accountReportReason_harassment')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportSubmit')));
      await tester.pumpAndSettle();

      expect(repo.sent.single.reason, AccountReportReason.harassment);
      expect(repo.sent.single.detail, isNull);
      expect(find.text(l10n.reportDoneTitle), findsOneWidget);
      expect(find.byKey(const ValueKey('accountReportDoneClose')), findsOneWidget);
      // 成功态刻意不提「他的内容会被隐藏」（举报帖子才告知，举报账号不告知）。
      expect(find.text(l10n.reportHiddenToast), findsNothing);

      await tester.tap(find.byKey(const ValueKey('accountReportDoneClose')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('accountReportDoneClose')), findsNothing); // 抽屉收起
      expect(find.byKey(const ValueKey('miniProfileClose')), findsNothing);       // 迷你卡也收起
    });

    testWidgets('「其他」的补充说明会真的发出去', (tester) async {
      final repo = await _pumpSheet(tester);
      await tester.tap(find.byKey(const ValueKey('accountReportReason_other')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('accountReportDetail')), '他一直私信我');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportSubmit')));
      await tester.pumpAndSettle();

      expect(repo.sent.single.reason, AccountReportReason.other);
      expect(repo.sent.single.detail, '他一直私信我');
    });

    testWidgets('⚠️ 失败 → 抽屉不关、已选类型与已填说明都不清空 + 顶部 Toast', (tester) async {
      await _pumpSheet(tester, repo: _FakeReportRepo(fail: true));
      await tester.tap(find.byKey(const ValueKey('accountReportReason_other')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('accountReportDetail')), '别清空我');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportSubmit')));
      await tester.pumpAndSettle();

      // 既有内容举报的 catch 只把按钮解禁、不给任何提示——这里必须有话说。
      expect(find.text(l10n.accountReportFailed), findsOneWidget);
      expect(find.byKey(const ValueKey('accountReportSubmit')), findsOneWidget);
      expect(find.text('别清空我'), findsOneWidget); // 说明没被清掉
      expect(tester.widget<FilledButton>(
              find.byKey(const ValueKey('accountReportSubmit'))).onPressed,
          isNotNull); // 可以直接再点
      await tester.pump(const Duration(seconds: 3));
    });
  });

  group('AC8：重复举报', () {
    testWidgets('已举报过 → 副标题换成说明块，且类型不预填', (tester) async {
      await _pumpSheet(tester, reported: true);

      expect(find.byKey(const ValueKey('accountReportRepeatNotice')), findsOneWidget);
      expect(find.text(l10n.reportSubtitle), findsNothing);
      // 类型重新选：提交按钮此刻仍是禁用的（说明没有预填上次的选择）。
      expect(tester.widget<FilledButton>(
              find.byKey(const ValueKey('accountReportSubmit'))).onPressed,
          isNull);
    });
  });
}
