import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/auth/domain/auth_state.dart';
import 'package:petgo/features/auth/domain/login_response.dart';
import 'package:petgo/features/content/presentation/feed_tab_row.dart';
import 'package:petgo/features/content/presentation/home_page.dart';
import 'package:petgo/l10n/app_localizations.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Beranda 首页换肤回归（PetGo Prototype）：问候头 + 快捷入口 + 每日卡 + 区头恒渲染。
///
/// 注：测试环境 feed 走真网→error 态，验证「头部在非 data 态也恒在」（Tab/提示条/快捷入口可达）。
LoginResponse _user() => const LoginResponse(
      accessToken: 'a',
      refreshToken: 'r',
      role: 'USER',
      isNewUser: false,
      onboardingCompleted: true,
      profile: UserProfile(
          nickname: 'Aurel', petStatus: 'B', hasPetProfile: true, onboardingCompleted: true),
    );

Future<void> _pumpHome(WidgetTester tester, ProviderContainer c) async {
  await tester.pumpWidget(UncontrolledProviderScope(
    container: c,
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: HomePage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('Beranda：问候头 + 快捷入口 + 每日卡 + 区头 + 分类Tab 恒渲染', (tester) async {
    final c = ProviderContainer();
    addTearDown(c.dispose);
    c.read(authControllerProvider.notifier).applyLogin(_user());

    await _pumpHome(tester, c);

    // 问候头（昵称代入）。
    expect(find.text('Apa kabar, Aurel?'), findsOneWidget);
    // 三个快捷入口。
    expect(find.text('Konsultasi Kilat'), findsOneWidget);
    expect(find.text('Gabung Gath'), findsOneWidget);
    expect(find.text('Paspor'), findsOneWidget);
    // 每日记录提示卡 + 区头 + 分类 Tab。
    expect(find.text('Catat momen hari ini'), findsOneWidget);
    expect(find.text('Untukmu'), findsOneWidget);
    expect(find.byType(FeedTabRow), findsOneWidget);
  });
}
