import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/onboarding/presentation/splash_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 启动屏（P-01）回归：品牌渲染 + ~2.2s 后过场回调。
/// 注入 onComplete 以免依赖 GoRouter；含无限脉冲动画，用 pump(Duration) 而非 pumpAndSettle。
void main() {
  testWidgets('渲染字标/副标，~2.2s 后触发过场回调', (tester) async {
    var done = false;
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: SplashPage(onComplete: () => done = true),
    ));
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.text('TailTopia'), findsOneWidget);
    expect(find.text('Komunitas Pecinta Hewan Peliharaan Indonesia'), findsOneWidget);
    expect(find.text(SplashPage.version), findsOneWidget);
    expect(done, isFalse); // 过场尚未触发

    await tester.pump(const Duration(milliseconds: 2300)); // 越过 hold(2200ms)
    expect(done, isTrue); // 过场已触发

    // 收尾：替换为空 widget 触发 SplashPage.dispose，避免 _loop ticker 残留致 teardown flaky。
    await tester.pumpWidget(const SizedBox());
  });
}
