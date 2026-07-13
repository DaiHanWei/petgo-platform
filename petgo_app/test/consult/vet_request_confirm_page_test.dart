import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/consult/presentation/vet_request_confirm_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0 widget。Story 3.5 确认发起屏：单价 + 说明 + 发起按钮渲染（无 timer，安全）。
void main() {
  testWidgets('renders price, description and start CTA', (tester) async {
    tester.platformDispatcher.localesTestValue = const [Locale('id')];
    final container = ProviderContainer();
    addTearDown(container.dispose);

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: VetRequestConfirmPage(),
      ),
    ));
    await tester.pump();

    expect(find.byKey(const ValueKey('vetRequestDesc')), findsOneWidget);
    expect(find.byKey(const ValueKey('vetRequestPrice')), findsOneWidget);
    expect(find.text('Rp50.000'), findsOneWidget); // 单价手工千分位
    expect(find.byKey(const ValueKey('vetRequestStart')), findsOneWidget);
  });
}
