import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/me/presentation/settings_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 7.1 · F8：二级设置页含语言/退出/注销三项入口（PDP 数据主体权利可达）。
void main() {
  testWidgets('设置页含语言 + 退出登录 + 账号注销入口', (tester) async {
    // 设置页分四组较长，用高视口确保 ListView 全量构建（底部 ZONA BAHAYA 不超出 fold）。
    await tester.binding.setSurfaceSize(const Size(440, 1400));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(const ProviderScope(
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: Locale('en'),
        home: SettingsPage(),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('meLanguage')), findsOneWidget);
    expect(find.byKey(const ValueKey('meLogout')), findsOneWidget); // 退出登录入口（7.3）
    expect(find.byKey(const ValueKey('meDeleteAccount')), findsOneWidget); // 账号注销入口（PDP 可达）
  });
}
