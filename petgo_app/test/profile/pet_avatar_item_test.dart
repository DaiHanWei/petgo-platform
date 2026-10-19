import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_avatar_item.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 圆形宠物头像（我的页 Hewanku / 他人主页 Hewan 共用）：「物种 · 年龄」单行（L2 第二轮按稿 A2 / J0 改，取代 2026-10-15 的两行）。
void main() {
  Future<void> pump(WidgetTester tester, Locale locale) async {
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: locale,
      home: Scaffold(
        body: Center(
          child: PetAvatarItem(
            name: 'doggy',
            avatarUrl: null,
            petType: 'DOG',
            // 1 岁 0 个月：印尼语「Anjing · 1th 0bln」在 84 宽单行放不下
            birthday: DateTime.now().subtract(const Duration(days: 370)),
            tailsonalityBadge: null,
            diaryCount: 8,
            onTap: () {},
            metaKey: const ValueKey('meta'),
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  // L2 第二轮（稿 A2 / J0）：副标题改为**单行**（原先允许两行，会把一排头像撑得高低不齐）；
  // L2 第三轮：放不下时整体缩小、不省略（省略会截掉年龄）。
  testWidgets('物种 · 年龄单行显示，放不下整体缩小不省略（稿 A2 / J0）', (tester) async {
    await pump(tester, const Locale('id'));
    final text = tester.widget<Text>(find.byKey(const ValueKey('meta')));
    expect(text.maxLines, 1);
    expect(text.overflow, isNot(TextOverflow.ellipsis));
    expect(text.data, startsWith('Anjing · '));
    expect(find.ancestor(of: find.byKey(const ValueKey('meta')), matching: find.byType(FittedBox)), findsOneWidget);
    final para = tester.renderObject<RenderParagraph>(find.byKey(const ValueKey('meta')));
    expect(para.size.height, lessThan(20), reason: '只占一行');
  });

  testWidgets('Diary 数为 0 → 不画「0」角标（L2 J0）', (tester) async {
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('id'),
      home: Scaffold(
        body: Center(
          child: PetAvatarItem(
            name: 'doggy',
            avatarUrl: null,
            petType: 'DOG',
            birthday: null,
            tailsonalityBadge: null,
            diaryCount: 0,
            onTap: () {},
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('0'), findsNothing);
  });
}
