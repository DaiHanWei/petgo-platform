import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/data/mini_profile_repository.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/mini_profile_sheet.dart';

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

  // ===== 个性签名（2026-08-07 用户反馈：设了签名的用户，别人点这张卡应当看得到）=====

  testWidgets('设了签名 → 卡片展示签名，并取代「主页筹备中」占位', (tester) async {
    await _pump(tester, const MiniProfile(
        postCount: 2, isDeactivated: false, nickname: 'Liu Xi', signature: '爱猫的人运气都不会太差'));
    await tester.tap(find.byKey(const ValueKey('openMini')));
    await tester.pumpAndSettle();

    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text('爱猫的人运气都不会太差'), findsOneWidget);
    // 占位与签名二选一：有签名时那句「主页筹备中」既多余又自相矛盾。
    expect(find.text(l10n.miniProfileComingSoon), findsNothing);
  });

  testWidgets('签名为空串/纯空白 → 按没设置处理，回落占位文案（卡片不留空白）', (tester) async {
    for (final sig in <String?>[null, '', '   ']) {
      await _pump(tester, MiniProfile(
          postCount: 1, isDeactivated: false, nickname: 'Liu Xi', signature: sig));
      await tester.tap(find.byKey(const ValueKey('openMini')));
      await tester.pumpAndSettle();

      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.miniProfileComingSoon), findsOneWidget,
          reason: 'signature=${sig == null ? 'null' : '"$sig"'} 时应回落占位');
      expect(find.byKey(const ValueKey('miniProfileSignature')), findsNothing);

      await tester.tap(find.byKey(const ValueKey('miniProfileClose')));
      await tester.pumpAndSettle();
    }
  });

  /// 🐛 回归（2026-08-07 实机反馈：卡片只有半屏宽）
  ///
  /// 根因不在本文件的布局，而在 Material 的 BottomSheet：M3 默认 `constraints`
  /// （maxWidth 640）**非空** ⇒ `BottomSheet.build` 恒把内容包进 `Align`，而 Align 会
  /// **放松**宽度约束再传下去。约束一松，卡片就按固有宽度收缩成「最宽子元素的宽度」——
  /// 也就是那行签名的文字宽度。签名越短，卡越窄。
  ///
  /// 所以断言必须盯**渲染出来的宽度**，不能只断言「有没有那个 SizedBox」：
  /// 后者在别人把外层结构挪动一层时照样绿，而卡片已经缩回去了。
  testWidgets('🐛 回归：卡片与屏幕等宽（签名长短都不得改变卡片宽度）', (tester) async {
    // ⚠️ 必须用**手机尺寸**测，不能用默认视口：flutter_test 默认视口是 800×600 逻辑像素，
    // 宽度超过 M3 BottomSheet 的 `maxWidth: 640` ⇒ 即使修好了也永远量到 640 ≠ 800，
    // 断言无法成立。这里取本次实机复现的机型（1080×2400 @420dpi = 411.4dp 宽）。
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 2.625;
    addTearDown(tester.view.reset);
    final screenWidth = tester.view.physicalSize.width / tester.view.devicePixelRatio;

    // 短签名与长签名各测一次：若哪天又按内容收缩，短签名那次会明显更窄。
    for (final sig in <String>['Hi', 'Cat people are lucky people']) {
      await _pump(tester, MiniProfile(
          postCount: 2, isDeactivated: false, nickname: 'Liu Xi', signature: sig));
      await tester.tap(find.byKey(const ValueKey('openMini')));
      await tester.pumpAndSettle();

      final sheetWidth = tester.getSize(find.descendant(
        of: find.byType(BottomSheet),
        matching: find.byType(SingleChildScrollView),
      )).width;
      expect(sheetWidth, screenWidth,
          reason: 'signature="$sig" 时卡片宽 $sheetWidth，应为屏宽 $screenWidth —— '
              '外层没撑满宽度，卡片又按文字宽度收缩了');

      await tester.tap(find.byKey(const ValueKey('miniProfileClose')));
      await tester.pumpAndSettle();
    }
  });
}
