import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/domain/content_tag.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/domain/pinned_slot.dart';
import 'package:tailtopia/features/content/presentation/feed_view.dart';
import 'package:tailtopia/features/content/presentation/pinned_badge.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/anchored_tooltip.dart';
import 'package:tailtopia/shared/widgets/content_tag_chip.dart';
import 'package:tailtopia/shared/widgets/feed_image.dart';
import 'package:tailtopia/shared/widgets/masonry_card.dart';

/// V1.1.6 Story 5.2：内容装饰标签。
///
/// <p>守两件事：**标签在左下角位、与另外两个角位互不重叠**（这是 3.4 那条叠加层契约的兑现），
/// 以及**点它弹的是 5.1 那个共享 tooltip**（不得两条 story 各建一个）。
ContentTag _tag(String code) => ContentTag(
    code: code, name: '编辑推荐', icon: '🏆', description: '被官方选中的优质内容');

FeedItem _item({List<ContentTag> tags = const []}) => FeedItem(
      id: 1,
      authorId: 7,
      authorDeleted: false,
      authorNickname: 'Alice',
      type: 'DAILY',
      body: 'body',
      firstImageUrl: 'https://cdn.example.com/a.jpg',
      imageUrls: const ['https://cdn.example.com/a.jpg', 'https://cdn.example.com/b.jpg'],
      imageSizes: const [ImageSize(1200, 1600)],
      createdAt: DateTime.utc(2026, 6, 2),
      decorationTags: tags,
    );

/// ⚠️ [maxImageHeight] 默认压得很小：测试画布只有 800×600，
/// 通栏图按真实比例会有一千多高，卡片底部（连同左下角的标签）会落到画布外 ——
/// 那时 `tester.tap` 点的是画布外的坐标、什么都碰不到。
Future<void> _pumpCard(WidgetTester tester,
    {required FeedItem item, Widget? pinnedBadge, double maxImageHeight = 900}) async {
  await tester.pumpWidget(ProviderScope(
    child: MediaQuery(
      data: const MediaQueryData(size: Size(390, 844)),
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: SingleChildScrollView(
            child: MasonryCard(
              item: item,
              deletedUserLabel: 'Deleted user',
              maxImageHeight: maxImageHeight,
              pinnedBadge: pinnedBadge,
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.pump();
}

void main() {
  tearDown(dismissAnchoredTooltip);

  group('AC3/AC4 首页卡：左下角位', () {
    testWidgets('装饰标签落在图片区左下角', (tester) async {
      await _pumpCard(tester, item: _item(tags: [_tag('editor_pick')]));

      final area = tester.getRect(find.byType(FeedImage));
      final chip = tester.getRect(find.byType(ContentTagChip));
      expect(chip.left - area.left, closeTo(8, 0.01));
      expect(area.bottom - chip.bottom, closeTo(8, 0.01));
    });

    /// 🛡 三个角位分处三处、互不遮挡（AD-8 Rule 6）：
    /// 装饰标签左下、顶置角标右上、轮播圆点底边居中。
    testWidgets('与顶置角标、轮播圆点互不重叠', (tester) async {
      await _pumpCard(tester,
          item: _item(tags: [_tag('editor_pick')]), pinnedBadge: const PinnedBadge());

      final chip = tester.getRect(find.byType(ContentTagChip));
      final badge = tester.getRect(find.byType(PinnedBadge));
      final dot = tester.getRect(find.byKey(const ValueKey('feedCarouselDot_0')));

      expect(chip.overlaps(badge), isFalse, reason: '左下 vs 右上');
      expect(chip.overlaps(dot), isFalse, reason: '左下 vs 底边居中');
      expect(badge.overlaps(dot), isFalse);
    });

    testWidgets('没有装饰标签时左下角位不渲染任何东西', (tester) async {
      await _pumpCard(tester, item: _item());
      expect(find.byType(ContentTagChip), findsNothing);
    });
  });

  group('AC5 复用 5.1 的 tooltip', () {
    /// 🛡 点装饰标签弹的是**同一个共享 tooltip** —— AC 明写不得两条 story 各建一个。
    testWidgets('点标签弹出名称与说明', (tester) async {
      await _pumpCard(tester,
          item: _item(tags: [_tag('editor_pick')]), maxImageHeight: 150);

      await tester.tap(find.byKey(const ValueKey('contentTag_editor_pick')));
      await tester.pumpAndSettle();

      // tagTooltip 这个 key 属于 Story 5.1 建的共享组件
      final tooltip = find.byKey(const ValueKey('tagTooltip'));
      expect(tooltip, findsOneWidget);
      // ⚠️ 标签名会出现两次（胶囊上一次、气泡标题一次），所以要限定在气泡里找。
      expect(find.descendant(of: tooltip, matching: find.text('编辑推荐')), findsOneWidget);
      expect(find.descendant(of: tooltip, matching: find.text('被官方选中的优质内容')),
          findsOneWidget);
    });

    testWidgets('点外部关闭', (tester) async {
      await _pumpCard(tester,
          item: _item(tags: [_tag('editor_pick')]), maxImageHeight: 150);
      await tester.tap(find.byKey(const ValueKey('contentTag_editor_pick')));
      await tester.pumpAndSettle();

      await tester.tapAt(const Offset(5, 5));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('tagTooltip')), findsNothing);
    });
  });

  group('顶置卡也带装饰标签', () {
    /// 顶置的是一条普通内容 —— 它的装饰标签同样该出现（两个角位并存）。
    testWidgets('顶置卡上装饰标签与顶置角标并存', (tester) async {
      await tester.pumpWidget(ProviderScope(
        child: MediaQuery(
          data: const MediaQueryData(size: Size(390, 844)),
          child: MaterialApp(
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            home: Scaffold(
              body: FeedMasonryView(
                items: const [],
                hasMore: false,
                loadingMore: false,
                deletedUserLabel: 'x',
                onLoadMore: () async {},
                onRefresh: () async {},
                pinned: PinnedSlot(
                  pinConfigId: 1,
                  pinType: 'CONTENT',
                  item: _item(tags: [_tag('editor_pick')]),
                ),
              ),
            ),
          ),
        ),
      ));
      await tester.pump();

      expect(find.byType(PinnedBadge), findsOneWidget);
      expect(find.byType(ContentTagChip), findsOneWidget);
      expect(tester.getRect(find.byType(ContentTagChip))
          .overlaps(tester.getRect(find.byType(PinnedBadge))), isFalse);
    });
  });

  group('线格式解析', () {
    test('字段缺失 → 空表；坏元素被滤掉', () {
      expect(ContentTag.listFromJson(null), isEmpty);
      final list = ContentTag.listFromJson([
        {'code': 'a', 'name': 'A', 'icon': '🏆', 'description': 'd'},
        {'code': 'b'},
        7,
      ]);
      expect(list, hasLength(1));
      expect(list.single.code, 'a');
    });

    test('首页条目读得到装饰标签', () {
      final item = FeedItem.fromJson({
        'id': 1,
        'authorId': 2,
        'type': 'DAILY',
        'createdAt': '2026-06-02T00:00:00Z',
        'decorationTags': [
          {'code': 'a', 'name': 'A', 'icon': '🏆', 'description': 'd'}
        ],
      });
      expect(item.decorationTags.single.code, 'a');
    });
  });
}
