import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/notify/domain/app_version_check.dart';
import 'package:petgo/features/notify/presentation/app_update_dialog.dart';
import 'package:petgo/l10n/app_localizations.dart';

/// Story 6.5 F2/F3：推荐弹窗有「稍后」可关；强制弹窗无「稍后」、不可跳过。
Future<void> _pump(WidgetTester tester, UpdateDecision decision) async {
  await tester.pumpWidget(MaterialApp(
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    locale: const Locale('en'),
    home: Builder(
      builder: (context) => Scaffold(
        body: Center(
          child: ElevatedButton(
            onPressed: () => AppUpdateDialog.show(context, decision, () {}),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('推荐更新：有「稍后」+「前往更新」', (tester) async {
    await _pump(tester, UpdateDecision.recommended);
    expect(find.byKey(const ValueKey('appUpdateRecommended')), findsOneWidget);
    expect(find.byKey(const ValueKey('appUpdateLater')), findsOneWidget);
    expect(find.byKey(const ValueKey('appUpdateGoStore')), findsOneWidget);
  });

  testWidgets('强制更新：无「稍后」，只有「前往更新」', (tester) async {
    await _pump(tester, UpdateDecision.forced);
    expect(find.byKey(const ValueKey('appUpdateForced')), findsOneWidget);
    expect(find.byKey(const ValueKey('appUpdateLater')), findsNothing);
    expect(find.byKey(const ValueKey('appUpdateGoStore')), findsOneWidget);
  });
}
