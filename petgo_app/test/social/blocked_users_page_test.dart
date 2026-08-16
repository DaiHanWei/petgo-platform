import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/social/data/account_report_repository.dart';
import 'package:tailtopia/features/social/data/blocked_users_repository.dart';
import 'package:tailtopia/features/social/domain/account_report_reason.dart';
import 'package:tailtopia/features/social/domain/blocked_user.dart';
import 'package:tailtopia/features/social/presentation/blocked_list_skeleton.dart';
import 'package:tailtopia/features/social/presentation/blocked_users_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/letter_avatar.dart';

/// V1.1.4 Story 1.5：黑名单页三态 + 解除流程 + 注销/封号态。
///
/// ⚠️ 「封号」那半条没有前端逻辑可测 —— 后端根本不下发封号信息，被封号的人在这份数据里
/// 与正常人完全一致。所以这里用「一个普通行」的用例来守住它：**任何人给封号加标记都得先改这条**。

class _FakeRepo implements BlockedUsersRepository {
  _FakeRepo(this.items, {this.failList = false, this.failUnblock = false});

  final List<BlockedUser> items;
  final bool failList;
  final bool failUnblock;
  final List<int> unblocked = <int>[];

  @override
  Future<List<BlockedUser>> list() async {
    if (failList) throw Exception('boom');
    return items;
  }

  @override
  Future<void> unblock(int userId) async {
    if (failUnblock) throw Exception('boom');
    unblocked.add(userId);
  }

  @override
  Future<void> block(int userId) async {}
}

BlockedUser _user({
  int userId = 7,
  String? nickname = 'Rina',
  bool deleted = false,
  bool reported = false,
}) =>
    BlockedUser(
      userId: userId,
      nickname: nickname,
      avatarUrl: null,
      deleted: deleted,
      reported: reported,
      blockedAt: DateTime.utc(2026, 8, 14, 9, 12),
    );

class _FakeReportRepo implements AccountReportRepository {
  final List<int> reported = [];
  @override
  Future<void> report(int targetUserId, AccountReportReason reason, {String? detail}) async =>
      reported.add(targetUserId);
}

