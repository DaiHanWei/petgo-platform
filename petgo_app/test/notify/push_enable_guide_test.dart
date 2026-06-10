import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/notify/presentation/push_enable_guide.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 6.4 F3：「我的」页推送开启引导渲染 + 「去设置」触发。
void main() {
  testWidgets('渲染引导 + 点「去设置」触发回调', (tester) async {
    var opened = false;
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
      home: Scaffold(
        body: PushEnableGuide(onOpenSettings: () async {
          opened = true;
          return true;
        }),
      ),
    ));
    expect(find.byKey(const ValueKey('pushEnableGuide')), findsOneWidget);
    expect(find.text('Enable notifications'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('pushOpenSettings')));
    await tester.pump();
    expect(opened, isTrue);
  });
}
