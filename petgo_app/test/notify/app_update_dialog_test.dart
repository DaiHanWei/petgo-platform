import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/startup/startup_popup_ledger.dart';
import 'package:tailtopia/features/notify/domain/app_version_check.dart';
import 'package:tailtopia/features/notify/presentation/app_update_dialog.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 6.5 F2/F3 → V1.3.4 Story 3.2 · AC7 重做：推荐弹窗有「Nanti」可关；
/// 强制弹窗无「Nanti」、遮罩 / 返回键都关不掉，点主按钮后仍在。
Future<void> _pump(
  WidgetTester tester,
  UpdateDecision decision, {
  VoidCallback? onGoStore,
  VoidCallback? onLater,
  bool withStore = true,
  Locale locale = const Locale('en'),
}) async {
  await tester.pumpWidget(MaterialApp(
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    locale: locale,
    home: Builder(
      builder: (context) => Scaffold(
        body: Center(
          child: ElevatedButton(
            onPressed: () => AppUpdateDialog.show(
              context,
              decision: decision,
              version: '1.3.4',
              onGoStore: withStore ? (onGoStore ?? () {}) : null,
              onLater: onLater,
            ),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

const _forced = ValueKey('appUpdateForced');
const _recommended = ValueKey('appUpdateRecommended');
const _goStore = ValueKey('appUpdateGoStore');
const _later = ValueKey('appUpdateLater');

void main() {
  setUp(StartupPopupLedger.instance.resetForTest);

  testWidgets('V1.3.4 Story 5.3：直接调 AppUpdateDialog.show 推荐态 → ledger 为空（推荐态不算冷启动功能弹窗）',
      (tester) async {
    await _pump(tester, UpdateDecision.recommended);
    expect(find.byKey(_recommended), findsOneWidget);
    expect(StartupPopupLedger.instance.hasAny, isFalse);
  });

  testWidgets('推荐更新：有「Nanti」+ 主按钮；点「Nanti」关窗并回调 onLater', (tester) async {
    var later = 0;
    await _pump(tester, UpdateDecision.recommended, onLater: () => later++);
    expect(find.byKey(_recommended), findsOneWidget);
    expect(find.byKey(_later), findsOneWidget);
    expect(find.byKey(_goStore), findsOneWidget);

    await tester.tap(find.byKey(_later));
    await tester.pumpAndSettle();
    expect(find.byKey(_recommended), findsNothing);
    expect(later, 1);
  });

  testWidgets('推荐更新：点遮罩可关，但不回调 onLater（不记冷却）', (tester) async {
    var later = 0;
    await _pump(tester, UpdateDecision.recommended, onLater: () => later++);
    await tester.tapAt(const Offset(4, 4));
    await tester.pumpAndSettle();
    expect(find.byKey(_recommended), findsNothing);
    expect(later, 0);
  });

  testWidgets('强制更新：无「Nanti」，只有主按钮', (tester) async {
    await _pump(tester, UpdateDecision.forced);
    expect(find.byKey(_forced), findsOneWidget);
    expect(find.byKey(_later), findsNothing);
    expect(find.byKey(_goStore), findsOneWidget);
  });

  testWidgets('强制更新：点遮罩、系统返回键都关不掉', (tester) async {
    await _pump(tester, UpdateDecision.forced);
    await tester.tapAt(const Offset(4, 4));
    await tester.pumpAndSettle();
    expect(find.byKey(_forced), findsOneWidget, reason: '点遮罩不得关闭强制弹窗');

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.byKey(_forced), findsOneWidget, reason: '系统返回键不得关闭强制弹窗');
  });

  testWidgets('强制更新：点主按钮回调被调用，弹窗仍在', (tester) async {
    var go = 0;
    await _pump(tester, UpdateDecision.forced, onGoStore: () => go++);
    await tester.tap(find.byKey(_goStore));
    await tester.pumpAndSettle();
    expect(go, 1);
    expect(find.byKey(_forced), findsOneWidget);
  });

  testWidgets('onGoStore 为 null（商店链接为空）→ 不渲染主按钮', (tester) async {
    await _pump(tester, UpdateDecision.forced, withStore: false);
    expect(find.byKey(_forced), findsOneWidget);
    expect(find.byKey(_goStore), findsNothing);
    expect(find.byKey(_later), findsNothing, reason: '强制态此时卡片无任何按钮');
  });

  testWidgets('版本标签：en = Version 1.3.4', (tester) async {
    await _pump(tester, UpdateDecision.forced);
    final label = find.byKey(const ValueKey('appUpdateVersionLabel'));
    expect(find.descendant(of: label, matching: find.text('Version 1.3.4')), findsOneWidget);
  });

  testWidgets('版本标签：id = Versi 1.3.4；主按钮 Perbarui sekarang', (tester) async {
    await _pump(tester, UpdateDecision.recommended, locale: const Locale('id'));
    final label = find.byKey(const ValueKey('appUpdateVersionLabel'));
    expect(find.descendant(of: label, matching: find.text('Versi 1.3.4')), findsOneWidget);
    expect(find.descendant(of: find.byKey(_goStore), matching: find.text('Perbarui sekarang')),
        findsOneWidget);
    expect(find.descendant(of: find.byKey(_later), matching: find.text('Nanti')), findsOneWidget);
  });

  testWidgets('结构：卡片圆角 24、宽 ≤312；图标块 64×64', (tester) async {
    await _pump(tester, UpdateDecision.forced);
    final card = tester.widget<Container>(find.byKey(_forced));
    final deco = card.decoration! as BoxDecoration;
    expect(deco.borderRadius, BorderRadius.circular(24));
    expect(tester.getSize(find.byKey(_forced)).width, lessThanOrEqualTo(312));

    expect(tester.getSize(find.byKey(const ValueKey('appUpdateIcon'))), const Size(64, 64));
    final icon = tester.widget<Container>(find.byKey(const ValueKey('appUpdateIcon')));
    final iconDeco = icon.decoration! as BoxDecoration;
    expect(iconDeco.borderRadius, BorderRadius.circular(20));
    expect(iconDeco.gradient, isA<LinearGradient>());
  });
}
