import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_providers.dart';
import 'package:tailtopia/features/tailsonality/domain/tailsonality_result.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_result_page.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_generating_view.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.2 Story 2.3 · AC6 结果页最小骨架 + AC5.2「减少动态效果」静态兜底。
void main() {
  Widget wrap(Widget child, {List overrides = const [], bool reduceMotion = false}) => ProviderScope(
        overrides: [...overrides],
        child: MaterialApp(
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: MediaQuery(
            data: MediaQueryData(disableAnimations: reduceMotion),
            child: Scaffold(body: child),
          ),
        ),
      );

  testWidgets('结果页按 token 显示完整代号 + 角色名', (tester) async {
    await tester.pumpWidget(wrap(const TailsonalityResultPage(token: 'abc'), overrides: [
      tailsonalityResultProvider('abc').overrideWith((ref) async => TailsonalityResult(
            token: 'abc',
            typeCode: 'ISFP-L',
            letters: 'ISFP',
            energy: 'L',
            questionSet: 'CAT',
            resultIndex: 1,
            unlocked: false,
            contentVersion: 1,
            createdAt: DateTime.utc(2026, 9, 30),
          )),
    ]));
    await tester.pumpAndSettle();
    expect(find.text('ISFP-L'), findsOneWidget);
    expect(find.text('Sus Radar 24/7'), findsOneWidget);
  });

  testWidgets('生成中：减少动态效果 → 首帧即五条全亮', (tester) async {
    await tester.pumpWidget(wrap(const TsGeneratingView(petName: 'Momo'), reduceMotion: true));
    await tester.pump();
    for (var i = 0; i < 5; i++) {
      final s = tester.getSemantics(find.byKey(ValueKey('tsGeneratingAxis_$i')));
      expect(s.value, '100%', reason: 'axis $i');
    }
    expect(find.text('E / I · Orientasi sosial'), findsOneWidget);
    expect(find.text('Tingkat energi'), findsOneWidget);
  });

  testWidgets('生成中：正常动效逐条点亮（约 450ms 一条）', (tester) async {
    await tester.pumpWidget(wrap(const TsGeneratingView(petName: 'Momo')));
    await tester.pump();
    String v(int i) => tester.getSemantics(find.byKey(ValueKey('tsGeneratingAxis_$i'))).value;
    expect(v(0), '0%');
    await tester.pump(const Duration(milliseconds: 500));
    expect(v(0), '100%');
    expect(v(1), '0%');
    await tester.pumpAndSettle();
    expect(v(4), '100%');
  });
}