Future<void> _pump(WidgetTester tester, _FakeRepo repo,
    {bool settle = true, AccountReportRepository? reportRepo}) async {
  final container = ProviderContainer(overrides: [
    blockedUsersRepositoryProvider.overrideWithValue(repo),
    accountReportRepositoryProvider.overrideWithValue(reportRepo ?? _FakeReportRepo()),
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
  if (settle) await tester.pumpAndSettle();
}

void main() {
  late AppLocalizations l10n;

  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
  });

  group('AC4：三种非正常态', () {
    testWidgets('加载中 → 骨架屏（不是转圈，结构已知就别让布局跳）', (tester) async {
      await _pump(tester, _FakeRepo([_user()]), settle: false);
      await tester.pump(); // 只推进一帧，future 尚未完成

      expect(find.byType(BlockedListSkeleton), findsOneWidget);

      await tester.pumpAndSettle();
    });

    testWidgets('空态 → 给出「去哪儿拉黑」的指引，不是白屏', (tester) async {
      await _pump(tester, _FakeRepo(const []));

      expect(find.text(l10n.blockedListEmptyTitle), findsOneWidget);
      expect(find.text(l10n.blockedListEmptyBody), findsOneWidget);
    });

    testWidgets('⚠️ 加载失败 → 显式报错 + 重试按钮，绝不画成空态', (tester) async {
      await _pump(tester, _FakeRepo(const [], failList: true));

      expect(find.text(l10n.blockedListLoadFailed), findsOneWidget);
      expect(find.byKey(const ValueKey('blockedListRetry')), findsOneWidget);
      // 把请求错误画成「你没拉黑过谁」是 bug 20260625-088 的原样重演。
      expect(find.text(l10n.blockedListEmptyTitle), findsNothing);
    });
  });

  group('AC2 / AC3：行内容与动作', () {
    testWidgets('行含昵称 + 拉黑时间 + 常驻「解除拉黑」按钮', (tester) async {
      await _pump(tester, _FakeRepo([_user()]));

      expect(find.text('Rina'), findsOneWidget);
      expect(find.byKey(const ValueKey('blockedUnblock_7')), findsOneWidget);
      expect(find.textContaining('14 Aug 2026'), findsOneWidget);
    });

    testWidgets('顶部有「他们不会知道你拉黑了他们」', (tester) async {
      await _pump(tester, _FakeRepo([_user()]));
      expect(find.text(l10n.blockedListHint), findsOneWidget);
    });

    testWidgets('被举报过 → 昵称后带「已举报」标签', (tester) async {
      await _pump(tester, _FakeRepo([_user(reported: true)]));
      expect(find.text(l10n.blockedListReportedTag), findsOneWidget);
    });

    testWidgets('只拉黑过 → 无「已举报」标签', (tester) async {
      await _pump(tester, _FakeRepo([_user(reported: false)]));
      expect(find.text(l10n.blockedListReportedTag), findsNothing);
    });

    testWidgets('不使用任何滑动手势（右滑撞系统返回、左滑压在解除按钮上）', (tester) async {
      await _pump(tester, _FakeRepo([_user()]));
      expect(find.byType(Dismissible), findsNothing);
    });
  });

  group('AC5 / AC6：解除流程', () {
    Future<void> tapUnblock(WidgetTester tester) async {
      await tester.tap(find.byKey(const ValueKey('blockedUnblock_7')));
      await tester.pumpAndSettle();
    }

    testWidgets('只拉黑过 → 确认正文说「解除后重新看到 TA 的内容」', (tester) async {
      await _pump(tester, _FakeRepo([_user()]));
      await tapUnblock(tester);

      expect(find.text(l10n.unblockConfirmTitle('Rina')), findsOneWidget);
      expect(find.text(l10n.unblockConfirmBody), findsOneWidget);
      expect(find.text(l10n.unblockConfirmBodyReported), findsNothing);
    });

    testWidgets('⚠️ 也被举报过 → 确认正文改成「内容仍不会展示」（动手前就说清楚）', (tester) async {
      await _pump(tester, _FakeRepo([_user(reported: true)]));
      await tapUnblock(tester);

      expect(find.text(l10n.unblockConfirmBodyReported), findsOneWidget);
      expect(find.text(l10n.unblockConfirmBody), findsNothing);
    });

    testWidgets('成功 → 条目移除 + Toast「刷新后可重新看到」', (tester) async {
      final repo = _FakeRepo([_user()]);
      await _pump(tester, repo);
      await tapUnblock(tester);

      await tester.tap(find.byKey(const ValueKey('confirmUnblockUser')));
      await tester.pumpAndSettle();

      expect(repo.unblocked, <int>[7]);
      expect(find.byKey(const ValueKey('blockedUnblock_7')), findsNothing); // 行没了
      expect(find.text(l10n.unblockSuccess), findsOneWidget);
      await tester.pump(const Duration(seconds: 3)); // 放掉 toast 定时器
    });

    testWidgets('也被举报过时成功 → 条目照样移除，但 Toast 换成「内容仍不展示」', (tester) async {
      final repo = _FakeRepo([_user(reported: true)]);
      await _pump(tester, repo);
      await tapUnblock(tester);

      await tester.tap(find.byKey(const ValueKey('confirmUnblockUser')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('blockedUnblock_7')), findsNothing); // 拉黑关系确实解除了
      expect(find.text(l10n.unblockSuccessReported), findsOneWidget);
      expect(find.text(l10n.unblockSuccess), findsNothing);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('失败 → 抽屉保持打开 + 失败 Toast，条目仍在', (tester) async {
      await _pump(tester, _FakeRepo([_user()], failUnblock: true));
      await tapUnblock(tester);

      await tester.tap(find.byKey(const ValueKey('confirmUnblockUser')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('confirmUnblockUser')), findsOneWidget);
      expect(find.text(l10n.unblockFailed), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
    });
  });

  group('AC8：封号 ≠ 注销', () {
    testWidgets('已注销 → 匿名昵称 + 默认头像，但「解除拉黑」按钮**仍可用**', (tester) async {
      await _pump(tester, _FakeRepo([_user(nickname: null, deleted: true)]));

      expect(find.text(l10n.feedDeletedUser), findsOneWidget);
      expect(tester.widget<LetterAvatar>(find.byType(LetterAvatar)).deleted, isTrue);

      // ⚠️ 去点击态只作用于头像与昵称；按钮禁用了用户就永远清不掉这一行。
      final button = tester.widget<OutlinedButton>(find.byKey(const ValueKey('blockedUnblock_7')));
      expect(button.onPressed, isNotNull);
    });

    testWidgets('普通行（含被封号者）→ 昵称头像照常，无任何附加标记', (tester) async {
      // 后端不下发封号信息，被封号的人到前端就是这么一行普通数据。
      await _pump(tester, _FakeRepo([_user()]));

      expect(find.text('Rina'), findsOneWidget);
      expect(find.text(l10n.feedDeletedUser), findsNothing);
      expect(find.text(l10n.blockedListReportedTag), findsNothing);
    });
  });

  // ===== Story 2.4：行内「⋯」→ 举报 =====

  group('Story 2.4：黑名单页的行内举报入口', () {
    testWidgets('AC1：每行渲染「⋯」，点开是含「举报」的菜单', (tester) async {
      await _pump(tester, _FakeRepo([_user()]));

      expect(find.byKey(const ValueKey('blockedMore_7')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('blockedMore_7')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('blockedMenuReport_7')), findsOneWidget);
      expect(find.text(l10n.accountReportAction), findsOneWidget);
    });

    testWidgets('AC1：「解除拉黑」仍是常驻按钮，两个热区分开；无任何滑动手势', (tester) async {
      await _pump(tester, _FakeRepo([_user()]));

      expect(find.byKey(const ValueKey('blockedUnblock_7')), findsOneWidget);
      expect(find.byType(Dismissible), findsNothing);
      // 两个热区不重叠——重叠就必然误触。
      final more = tester.getRect(find.byKey(const ValueKey('blockedMore_7')));
      final unblock = tester.getRect(find.byKey(const ValueKey('blockedUnblock_7')));
      expect(more.overlaps(unblock), isFalse);
    });

    testWidgets('点举报 → 走 Story 2.2 的同一套抽屉（五类 + 「提交后无法撤销」）', (tester) async {
      await _pump(tester, _FakeRepo([_user()]));
      await tester.tap(find.byKey(const ValueKey('blockedMore_7')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('blockedMenuReport_7')));
      await tester.pumpAndSettle();

      expect(find.text(l10n.accountReportTitle), findsOneWidget);
      expect(find.byKey(const ValueKey('accountReportIrreversible')), findsOneWidget);
      expect(find.byKey(const ValueKey('accountReportReason_spam')), findsOneWidget);
    });

    testWidgets('⚠️ AC3：举报成功后该行**仍在列表里**，只是多了「已举报」标签', (tester) async {
      final reportRepo = _FakeReportRepo();
      await _pump(tester, _FakeRepo([_user()]), reportRepo: reportRepo);
      expect(find.text(l10n.blockedListReportedTag), findsNothing);

      await tester.tap(find.byKey(const ValueKey('blockedMore_7')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('blockedMenuReport_7')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportReason_harassment')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportSubmit')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportDoneClose')));
      await tester.pumpAndSettle();

      expect(reportRepo.reported, <int>[7]);
      // 这一行在本页是因为它含 BLOCK；举报不碰 BLOCK，所以行还在、位置也不变。
      // 移除它会让用户以为拉黑也一起解除了。
      expect(find.byKey(const ValueKey('blockedUnblock_7')), findsOneWidget);
      expect(find.text(l10n.blockedListReportedTag), findsOneWidget);
    });

    testWidgets('AC2：已举报过的人，菜单里的举报项**照样可点**（允许重复举报）', (tester) async {
      await _pump(tester, _FakeRepo([_user(reported: true)]));
      await tester.tap(find.byKey(const ValueKey('blockedMore_7')));
      await tester.pumpAndSettle();

      final item = tester.widget<InkWell>(find.byKey(const ValueKey('blockedMenuReport_7')));
      expect(item.onTap, isNotNull);

      await tester.tap(find.byKey(const ValueKey('blockedMenuReport_7')));
      await tester.pumpAndSettle();
      // 进去就是重复举报态（副标题换成说明块）。
      expect(find.byKey(const ValueKey('accountReportRepeatNotice')), findsOneWidget);
    });
  });
}
