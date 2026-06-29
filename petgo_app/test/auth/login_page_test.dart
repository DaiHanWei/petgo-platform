import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/dio_client.dart';
import 'package:tailtopia/core/storage/secure_storage.dart';
import 'package:tailtopia/features/auth/data/auth_repository.dart';
import 'package:tailtopia/features/auth/data/google_auth_client.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/auth/presentation/login_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

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

/// Story 1.3 R2（F13）：注入式 AuthRepository，loginWithGoogle 抛指定异常以验失败/取消态。
class _NoopGoogleClient implements GoogleAuthClient {
  @override
  Future<String?> signInAndGetIdToken() async => null;
  @override
  Future<void> signOut() async {}
}

class _FakeAuthRepo extends AuthRepository {
  _FakeAuthRepo(this._run)
      : super(dio: Dio(), tokenStore: InMemoryTokenStore(), googleClient: _NoopGoogleClient());
  final Future<LoginResponse> Function() _run;
  @override
  Future<LoginResponse> loginWithGoogle() => _run();
}

Future<ProviderContainer> _pumpWith(
    WidgetTester tester, Future<LoginResponse> Function() run) async {
  final container = ProviderContainer(overrides: [
    authRepositoryProvider.overrideWithValue(_FakeAuthRepo(run)),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: const MaterialApp(
      locale: Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: LoginPage(),
    ),
  ));
  await tester.pumpAndSettle();
  return container;
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
    expect(find.text('Lanjutkan dengan Google'), findsOneWidget);
    expect(find.text('Syarat & Ketentuan'), findsOneWidget);
    expect(find.text('Kebijakan Privasi'), findsOneWidget);
  });

  testWidgets('FR-44: Apple 按钮仅 iOS 显示且置于 Google 之上', (tester) async {
    // 默认（非 iOS）→ 无 Apple 按钮，只有 Google。
    await _pump(tester, const Locale('id'));
    expect(find.byKey(const ValueKey('appleLoginButton')), findsNothing);
    expect(find.byKey(const ValueKey('googleLoginButton')), findsOneWidget);

    // iOS → Apple 显示，且在 Google 上方（App Store 4.8 同级置顶）。
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    try {
      await _pump(tester, const Locale('id'));
      expect(find.byKey(const ValueKey('appleLoginButton')), findsOneWidget);
      expect(find.text('Lanjutkan dengan Apple'), findsOneWidget);
      final appleDy = tester.getTopLeft(find.byKey(const ValueKey('appleLoginButton'))).dy;
      final googleDy = tester.getTopLeft(find.byKey(const ValueKey('googleLoginButton'))).dy;
      expect(appleDy, lessThan(googleDy));
    } finally {
      debugDefaultTargetPlatformOverride = null; // 体内重置，过 foundation 调试变量不变式校验
    }
  });

  testWidgets('AC5(R2/F13): 授权失败（网络/服务异常）→ 「登录失败，请重试」+ 不创建账号（仍游客）',
      (tester) async {
    final c = await _pumpWith(tester, () async => throw Exception('network error'));
    await tester.tap(find.byKey(const ValueKey('googleLoginButton')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('Sign-in failed, please try again'), findsOneWidget);
    // 不创建账号：登录态仍为游客；按钮恢复可点（=重试）
    expect(c.read(authControllerProvider).isLoggedIn, isFalse);
    final btn = tester.widget<FilledButton>(find.descendant(
      of: find.byKey(const ValueKey('googleLoginButton')),
      matching: find.byType(FilledButton),
    ));
    expect(btn.onPressed, isNotNull);
    await tester.pump(const Duration(seconds: 3)); // 走完 toast 自动消失定时器，避免 pending timer
  });

  testWidgets('AC5(R2/F13): 用户取消 → 取消提示 + 不创建账号（仍游客）', (tester) async {
    final c = await _pumpWith(tester, () async => throw const LoginCancelled());
    await tester.tap(find.byKey(const ValueKey('googleLoginButton')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('Sign-in cancelled'), findsOneWidget);
    expect(c.read(authControllerProvider).isLoggedIn, isFalse);
    await tester.pump(const Duration(seconds: 3)); // 走完 toast 定时器
  });
}
