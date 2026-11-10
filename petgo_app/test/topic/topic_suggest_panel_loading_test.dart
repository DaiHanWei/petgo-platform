import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/topic/presentation/widgets/topic_suggest_panel.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：# 联想面板加载态只占一行高（V1.4.2 social-topic · Story 4.4 E2；5.2 复审顺带修复）。
///
/// 回归点：转圈外层 `Center` 不设 `heightFactor` 时，被 [KeyboardSuggestPanel] 的 `Flexible` 包住会撑满上限 220。
void main() {
  testWidgets('加载中：面板约 42 高（12 + 18 + 12 + 上沿 1px），远小于 220', (tester) async {
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Align(
          alignment: Alignment.bottomCenter,
          child: TopicSuggestPanel(
            keyword: 'ku',
            loading: true,
            failed: false,
            suggestions: null,
            onSelect: (_) {},
            onCreate: (_) {},
            onRetry: () {},
          ),
        ),
      ),
    ));
    await tester.pump();
    expect(find.byKey(const ValueKey('topicSuggestLoading')), findsOneWidget);
    final double h = tester.getSize(find.byKey(const ValueKey('topicSuggestPanel'))).height;
    expect(h, closeTo(43, 2));
    expect(h, lessThan(100));
  });
}
