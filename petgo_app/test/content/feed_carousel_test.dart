import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/feed_image.dart';
import 'package:tailtopia/shared/widgets/masonry_card.dart';

/// V1.1.6 Story 3.4：首页多图轮播 + 图片区叠加层。
///
/// <p>这组测试守两件事：**轮播不能把别的手势吃掉**，以及**叠加层的三个角位不能被后来者改掉**。
/// 后者尤其重要 —— 叠加层是 Epic 4（顶置角标）与 Epic 5（装饰标签）的地基，
/// 它们只该往角位里挂内容。
FeedItem _item({
  int id = 1,
  List<String> urls = const ['https://cdn.example.com/a.jpg'],
  List<ImageSize?> sizes = const [ImageSize(1200, 1600)],
}) =>
    FeedItem(
      id: id,
      authorId: 7,
      authorDeleted: false,
      authorNickname: 'Alice',
      type: 'DAILY',
      body: 'Hello pets',
      firstImageUrl: urls.isEmpty ? null : urls.first,
      imageUrls: urls,
      imageSizes: sizes,
      createdAt: DateTime.utc(2026, 6, 2),
    );

const _threeImages = [
  'https://cdn.example.com/a.jpg',
  'https://cdn.example.com/b.jpg',
  'https://cdn.example.com/c.jpg',
];

Future<void> _pump(
  WidgetTester tester,
  FeedItem item, {
  VoidCallback? onTap,
  Widget? pinnedBadge,
  Widget? decorTag,
}) async {
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
              maxImageHeight: 900,
              onTap: onTap,
              pinnedBadge: pinnedBadge,
              decorTag: decorTag,
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.pump();
}

double _aspect(WidgetTester tester) => tester
    .widget<AspectRatio>(
        find.descendant(of: find.byType(FeedImage), matching: find.byType(AspectRatio)))
    .aspectRatio;

