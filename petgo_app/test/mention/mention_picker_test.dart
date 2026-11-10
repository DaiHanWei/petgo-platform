import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/mention/data/mention_candidate_repository.dart';
import 'package:tailtopia/features/mention/presentation/mention_picker.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：@ 选择器面板（V1.3.0 batch-b1 Story 3.2 起；V1.4.2 social-topic Story 5.2 改为「打字即查」· UI E1–E6）。
///
/// <h3>🔴 本文件同时是「别把它做成全局搜索」这条范围红线的机械守卫</h3>
/// 接口只在关注 / 粉丝 / 互动三类范围内查（Story 5.1），没有全局用户搜索（留在 1.6.0）。
/// 界面上如果长得像搜索框、文案写"搜索用户"，用户会期待搜到任何人 —— 这比没有更糟。
/// 所以下面有用例直接钉住文案与图标。
class _FakeRepo implements MentionCandidateRepository {
  _FakeRepo(this.candidates, {this.fail = false, this.onSearch});

  final List<MentionCandidate> candidates;
  bool fail;
  int calls = 0;

  /// 每次 `search` 的关键词（按调用顺序）。
  final List<String> searched = <String>[];

  /// 自定义 search 行为；不给 → 按昵称包含（不分大小写）筛 [candidates]，保持顺序。
  Future<List<MentionCandidate>> Function(String keyword)? onSearch;

  @override
  Future<List<MentionCandidate>> getCandidates() async {
    calls++;
    if (fail) throw Exception('boom');
    return candidates;
  }

  @override
  Future<List<MentionCandidate>> search(String keyword, {CancelToken? cancelToken}) async {
    searched.add(keyword);
    if (onSearch != null) return onSearch!(keyword);
    return candidates
        .where((c) => c.nickname.toLowerCase().contains(keyword.toLowerCase()))
        .toList(growable: false);
  }
}

MentionCandidate _c(int id, String nickname) =>
    MentionCandidate(userId: id, nickname: nickname);

DioException _http(int status) {
  final RequestOptions ro = RequestOptions(path: '/api/v1/me/mention-candidates');
  return DioException(
    requestOptions: ro,
    type: DioExceptionType.badResponse,
    response: Response<dynamic>(requestOptions: ro, statusCode: status),
  );
}

