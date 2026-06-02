import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/content/data/mini_profile_repository.dart';
import 'package:petgo/l10n/app_localizations.dart';
import 'package:petgo/shared/widgets/mini_profile_sheet.dart';

class _FakeMiniRepo implements MiniProfileRepository {
  _FakeMiniRepo(this.profile);
  final MiniProfile profile;
  @override
  Future<MiniProfile> getMiniProfile(int userId) async => profile;
}

Future<void> _pump(WidgetTester tester, MiniProfile profile) async {
  final container = ProviderContainer(overrides: [
    miniProfileRepositoryProvider.overrideWithValue(_FakeMiniRepo(profile)),
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
              onPressed: () => showMiniProfile(context, ref, 7),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

// 措辞克制：禁用技术性表达（UX-DR14）。
const _bannedWords = [
  'coming soon', 'not available', 'under construction',
  '功能开发中', '敬请期待', '暂不支持',
];

void main() {
  testWidgets('AC1: 弹卡含昵称/发布数/筹备中文案/关闭，无关注·查看主页按钮', (tester) async {
    await _pump(tester, const MiniProfile(
        postCount: 3, isDeactivated: false, nickname: 'Alice', avatarUrl: null));
    await tester.tap(find.byKey(const ValueKey('openMini')));
    await tester.pumpAndSettle();

    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text('Alice'), findsOneWidget);
    expect(find.text(l10n.miniProfilePostCount(3)), findsOneWidget);
    expect(find.text(l10n.miniProfileComingSoon), findsOneWidget);
    expect(find.byKey(const ValueKey('miniProfileClose')), findsOneWidget);
    // 无「关注」「查看主页」按钮
    expect(find.text('Follow'), findsNothing);
    expect(find.text('View profile'), findsNothing);
  });

  testWidgets('AC1: 措辞克制——双语文案不含技术性禁用词', (tester) async {
    final en = await AppLocalizations.delegate.load(const Locale('en'));
    final id = await AppLocalizations.delegate.load(const Locale('id'));
    for (final text in [en.miniProfileComingSoon, id.miniProfileComingSoon]) {
      final lower = text.toLowerCase();
      for (final banned in _bannedWords) {
        expect(lower.contains(banned.toLowerCase()), isFalse,
            reason: '迷你主页文案不得含技术性表达: "$banned" in "$text"');
      }
    }
  });

  testWidgets('AC2: 已注销用户 → 不弹迷你卡', (tester) async {
    await _pump(tester, const MiniProfile(postCount: 0, isDeactivated: true));
    await tester.tap(find.byKey(const ValueKey('openMini')));
    await tester.pumpAndSettle();
    // 无 sheet：关闭按钮不存在
    expect(find.byKey(const ValueKey('miniProfileClose')), findsNothing);
  });
}
