import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_avatar_item.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 圆形宠物头像（我的页 Hewanku / 他人主页 Hewan 共用）：「物种 · 年龄」不被截断（验收 2026-10-15）。
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

  testWidgets('物种 · 年龄完整显示、不出省略号（en，排版实测）', (tester) async {
    await pump(tester, const Locale('en'));
    final para = tester.renderObject<RenderParagraph>(find.byKey(const ValueKey('meta')));
    expect(para.didExceedMaxLines, isFalse, reason: '「物种 · 年龄」被截断');
  });

  // 测试字体每个字按整字号宽画（「1th 0bln」8 字 = 88px），比真机 Poppins 宽一倍，无法做排版实测；
  // 只断言两条约束：允许两行、年龄内是不换行空格（只在「·」处折行）。
  testWidgets('物种 · 年龄允许两行且年龄不被拆开（id）', (tester) async {
    await pump(tester, const Locale('id'));
    final text = tester.widget<Text>(find.byKey(const ValueKey('meta')));
    expect(text.maxLines, 2);
    final age = text.data!.split(' · ').last;
    expect(age, contains('\u00A0'));
    expect(age.contains(' '), isFalse, reason: '年龄内的空格应为不换行空格');
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
