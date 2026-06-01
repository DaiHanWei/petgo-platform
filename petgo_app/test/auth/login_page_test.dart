import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/auth/presentation/login_page.dart';
import 'package:petgo/l10n/app_localizations.dart';

Future<void> _pump(WidgetTester tester, Locale locale) async {
  await tester.pumpWidget(ProviderScope(
    child: MaterialApp(
      locale: locale,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: const LoginPage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('AC2: Google 按钮 + 两个 Text Link + 无勾选框（en）', (tester) async {
    await _pump(tester, const Locale('en'));

    expect(find.byKey(const ValueKey('googleLoginButton')), findsOneWidget);
    expect(find.text('Sign in with Google'), findsOneWidget);
    // 两份可点链接
    expect(find.byKey(const ValueKey('termsLink')), findsOneWidget);
    expect(find.byKey(const ValueKey('privacyLink')), findsOneWidget);
    expect(find.text('Terms of Service'), findsOneWidget);
    expect(find.text('Privacy Policy'), findsOneWidget);
    // FR-0D：无勾选框
    expect(find.byType(Checkbox), findsNothing);
  });

  testWidgets('AC2: 文案随 id 切换', (tester) async {
    await _pump(tester, const Locale('id'));
    expect(find.text('Masuk dengan Google'), findsOneWidget);
    expect(find.text('Ketentuan Layanan'), findsOneWidget);
    expect(find.text('Kebijakan Privasi'), findsOneWidget);
  });
}
