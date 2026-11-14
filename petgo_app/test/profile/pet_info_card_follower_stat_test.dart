import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/profile/domain/pet_header_info.dart';
import 'package:tailtopia/features/profile/presentation/widgets/diary_header.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_info_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：统计条第 4 格「N Pengikut」（V1.4.2 social-topic · Story 1.4 · AC2 · UI 稿 A1 / A4）。
///
/// 结构断言（不写 golden）：几格、内边距、数字格式、等宽数字、礼物格 FittedBox、bottomAction 插槽。
void main() {
  Future<void> pump(WidgetTester tester,
      {int? followerCount, Widget? bottomAction, String locale = 'id'}) async {
    await tester.pumpWidget(MaterialApp(
      locale: Locale(locale),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: PetInfoCard(
          profile: const PetHeaderInfo(name: 'Mochi', petType: 'CAT'),
          happyCount: 12,
          giftTotalValue: 53000,
          milestoneCount: 6,
          followerCount: followerCount,
          bottomAction: bottomAction,
        ),
      ),
    ));
    await tester.pump();
  }

  /// 统计条 Row 里的格（Expanded）。
  Finder cells() => find.descendant(
      of: find.byKey(const ValueKey('petInfoCard')),
      matching: find.byWidgetPredicate((w) => w is Expanded && w.child is! Column && w.flex == 1));

  EdgeInsets cellPadding(WidgetTester tester, Finder cell) => tester
      .widgetList<Padding>(find.descendant(of: cell, matching: find.byType(Padding)))
      .map((p) => p.padding as EdgeInsets)
      .firstWhere((p) => p.top == 6);

  testWidgets('followerCount null → 2 格（V1.5.0 去掉 Hadiah）、内边距 8', (tester) async {
    await pump(tester);
    expect(find.byKey(const ValueKey('petInfoFollowerStat')), findsNothing);
    expect(find.text('Pengikut'), findsNothing);
    final happy = find.ancestor(of: find.text('12'), matching: find.byType(Expanded)).first;
    expect(cellPadding(tester, happy), const EdgeInsets.symmetric(horizontal: 8, vertical: 6));
    expect(find.byKey(const ValueKey('petInfoGiftStat')), findsNothing);
  });

  testWidgets('followerCount 128 → 3 格（V1.5.0 去掉 Hadiah）、每格内边距 4、数字 16/w700 ink + tabular、标签 Pengikut、不可点',
      (tester) async {
    await pump(tester, followerCount: 128);
    final cell = find.byKey(const ValueKey('petInfoFollowerStat'));
    expect(cell, findsOneWidget);
    expect(cells(), findsNWidgets(3));
    final number = tester.widget<Text>(find.byKey(const ValueKey('petInfoFollowerCount')));
    expect(number.data, '128');
    expect(number.style!.fontSize, 16);
    expect(number.style!.fontWeight, FontWeight.w700);
    expect(number.style!.color, AppColors.ink);
    expect(number.style!.fontFeatures, contains(const FontFeature.tabularFigures()));
    expect(find.text('Pengikut'), findsOneWidget);
    expect(find.descendant(of: cell, matching: find.byType(InkWell)), findsNothing);
    expect(find.descendant(of: cell, matching: find.byType(GestureDetector)), findsNothing);
    expect(cellPadding(tester, cell), const EdgeInsets.symmetric(horizontal: 4, vertical: 6));
    final happy = find.ancestor(of: find.text('12'), matching: find.byType(Expanded)).first;
    expect(cellPadding(tester, happy), const EdgeInsets.symmetric(horizontal: 4, vertical: 6));
    expect(find.byKey(const ValueKey('petInfoGiftStat')), findsNothing);
  });

  testWidgets('计数格式同 1.3.4：9999 → 9,9K（id）/ 9.9K（en）', (tester) async {
    await pump(tester, followerCount: 9999);
    expect(tester.widget<Text>(find.byKey(const ValueKey('petInfoFollowerCount'))).data, '9,9K');
    await pump(tester, followerCount: 9999, locale: 'en');
    expect(tester.widget<Text>(find.byKey(const ValueKey('petInfoFollowerCount'))).data, '9.9K');
    expect(find.text('Followers'), findsOneWidget);
  });

  testWidgets('bottomAction 放在统计条下方，上距 12', (tester) async {
    await pump(tester,
        followerCount: 0,
        bottomAction: const SizedBox(key: ValueKey('cardBottom'), height: 40, width: double.infinity));
    final statsBottom = tester.getBottomLeft(find.byKey(const ValueKey('petInfoFollowerStat'))).dy;
    final actionTop = tester.getTopLeft(find.byKey(const ValueKey('cardBottom'))).dy;
    // 统计条容器有 vertical 2 内边距：格底 + 2 = 统计条底。
    expect(actionTop - (statsBottom + 2), closeTo(12, 0.01));
  });

  /// A4：主人 Diary Tab 的 [DiaryHeader] 透传 followerCount → 第 4 格；只人数、无按钮；不传 leading → 标题行不变。
  testWidgets('DiaryHeader 透传 followerCount（A4：只人数、无按钮），不传 leading 时标题行首个子项仍是标题', (tester) async {
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: const Scaffold(
        body: SingleChildScrollView(
          child: DiaryHeader(
            profile: PetHeaderInfo(name: 'Mochi', petType: 'CAT'),
            happyCount: 1,
            giftTotalValue: 0,
            milestoneCompleted: 1,
            milestoneTotal: 30,
            followerCount: 7,
          ),
        ),
      ),
    ));
    await tester.pump();
    expect(find.byKey(const ValueKey('petInfoFollowerStat')), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(const ValueKey('petInfoFollowerCount'))).data, '7');
    expect(find.byKey(const ValueKey('petFollowButton')), findsNothing);
    expect(find.byKey(const ValueKey('visitorBackButton')), findsNothing);
  });
}
