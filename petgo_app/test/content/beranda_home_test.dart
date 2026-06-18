import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/presentation/feed_tab_row.dart';
import 'package:tailtopia/features/content/presentation/home_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Beranda 首页推倒重做回归（原型 feed.html）：AppBar「TailTopia 🐾」+ 分类 Chips 恒渲染；
/// Momo 问候头 / 快捷入口 / 每日卡 / 「Untukmu」区头已移除（决策 #6）。
///
/// 注：测试环境 feed 走真网→error 态，验证「头部在非 data 态也恒在」（Tab/提示条可达）。
LoginResponse _user() => const LoginResponse(
      accessToken: 'a',
      refreshToken: 'r',
      role: 'USER',
      isNewUser: false,
      onboardingCompleted: true,
      profile: UserProfile(
          nickname: 'Aurel', petStatus: 'PLANNING', hasPetProfile: true, onboardingCompleted: true),
    );

Future<void> _pumpHome(WidgetTester tester, ProviderContainer c) async {
  await tester.pumpWidget(UncontrolledProviderScope(
    container: c,
    child: const MaterialApp(
      locale: Locale('id'), // 文案已迁 arb：固定印尼语以断言 'Apa kabar, ...' 等 id 文案
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: HomePage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('Beranda：AppBar「TailTopia 🐾」+ 分类 Chips 恒渲染；旧问候头/快捷入口/每日卡/区头已移除', (tester) async {
    final c = ProviderContainer();
    addTearDown(c.dispose);
    c.read(authControllerProvider.notifier).applyLogin(_user());

    await _pumpHome(tester, c);

    // AppBar 标题 + 分类 Chips 恒在。
    expect(find.text('TailTopia 🐾'), findsOneWidget);
    expect(find.byType(FeedTabRow), findsOneWidget);
    // 推倒重做：旧 Momo 问候头 / 快捷入口 / 每日卡 / 「Untukmu」区头不再渲染。
    expect(find.text('Apa kabar, Aurel?'), findsNothing);
    expect(find.text('Konsultasi Kilat'), findsNothing);
    expect(find.text('Catat momen hari ini'), findsNothing);
    expect(find.text('Untukmu'), findsNothing);
  });
}
