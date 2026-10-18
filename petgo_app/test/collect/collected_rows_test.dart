import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/collect/domain/collected_item.dart';
import 'package:tailtopia/features/collect/presentation/collected_rows.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/post_grid_tile.dart';

import 'collect_fakes.dart';

/// V1.4.0 ④ Story 2.4 · AC2.2 / AC2.3 / AC3.2 / AC3.3：收藏列表行（正常 + 不可见两态）。
void main() {
  Future<AppLocalizations> pump(WidgetTester tester, Widget row) async {
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('id'),
      home: Scaffold(body: ListView(children: [row])),
    ));
    await tester.pump();
    return AppLocalizations.delegate.load(const Locale('id'));
  }

  for (final type in ['DAILY', 'GROWTH_MOMENT', 'KNOWLEDGE']) {
    testWidgets('可见帖子行（$type）：摘要 + 作者 + 类型标与 PostGridTile 同配色；无封面画占位', (tester) async {
      final l10n = await pump(tester, CollectedPostRowView(item: visiblePost(5, type: type), onTap: () {}));
      expect(find.text('excerpt 5'), findsOneWidget);
      expect(find.text('Rina'), findsOneWidget);
      final (label, fg, bg) = PostGridTile.badgeStyle(type, l10n);
      final badge = find.byKey(const ValueKey('collectedPostTypeBadge'));
      expect(find.descendant(of: badge, matching: find.text(label)), findsOneWidget);
      final box = tester.widget<Container>(badge);
      expect((box.decoration! as BoxDecoration).color, bg);
      expect(tester.widget<Text>(find.descendant(of: badge, matching: find.text(label))).style!.color, fg);
      expect(find.byKey(const ValueKey('collectedPostRow_5')), findsOneWidget);
    });
  }

  testWidgets('不可见帖子行：不显示摘要 / 作者，显示「Konten tidak tersedia lagi」+「Ketuk untuk menghapus」', (tester) async {
    await pump(tester, CollectedPostRowView(item: hiddenPost(6), onTap: () {}));
    expect(find.text('Konten tidak tersedia lagi'), findsOneWidget);
    expect(find.text('Ketuk untuk menghapus'), findsOneWidget);
    expect(find.byIcon(Icons.visibility_off_outlined), findsOneWidget);
    expect(find.byKey(const ValueKey('collectedPostTypeBadge')), findsNothing);
    expect(find.text('Rina'), findsNothing);
  });

  testWidgets('available=true 但 post=null → 按不可见渲染不崩', (tester) async {
    await pump(tester, CollectedPostRowView(
        item: CollectedPost.fromJson({'postId': 7, 'available': true}), onTap: () {}));
    expect(find.text('Konten tidak tersedia lagi'), findsOneWidget);
  });

  testWidgets('可见场所行：名称 / 类型 / 地址；未知类型省略类型行', (tester) async {
    await pump(tester, Column(children: [
      CollectedPlaceRowView(item: visiblePlace('a'), onTap: () {}),
      CollectedPlaceRowView(item: visiblePlace('b', type: 'SOMETHING_NEW'), onTap: () {}),
    ]));
    expect(find.text('Kopi a'), findsOneWidget);
    expect(find.text('Jl. Test'), findsNWidgets(2));
    expect(find.text('Kafe'), findsOneWidget);
  });

  testWidgets('不可见场所行：名称仍在、占位块（无图片）、「Tempat tidak tersedia lagi」', (tester) async {
    await pump(tester, CollectedPlaceRowView(item: hiddenPlace('z'), onTap: () {}));
    expect(find.text('Gone z'), findsOneWidget);
    expect(find.text('Tempat tidak tersedia lagi'), findsOneWidget);
    expect(find.byKey(const ValueKey('collectedUnavailableThumb')), findsOneWidget);
    expect(find.byType(Image), findsNothing);
    expect(find.text('Jl. Test'), findsNothing);
  });
}