void main() {
  group('AC1 轮播与圆点', () {
    testWidgets('多图 → 出圆点，数量等于图片数', (tester) async {
      await _pump(tester, _item(urls: _threeImages));
      expect(find.byType(PageView), findsOneWidget);
      for (var i = 0; i < 3; i++) {
        expect(find.byKey(ValueKey('feedCarouselDot_$i')), findsOneWidget);
      }
      expect(find.byKey(const ValueKey('feedCarouselDot_3')), findsNothing);
    });

    /// 🔴 单图是**压根不接横向手势**，不是"接了但滑不动"。
    ///
    /// 单图帖若仍挂着横向识别器，用户斜着滑一下就可能被截胡、列表滚不动 ——
    /// 这类"偶尔滚不动"最难复现也最恼人。所以这里断言的是**根本没有轮播控件**。
    testWidgets('单图 → 无圆点、且完全没有轮播控件', (tester) async {
      await _pump(tester, _item());
      expect(find.byType(PageView), findsNothing);
      expect(find.byKey(const ValueKey('feedCarouselDot_0')), findsNothing);
    });

    testWidgets('翻页后高亮点跟着走', (tester) async {
      await _pump(tester, _item(urls: _threeImages));
      Size dot(int i) => tester.getSize(find.byKey(ValueKey('feedCarouselDot_$i')));
      expect(dot(0).width, greaterThan(dot(1).width), reason: '当前点更大');

      await tester.fling(find.byType(PageView), const Offset(-400, 0), 1200);
      await tester.pumpAndSettle();
      expect(dot(1).width, greaterThan(dot(0).width));
    });
  });

  group('AC3 🛡 滑动时高度不变', () {
    /// Feed 是长列表 —— 滑图时若容器变高，下方所有卡片会被整体推动。
    /// 高度只由**首图**决定，非首图居中裁剪填满。
    testWidgets('翻到第二张后容器高度不变', (tester) async {
      // 首图 3:4（0.75），第二张的真实比例完全不同也不该影响容器
      await _pump(tester, _item(urls: _threeImages, sizes: const [ImageSize(1200, 1600)]));
      final before = _aspect(tester);
      expect(before, 0.75);

      await tester.fling(find.byType(PageView), const Offset(-400, 0), 1200);
      await tester.pumpAndSettle();
      expect(_aspect(tester), before, reason: '翻页不得改变容器高度');
    });

    testWidgets('尺寸数组只取首图那一项', (tester) async {
      await _pump(tester, _item(
        urls: _threeImages,
        // 第二、三张是横图，但容器必须听首图的
        sizes: const [ImageSize(1200, 1600), ImageSize(1600, 900), ImageSize(1600, 900)],
      ));
      expect(_aspect(tester), 0.75);
    });
  });

  group('AC4 🛡 叠加层三个角位（Epic 4/5 的地基契约）', () {
    /// 🔴 这条守的是**结构**，不是观感。
    ///
    /// 架构层面把三个角位写死了：圆点底边居中、顶置角标右上、装饰标签左下，三者互不重叠。
    /// Epic 4 与 Epic 5 只该往入参里挂内容 —— 谁再去改图片区结构，这条会红。
    testWidgets('右上挂顶置角标、左下挂装饰标签，各就各位且不重叠', (tester) async {
      await _pump(
        tester,
        _item(urls: _threeImages),
        pinnedBadge: const SizedBox(key: ValueKey('slotTopRight'), width: 40, height: 18),
        decorTag: const SizedBox(key: ValueKey('slotBottomLeft'), width: 60, height: 20),
      );

      final area = tester.getRect(find.byType(FeedImage));
      final topRight = tester.getRect(find.byKey(const ValueKey('slotTopRight')));
      final bottomLeft = tester.getRect(find.byKey(const ValueKey('slotBottomLeft')));

      // 右上：贴右、贴顶
      expect(area.right - topRight.right, closeTo(8, 0.01));
      expect(topRight.top - area.top, closeTo(8, 0.01));
      // 左下：贴左、贴底
      expect(bottomLeft.left - area.left, closeTo(8, 0.01));
      expect(area.bottom - bottomLeft.bottom, closeTo(8, 0.01));
      // 三者互不重叠
      expect(topRight.overlaps(bottomLeft), isFalse);

      // 圆点：底边居中。
      // ⚠️ 量的是圆点本身，不是承载它们的那一行 —— 那一行是通栏宽的，量它等于没量。
      final firstDot = tester.getRect(find.byKey(const ValueKey('feedCarouselDot_0')));
      final lastDot = tester.getRect(find.byKey(const ValueKey('feedCarouselDot_2')));
      expect((firstDot.left + lastDot.right) / 2, closeTo(area.center.dx, 1.0));
      expect(area.bottom - firstDot.bottom, closeTo(9, 0.01));
      expect(firstDot.overlaps(bottomLeft), isFalse);
      expect(lastDot.overlaps(topRight), isFalse);
    });

    /// 不挂东西时角位不该凭空占位（空 story 交付的是**空位**）。
    testWidgets('不传角位内容时不渲染任何角位', (tester) async {
      await _pump(tester, _item());
      expect(find.byKey(const ValueKey('slotTopRight')), findsNothing);
      expect(find.byKey(const ValueKey('slotBottomLeft')), findsNothing);
    });
  });

  group('AC2 手势三分', () {
    /// 点击 = 进详情。轮播接管的是**横向拖拽**，不该把点击也吃掉。
    testWidgets('多图帖点击图片仍进详情', (tester) async {
      var tapped = 0;
      await _pump(tester, _item(urls: _threeImages), onTap: () => tapped++);
      await tester.tap(find.byType(PageView));
      await tester.pump();
      expect(tapped, 1);
    });

    testWidgets('单图帖点击图片仍进详情', (tester) async {
      var tapped = 0;
      await _pump(tester, _item(), onTap: () => tapped++);
      await tester.tap(find.byType(FeedImage));
      await tester.pump();
      expect(tapped, 1);
    });

    /// 纵向滑动必须继续滚列表 —— 不能被轮播吃掉。
    testWidgets('在图片区纵向拖拽仍滚动列表', (tester) async {
      await _pump(tester, _item(urls: _threeImages));
      final scrollable = find.byType(Scrollable).first;
      final before = tester.widget<Scrollable>(scrollable).controller?.offset ?? 0;
      await tester.drag(find.byType(PageView), const Offset(0, -200));
      await tester.pumpAndSettle();
      final after = tester.widget<Scrollable>(scrollable).controller?.offset ?? 0;
      expect(after, isNot(before), reason: '纵向拖拽不该被轮播吃掉');
    });
  });

  group('AC1 线格式解析', () {
    test('整组图片：缺字段回落到首图，非字符串元素被滤掉', () {
      final withGroup = FeedItem.fromJson({
        'id': 1,
        'authorId': 2,
        'type': 'DAILY',
        'createdAt': '2026-06-02T00:00:00Z',
        'firstImageUrl': 'a.jpg',
        'imageUrls': ['a.jpg', 'b.jpg', 3],
      });
      expect(withGroup.images, ['a.jpg', 'b.jpg']);

      final onlyFirst = FeedItem.fromJson({
        'id': 1,
        'authorId': 2,
        'type': 'DAILY',
        'createdAt': '2026-06-02T00:00:00Z',
        'firstImageUrl': 'a.jpg',
      });
      expect(onlyFirst.images, ['a.jpg'], reason: '老后端只给首图时不该变成无图帖');

      final noImage = FeedItem.fromJson({
        'id': 1,
        'authorId': 2,
        'type': 'DAILY',
        'createdAt': '2026-06-02T00:00:00Z',
      });
      expect(noImage.images, isEmpty);
    });
  });
}
