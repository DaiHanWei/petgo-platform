import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/consult/presentation/consult_rating_dialog.dart';
import 'package:petgo/l10n/app_localizations.dart';

/// Story 5.6 F2/F3：评分弹窗——星必选才可提交（1-5），≤100 字选填。
Future<void> _pump(WidgetTester tester) async {
  await tester.pumpWidget(MaterialApp(
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    locale: const Locale('en'),
    home: Builder(
      builder: (context) => Scaffold(
        body: Center(
          child: ElevatedButton(
            onPressed: () => ConsultRatingDialog.show(context),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('未选星级时提交按钮禁用（星必填）', (tester) async {
    await _pump(tester);
    final submit = find.byKey(const ValueKey('ratingSubmit'));
    expect(submit, findsOneWidget);
    expect(tester.widget<FilledButton>(submit).onPressed, isNull);
    expect(find.text('Please pick a star rating'), findsOneWidget);
  });

  testWidgets('选 5 星后可提交，返回 RatingResult', (tester) async {
    await _pump(tester);
    await tester.tap(find.byKey(const ValueKey('ratingStar_5')));
    await tester.pump();
    final submit = find.byKey(const ValueKey('ratingSubmit'));
    expect(tester.widget<FilledButton>(submit).onPressed, isNotNull);
  });
}
