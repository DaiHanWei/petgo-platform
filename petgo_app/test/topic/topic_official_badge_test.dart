import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/topic/presentation/widgets/topic_official_badge.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：Resmi 徽章取 token（V1.4.2 social-topic · Story 4.3 · AC5，防回归）。
void main() {
  Future<void> pump(WidgetTester tester, Locale locale) => tester.pumpWidget(MaterialApp(
        locale: locale,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: const Scaffold(body: Center(child: TopicOfficialBadge())),
      ));

  testWidgets('violet100 底 · 圆角 4 · 内边距 2/4 · verified 12 mint600 · 10/1.0 w600 mint600', (tester) async {
    await pump(tester, const Locale('id'));
    final box = tester.widget<Container>(find.byKey(const ValueKey('topicOfficialBadge')));
    final deco = box.decoration! as BoxDecoration;
    expect(deco.color, AppColors.violet100);
    expect(deco.borderRadius, BorderRadius.circular(4));
    expect(box.padding, const EdgeInsets.symmetric(vertical: 2, horizontal: 4));

    final icon = tester.widget<Icon>(find.byIcon(Icons.verified_rounded));
    expect(icon.size, 12);
    expect(icon.color, AppColors.mint600);

    final text = tester.widget<Text>(find.text('Resmi'));
    expect(text.style!.fontSize, 10);
    expect(text.style!.height, 1.0);
    expect(text.style!.fontWeight, FontWeight.w600);
    expect(text.style!.color, AppColors.mint600);
  });

  testWidgets('英文 Official', (tester) async {
    await pump(tester, const Locale('en'));
    expect(find.text('Official'), findsOneWidget);
  });
}
