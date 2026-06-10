import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/l10n/locale_controller.dart';
import 'package:tailtopia/features/me/presentation/language_settings_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Story 7.2 AC1/AC2：回退逻辑 + 手动切换即时生效。
void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  test('localeOverride 解析：id/en 锁定，其它/空 → 跟随设备(null)', () {
    final c = ProviderContainer(overrides: [localeOverrideProvider.overrideWithValue('id')]);
    addTearDown(c.dispose);
    expect(c.read(localeControllerProvider), const Locale('id'));

    final c2 = ProviderContainer(overrides: [localeOverrideProvider.overrideWithValue(null)]);
    addTearDown(c2.dispose);
    expect(c2.read(localeControllerProvider), isNull); // 跟随设备
  });

  testWidgets('AC2：手动切到印尼语即时生效（localeController 更新）', (tester) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: Locale('en'),
        home: LanguageSettingsPage(),
      ),
    ));
    await tester.pumpAndSettle();

    expect(container.read(localeControllerProvider), isNull); // 初始跟随系统
    await tester.tap(find.byKey(const ValueKey('langId')));
    await tester.pumpAndSettle();
    expect(container.read(localeControllerProvider), const Locale('id'));

    await tester.tap(find.byKey(const ValueKey('langEn')));
    await tester.pumpAndSettle();
    expect(container.read(localeControllerProvider), const Locale('en'));
  });
}
