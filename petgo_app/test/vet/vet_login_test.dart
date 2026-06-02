import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/auth/presentation/login_page.dart';
import 'package:petgo/features/vet/presentation/vet_login_page.dart';
import 'package:petgo/l10n/app_localizations.dart';

Future<void> _pump(WidgetTester tester, Widget home, Locale locale) async {
  await tester.pumpWidget(ProviderScope(
    child: MaterialApp(
      locale: locale,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: home,
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('F1: 登录页 Google 按钮下方有「兽医登录」小字入口（en）', (tester) async {
    await _pump(tester, const LoginPage(), const Locale('en'));
    expect(find.byKey(const ValueKey('vetLoginLink')), findsOneWidget);
    expect(find.text('Vet sign-in'), findsOneWidget);
  });

  testWidgets('F1/F3: 兽医登录页有账密表单 + 登录按钮，且无「忘记密码」入口', (tester) async {
    await _pump(tester, const VetLoginPage(), const Locale('en'));
    expect(find.byKey(const ValueKey('vetUsernameField')), findsOneWidget);
    expect(find.byKey(const ValueKey('vetPasswordField')), findsOneWidget);
    expect(find.byKey(const ValueKey('vetLoginButton')), findsOneWidget);
    // 无自助「忘记密码」流程，仅联系运营提示。
    expect(find.textContaining('Forgot'), findsOneWidget);
    expect(find.textContaining('contact operations'), findsOneWidget);
  });

  testWidgets('文案随 id 切换', (tester) async {
    await _pump(tester, const VetLoginPage(), const Locale('id'));
    expect(find.text('Masuk'), findsOneWidget);
    expect(find.text('Akun'), findsOneWidget);
  });
}
