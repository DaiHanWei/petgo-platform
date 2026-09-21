import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/mention/data/mention_candidate_repository.dart';
import 'package:tailtopia/features/mention/presentation/mention_picker.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：@ 选择器浮层（V1.3.0 batch-b1 Story 3.2 · AC2/AC3）。
///
/// <h3>🔴 本文件同时是「别把过滤框做成搜索框」这条范围红线的机械守卫</h3>
/// 没有全局用户搜索（留在 1.6.0，未前移 —— AD-10 Rule 3）。界面上如果长得像搜索框、
/// 文案写"搜索用户"，用户会期待搜到任何人，而实际只能搜 30 个 —— 这比没有更糟。
/// 所以下面有两条用例直接钉住文案与图标。
class _FakeRepo implements MentionCandidateRepository {
  _FakeRepo(this.candidates, {this.fail = false});

  final List<MentionCandidate> candidates;
  final bool fail;
  int calls = 0;

  @override
  Future<List<MentionCandidate>> getCandidates() async {
    calls++;
    if (fail) throw Exception('boom');
    return candidates;
  }
}

MentionCandidate _c(int id, String nickname) =>
    MentionCandidate(userId: id, nickname: nickname);

void main() {
  Future<MentionCandidate?> pumpPicker(
    WidgetTester tester,
    MentionCandidateRepository repo, {
    String keyword = '',
  }) async {
    MentionCandidate? picked;
    final container = ProviderContainer(
      overrides: [mentionCandidateRepositoryProvider.overrideWithValue(repo)],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: MentionPicker(keyword: keyword, onSelected: (c) => picked = c),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    return picked;
  }

  testWidgets('AC1：浮层组件存在，候选逐行渲染', (tester) async {
    await pumpPicker(tester, _FakeRepo([_c(1, 'Aurel'), _c(2, 'Budi')]));
    expect(find.byKey(const ValueKey('mentionPicker')), findsOneWidget);
    expect(find.byKey(const ValueKey('mentionCandidate_1')), findsOneWidget);
    expect(find.byKey(const ValueKey('mentionCandidate_2')), findsOneWidget);
  });

  testWidgets('AC1：点一行 → 把该候选交回调用方（由它插文本 + 绑 userId）', (tester) async {
    MentionCandidate? picked;
    final container = ProviderContainer(overrides: [
      mentionCandidateRepositoryProvider.overrideWithValue(_FakeRepo([_c(7, 'Aurel')])),
    ]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(body: MentionPicker(keyword: '', onSelected: (c) => picked = c)),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('mentionCandidate_7')));
    await tester.pumpAndSettle();
    expect(picked?.userId, 7);
  });

  testWidgets('AC2：正文里 @ 后面打的字就是过滤条件', (tester) async {
    await pumpPicker(tester, _FakeRepo([_c(1, 'Aurel'), _c(2, 'Budi')]), keyword: 'bu');
    expect(find.byKey(const ValueKey('mentionCandidate_2')), findsOneWidget);
    expect(find.byKey(const ValueKey('mentionCandidate_1')), findsNothing);
  });

  testWidgets('AC2：过滤不分大小写', (tester) async {
    await pumpPicker(tester, _FakeRepo([_c(1, 'Aurel')]), keyword: 'AUR');
    expect(find.byKey(const ValueKey('mentionCandidate_1')), findsOneWidget);
  });

  testWidgets('AC2：浮层内过滤框只在这批人之内筛', (tester) async {
    await pumpPicker(tester, _FakeRepo([_c(1, 'Aurel'), _c(2, 'Budi')]));
    await tester.enterText(find.byKey(const ValueKey('mentionFilterInput')), 'aur');
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mentionCandidate_1')), findsOneWidget);
    expect(find.byKey(const ValueKey('mentionCandidate_2')), findsNothing);
  });

  testWidgets('AC2 🔴 过滤只在候选内做：筛不到就是空，绝不发第二次请求去"搜"', (tester) async {
    // ⚠️ 这条是范围红线：没有全局用户搜索（1.6.0，未前移）。
    // 打字触发一次网络请求 = 那个输入框已经变成搜索框了。
    final repo = _FakeRepo([_c(1, 'Aurel')]);
    await pumpPicker(tester, repo);
    expect(repo.calls, 1);
    await tester.enterText(find.byKey(const ValueKey('mentionFilterInput')), 'zzz');
    await tester.pumpAndSettle();
    expect(repo.calls, 1, reason: '过滤是纯客户端的，不该再打接口');
    expect(find.byKey(const ValueKey('mentionCandidate_1')), findsNothing);
  });

  testWidgets('AC2 🔴 文案不许承诺"搜索用户"，也不许出现放大镜', (tester) async {
    await pumpPicker(tester, _FakeRepo([_c(1, 'Aurel')]));
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    // 占位说的是"找个名字"，不是"搜索用户"。
    expect(find.text(l10n.mentionFilterHint), findsOneWidget);
    for (final forbidden in <String>['search', 'Search', 'cari user', 'semua pengguna']) {
      expect(l10n.mentionFilterHint.contains(forbidden), isFalse,
          reason: '占位文案不得暗示能搜到任何用户：$forbidden');
    }
    // 放大镜图标本身就是"能搜到任何人"的视觉承诺。
    expect(find.byIcon(Icons.search), findsNothing);
    expect(find.byIcon(Icons.search_rounded), findsNothing);
  });

  testWidgets('AC2：筛不出来说的是"没有匹配的名字"，不是"用户不存在"', (tester) async {
    await pumpPicker(tester, _FakeRepo([_c(1, 'Aurel')]), keyword: 'zzz');
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text(l10n.mentionNoMatch), findsOneWidget);
    // 筛不出来不是「空」，不配 D2 那个人像图标。
    expect(find.byIcon(Icons.people_outline_rounded), findsNothing);
    // 「用户不存在 / 没找到该用户」是在承诺全局搜索过了 —— 不许这么说。
    for (final forbidden in <String>['not found', 'no user', 'tidak ditemukan']) {
      expect(l10n.mentionNoMatch.toLowerCase().contains(forbidden), isFalse);
    }
  });

  testWidgets('AC3：一个候选都没有 → 空态，且**不显示过滤输入框**', (tester) async {
    await pumpPicker(tester, _FakeRepo(const []));
    expect(find.byKey(const ValueKey('mentionPickerEmpty')), findsOneWidget);
    // 🔴 一个搜不出任何东西的输入框只会让人一直敲。
    expect(find.byKey(const ValueKey('mentionFilterInput')), findsNothing);
    // UI 稿 D2：候选为空的空态顶部有人像图标。
    expect(find.byIcon(Icons.people_outline_rounded), findsOneWidget);
  });

  testWidgets('AC3：空态告诉用户怎么才能有候选（去互动）', (tester) async {
    await pumpPicker(tester, _FakeRepo(const []));
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text(l10n.mentionEmptyTitle), findsOneWidget);
    expect(find.text(l10n.mentionEmptyHint), findsOneWidget);
  });

  testWidgets('取数失败与"没有候选"走同一个空态（用户能做的事完全一样）', (tester) async {
    await pumpPicker(tester, _FakeRepo(const [], fail: true));
    expect(find.byKey(const ValueKey('mentionPickerEmpty')), findsOneWidget);
    expect(find.byKey(const ValueKey('mentionFilterInput')), findsNothing);
  });
}