void main() {
  /// 用 [keyword] 驱动面板：改 `keyword.value` = 模拟用户在正文 @ 后继续打字 / 删字。
  Future<ValueNotifier<String>> pumpPicker(
    WidgetTester tester,
    MentionCandidateRepository repo, {
    String initial = '',
    Locale locale = const Locale('en'),
    void Function(MentionCandidate)? onSelected,
    bool settle = true,
  }) async {
    final ValueNotifier<String> keyword = ValueNotifier<String>(initial);
    addTearDown(keyword.dispose);
    final container = ProviderContainer(
      overrides: [mentionCandidateRepositoryProvider.overrideWithValue(repo)],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        locale: locale,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: ValueListenableBuilder<String>(
            valueListenable: keyword,
            builder: (context, k, _) =>
                MentionPicker(keyword: k, onSelected: onSelected ?? (_) {}),
          ),
        ),
      ),
    ));
    if (settle) await tester.pumpAndSettle();
    return keyword;
  }

  List<int> shownIds(WidgetTester tester) => tester
      .widgetList<InkWell>(find.byWidgetPredicate(
          (w) => w is InkWell && w.key is ValueKey<String> &&
              (w.key! as ValueKey<String>).value.startsWith('mentionCandidate_')))
      .map((w) => int.parse((w.key! as ValueKey<String>).value.substring('mentionCandidate_'.length)))
      .toList();

  // ===== E1 快捷列表 =====

  testWidgets('E1：@ 后无字 → 快捷列表，顺序 = 接口顺序；面板内没有任何输入框（过滤框已移除）', (tester) async {
    final repo = _FakeRepo([_c(3, 'Citra'), _c(1, 'Aurel'), _c(2, 'Budi')]);
    await pumpPicker(tester, repo);
    expect(find.byKey(const ValueKey('mentionPicker')), findsOneWidget);
    expect(shownIds(tester), [3, 1, 2]);
    expect(find.descendant(of: find.byKey(const ValueKey('mentionPicker')), matching: find.byType(TextField)),
        findsNothing);
    expect(find.byKey(const ValueKey('mentionFilterInput')), findsNothing);
    expect(repo.searched, isEmpty, reason: '无字时只走快捷列表，不发带 q 的请求');
  });

  testWidgets('E1：点一行 → 把该候选交回调用方（由它插文本 + 绑 userId）', (tester) async {
    MentionCandidate? picked;
    await pumpPicker(tester, _FakeRepo([_c(7, 'Aurel')]), onSelected: (c) => picked = c);
    await tester.tap(find.byKey(const ValueKey('mentionCandidate_7')));
    await tester.pumpAndSettle();
    expect(picked?.userId, 7);
  });

  // ===== E2 查询中 / 防抖 =====

  testWidgets('E2：打字后 299ms 内不发请求、显示转圈（无底圈）；到 300ms 发一次，参数 = 当前关键词', (tester) async {
    final repo = _FakeRepo([_c(1, 'Aurel'), _c(2, 'Budi')]);
    final keyword = await pumpPicker(tester, repo);
    keyword.value = 'bu';
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 299));
    expect(repo.searched, isEmpty);
    expect(find.byKey(const ValueKey('mentionPickerLoading')), findsOneWidget);
    expect(find.byKey(const ValueKey('mentionCandidate_1')), findsNothing, reason: '不展示上一批结果');
    final spinner = tester.widget<CircularProgressIndicator>(find.byType(CircularProgressIndicator));
    expect(spinner.strokeWidth, 2);
    expect(spinner.backgroundColor, isNull);
    await tester.pump(const Duration(milliseconds: 1));
    expect(repo.searched, ['bu']);
    await tester.pumpAndSettle();
    expect(shownIds(tester), [2]);
  });

  testWidgets('E2：300ms 内连续打字只发最后一次', (tester) async {
    final repo = _FakeRepo([_c(1, 'Rara')]);
    final keyword = await pumpPicker(tester, repo);
    keyword.value = 'r';
    await tester.pump(const Duration(milliseconds: 200));
    keyword.value = 'ra';
    await tester.pump(const Duration(milliseconds: 200));
    keyword.value = 'rar';
    await tester.pump(const Duration(milliseconds: 200));
    expect(repo.searched, isEmpty);
    await tester.pumpAndSettle();
    expect(repo.searched, ['rar']);
  });

  testWidgets('关键词透传给 search（大小写原样，匹配是服务端的事）', (tester) async {
    final repo = _FakeRepo([_c(1, 'Aurel')]);
    await pumpPicker(tester, repo, initial: 'AUR');
    expect(repo.searched, ['AUR']);
    expect(shownIds(tester), [1]);
  });

  // ===== E3 查询结果 =====

  testWidgets('E3：返回 3 条 → 3 行，顺序原样（不在本地二次排序 / 过滤）', (tester) async {
    final repo = _FakeRepo(const [], onSearch: (_) async => [_c(9, 'Zed'), _c(4, 'Ana'), _c(6, 'Mo')]);
    await pumpPicker(tester, repo, initial: 'x');
    expect(shownIds(tester), [9, 4, 6]);
  });

  // ===== E4 无结果 =====

  testWidgets('E4：带 q 返回空 → "没有匹配的名字"一行，无图标；不是"用户不存在"', (tester) async {
    await pumpPicker(tester, _FakeRepo([_c(1, 'Aurel')]), initial: 'zzz');
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text(l10n.mentionNoMatch), findsOneWidget);
    expect(find.byIcon(Icons.people_outline_rounded), findsNothing);
    for (final forbidden in <String>['not found', 'no user', 'tidak ditemukan']) {
      expect(l10n.mentionNoMatch.toLowerCase().contains(forbidden), isFalse);
    }
  });

  // ===== E5 查询失败 =====

  testWidgets('E5：search 失败（429）→ 失败提示 + 重试；点重试立刻再查（不等 300ms）→ 成功后 E3', (tester) async {
    bool fail = true;
    final repo = _FakeRepo(const [], onSearch: (_) async {
      if (fail) throw _http(429);
      return [_c(5, 'Budi')];
    });
    await pumpPicker(tester, repo, initial: 'bu');
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.byKey(const ValueKey('mentionPickerError')), findsOneWidget);
    expect(find.text(l10n.detailNetworkError), findsOneWidget);
    expect(find.byKey(const ValueKey('mentionPickerRetry')), findsOneWidget);
    expect(find.text(l10n.commonRetry), findsOneWidget);
    expect(find.byKey(const ValueKey('mentionPickerEmpty')), findsNothing, reason: '失败不落 E6 / E4');
    // 热区 ≥ 44。
    expect(tester.getSize(find.byKey(const ValueKey('mentionPickerRetry'))).height, greaterThanOrEqualTo(44));

    fail = false;
    await tester.tap(find.byKey(const ValueKey('mentionPickerRetry')));
    await tester.pump();
    expect(repo.searched, ['bu', 'bu'], reason: '重试立即发，不等防抖');
    await tester.pumpAndSettle();
    expect(shownIds(tester), [5]);
  });

  for (final int status in <int>[422, 500]) {
    testWidgets('E5：search $status 同样落 E5', (tester) async {
      await pumpPicker(tester, _FakeRepo(const [], onSearch: (_) async => throw _http(status)), initial: 'q');
      expect(find.byKey(const ValueKey('mentionPickerError')), findsOneWidget);
    });
  }

  testWidgets('E5：快捷列表取数失败也是 E5（不是 E6 空态）；点重试重取快捷列表', (tester) async {
    final repo = _FakeRepo([_c(1, 'Aurel')], fail: true);
    await pumpPicker(tester, repo);
    expect(find.byKey(const ValueKey('mentionPickerError')), findsOneWidget);
    expect(find.byKey(const ValueKey('mentionPickerEmpty')), findsNothing);
    expect(find.byIcon(Icons.people_outline_rounded), findsNothing);
    expect(repo.calls, 1);

    repo.fail = false;
    await tester.tap(find.byKey(const ValueKey('mentionPickerRetry')));
    await tester.pumpAndSettle();
    expect(repo.calls, 2);
    expect(shownIds(tester), [1]);
    expect(repo.searched, isEmpty);
  });

  // ===== E6 一个人都没有 =====

  testWidgets('E6：快捷列表返回空 → 图标 + 标题 + 新提示（en）', (tester) async {
    await pumpPicker(tester, _FakeRepo(const []));
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.byKey(const ValueKey('mentionPickerEmpty')), findsOneWidget);
    expect(find.byIcon(Icons.people_outline_rounded), findsOneWidget);
    expect(find.text(l10n.mentionEmptyTitle), findsOneWidget);
    expect(find.text('Follow someone first, then you can mention them here'), findsOneWidget);
    expect(find.byType(TextField), findsNothing);
  });

  testWidgets('E6：快捷列表返回空 → 新提示（id）', (tester) async {
    await pumpPicker(tester, _FakeRepo(const []), locale: const Locale('id'));
    final l10n = await AppLocalizations.delegate.load(const Locale('id'));
    expect(find.text(l10n.mentionEmptyTitle), findsOneWidget);
    expect(find.text('Ikuti seseorang dulu, nanti kamu bisa menyebut mereka di sini'), findsOneWidget);
  });

  // ===== 丢弃旧结果 / 删空 / dispose =====

  testWidgets('🔴 丢弃旧结果：ra 的请求挂起，改成 rar 后它先回；ra 晚到也不覆盖', (tester) async {
    final Map<String, Completer<List<MentionCandidate>>> pending = {};
    final repo = _FakeRepo(const [], onSearch: (k) {
      final c = Completer<List<MentionCandidate>>();
      pending[k] = c;
      return c.future;
    });
    final keyword = await pumpPicker(tester, repo);
    keyword.value = 'ra';
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(pending.keys, ['ra']);
    keyword.value = 'rar';
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(pending.keys, ['ra', 'rar']);

    pending['rar']!.complete([_c(2, 'Rara')]);
    await tester.pump();
    pending['ra']!.complete([_c(1, 'Raka'), _c(3, 'Ratna')]);
    await tester.pump();
    expect(shownIds(tester), [2]);
  });

  testWidgets('删回 @ 后为空 → 回 E1 快捷列表，且不再发 search', (tester) async {
    final repo = _FakeRepo([_c(1, 'Aurel'), _c(2, 'Budi')]);
    final keyword = await pumpPicker(tester, repo);
    keyword.value = 'bu';
    await tester.pumpAndSettle();
    expect(shownIds(tester), [2]);
    keyword.value = '';
    await tester.pump();
    expect(shownIds(tester), [1, 2]);
    await tester.pump(const Duration(seconds: 1));
    expect(repo.searched, ['bu']);
  });

  testWidgets('防抖中面板被收起 → 计时器随 dispose 取消，不再发请求', (tester) async {
    final repo = _FakeRepo([_c(1, 'Aurel')]);
    final keyword = await pumpPicker(tester, repo);
    keyword.value = 'au';
    await tester.pump(const Duration(milliseconds: 100));
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 1));
    expect(repo.searched, isEmpty);
  });

  // ===== 范围红线 =====

  testWidgets('🔴 不许出现放大镜，旧过滤占位文案也不许承诺"搜索用户"', (tester) async {
    await pumpPicker(tester, _FakeRepo([_c(1, 'Aurel')]));
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    // 过滤框已移除：旧占位不再出现在面板里（ARB 键保留待清理）。
    expect(find.text(l10n.mentionFilterHint), findsNothing);
    for (final forbidden in <String>['search', 'Search', 'cari user', 'semua pengguna']) {
      expect(l10n.mentionFilterHint.contains(forbidden), isFalse,
          reason: '文案不得暗示能搜到任何用户：$forbidden');
    }
    // 放大镜图标本身就是"能搜到任何人"的视觉承诺。
    expect(find.byIcon(Icons.search), findsNothing);
    expect(find.byIcon(Icons.search_rounded), findsNothing);
  });

  // 复审 CONFIRMED：转圈外层 Center 不设 heightFactor 会被 Flexible 撑满 220。
  for (final bool docked in <bool>[true, false]) {
    testWidgets('E2：请求挂起时面板只占一行高（约 42，远小于 220）· docked=$docked', (tester) async {
      final repo = _FakeRepo(const [], onSearch: (_) => Completer<List<MentionCandidate>>().future);
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
            body: Align(
              alignment: Alignment.bottomCenter,
              child: MentionPicker(keyword: 'ab', docked: docked, onSelected: (_) {}),
            ),
          ),
        ),
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(repo.searched, ['ab']);
      expect(find.byKey(const ValueKey('mentionPickerLoading')), findsOneWidget);
      final double h = tester.getSize(find.byKey(const ValueKey('mentionPicker'))).height;
      // docked：12+18+12 + 上沿 1px；非 docked：+ 上下 xs 内边距与四周 1px 描边。
      expect(h, inInclusiveRange(40, 60));
      expect(h, lessThan(100));
    });
  }

  // ===== 外观参数 =====

  testWidgets('rowHorizontalPadding 生效；docked 无圆角无四周描边（外壳 = KeyboardSuggestPanel）', (tester) async {
    final container = ProviderContainer(overrides: [
      mentionCandidateRepositoryProvider.overrideWithValue(_FakeRepo([_c(1, 'Aurel')])),
    ]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: MentionPicker(keyword: '', docked: true, rowHorizontalPadding: 20, onSelected: (_) {}),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    final pad = tester.widget<Padding>(find
        .descendant(of: find.byKey(const ValueKey('mentionCandidate_1')), matching: find.byType(Padding))
        .first);
    expect((pad.padding as EdgeInsets).left, 20);
    expect((pad.padding as EdgeInsets).right, 20);
    final box = tester.widget<Container>(find
        .descendant(of: find.byKey(const ValueKey('mentionPicker')), matching: find.byType(Container))
        .first);
    final deco = box.decoration! as BoxDecoration;
    expect(deco.borderRadius, isNull);
    final border = deco.border! as Border;
    expect(border.top.width, 1);
    expect(border.left, BorderSide.none);
    expect(deco.boxShadow, isNotEmpty);
  });
}
