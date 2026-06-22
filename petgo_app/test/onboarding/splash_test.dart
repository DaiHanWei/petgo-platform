import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/features/onboarding/presentation/splash_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 启动屏（P-01 品牌重塑）回归：标语/版本渲染 + 过场回调。
/// 注入 onComplete 以免依赖 GoRouter；含无限光晕/spinner 动画，用 pump(Duration) 而非 pumpAndSettle。
void main() {
  Future<void> pumpSplash(WidgetTester tester, {required VoidCallback onComplete}) async {
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: SplashPage(onComplete: onComplete),
    ));
    // 让 didChangeDependencies → 异步 prefs 决策 → setState(_decided) 落地。
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
  }

  testWidgets('当天首开：播完整动效，渲染标语/版本，~4.5s 后过场', (tester) async {
    SharedPreferences.setMockInitialValues({}); // 无记录 → 首开 → 动画
    var done = false;
    await pumpSplash(tester, onComplete: () => done = true);

    expect(find.textContaining('Komunitas Pecinta Hewan Peliharaan'), findsOneWidget);
    expect(find.text(SplashPage.version), findsOneWidget);
    expect(done, isFalse); // 动效进行中，过场未触发

    await tester.pump(const Duration(milliseconds: 4600)); // 越过 animatedHold(4500ms)
    expect(done, isTrue);

    await tester.pumpWidget(const SizedBox()); // 触发 dispose，避免 ticker 残留
  });

  testWidgets('当天已播过：静止终态，~1.4s 后过场（更快）', (tester) async {
    final n = DateTime.now();
    SharedPreferences.setMockInitialValues(
        {'petgo.splash_last_shown_date': '${n.year}-${n.month}-${n.day}'});
    var done = false;
    await pumpSplash(tester, onComplete: () => done = true);

    expect(find.text(SplashPage.version), findsOneWidget);
    expect(done, isFalse);

    await tester.pump(const Duration(milliseconds: 1500)); // 越过 staticHold(1400ms)
    expect(done, isTrue);

    await tester.pumpWidget(const SizedBox());
  });
}
