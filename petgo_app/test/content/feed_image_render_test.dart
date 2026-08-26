import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/feed_image.dart';
import 'package:tailtopia/shared/widgets/masonry_card.dart';

/// V1.1.6 Story 3.3：三段口径落到**卡片**上的效果。
///
/// <p>纯函数的口径由 `feed_image_layout_test.dart` 守；这组守的是"接线接对了没有"——
/// 尺寸有没有传进去、护栏上限有没有传进去、无图帖有没有被误渲染成图片区。
FeedItem _item({
  int id = 1,
  String? image = 'https://cdn.example.com/a.jpg',
  List<ImageSize?> sizes = const [],
}) =>
    FeedItem(
      id: id,
      authorId: 7,
      authorDeleted: false,
      authorNickname: 'Alice',
      type: 'DAILY',
      body: 'Hello pets',
      firstImageUrl: image,
      createdAt: DateTime.utc(2026, 6, 2),
      imageSizes: sizes,
    );

Future<double?> _pumpAspect(
  WidgetTester tester,
  FeedItem item, {
  double? maxImageHeight,
  Size screen = const Size(390, 844),
}) async {
  await tester.pumpWidget(ProviderScope(
    child: MediaQuery(
      data: MediaQueryData(size: screen),
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: SingleChildScrollView(
            child: MasonryCard(
              item: item,
              deletedUserLabel: 'Deleted user',
              maxImageHeight: maxImageHeight,
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.pump();
  final finder = find.descendant(
      of: find.byType(FeedImage), matching: find.byType(AspectRatio));
  if (finder.evaluate().isEmpty) return null;
  return tester.widget<AspectRatio>(finder).aspectRatio;
}

void main() {
  group('AC1 卡片按实际比例定高', () {
    /// 🔴 改版前这里是写死的 4:3 —— 3:4 竖拍恒被裁 44%。这条钉住"竖图真的按竖图渲染了"。
    testWidgets('3:4 竖图渲染为 0.75，不再是 4:3', (tester) async {
      final aspect = await _pumpAspect(
          tester, _item(sizes: const [ImageSize(1200, 1600)]),
          maxImageHeight: 900);
      expect(aspect, 0.75);
      expect(aspect, isNot(closeTo(4 / 3, 0.01)), reason: '不得再回到写死的 4:3');
    });

    testWidgets('超出上界的全景图夹到 1.34', (tester) async {
      final aspect = await _pumpAspect(
          tester, _item(sizes: const [ImageSize(3000, 1000)]),
          maxImageHeight: 900);
      expect(aspect, kFeedRatioMax);
    });
  });

  group('AC2 护栏上限确实传到了卡片里', () {
    /// 列表容器量出的视口高度必须真的作用到图片区上，否则护栏形同虚设。
    testWidgets('上限收紧后竖图被压扁', (tester) async {
      final tall =
          await _pumpAspect(tester, _item(sizes: const [ImageSize(3, 4)]), maxImageHeight: 900);
      final squeezed =
          await _pumpAspect(tester, _item(sizes: const [ImageSize(3, 4)]), maxImageHeight: 300);
      expect(tall, 0.75);
      expect(squeezed, greaterThan(0.75));
      expect(390 / squeezed!, closeTo(300, 0.5), reason: '实际高度应正好落在上限上');
    });

    /// 不传上限时退化成按屏高估算 —— 单卡场景（如测试、非列表复用）不该崩，也不该无护栏。
    testWidgets('不传上限时按屏高兜底', (tester) async {
      final aspect = await _pumpAspect(
        tester,
        _item(sizes: const [ImageSize(3, 4)]),
        screen: const Size(320, 480), // 小屏
      );
      expect(aspect, greaterThan(0.75), reason: '小屏上竖图应被护栏压扁');
    });
  });

  group('AC3 占位', () {
    /// 🔴 存量内容永远没有尺寸 —— 加载前必须先按 1:1 把高度占住。
    testWidgets('无尺寸的存量帖按 1:1 预留', (tester) async {
      final aspect = await _pumpAspect(tester, _item(), maxImageHeight: 900);
      expect(aspect, kFeedPlaceholderRatio);
    });

    testWidgets('元素为 null（那一张测不出来）同样按 1:1 预留', (tester) async {
      final aspect =
          await _pumpAspect(tester, _item(sizes: const [null]), maxImageHeight: 900);
      expect(aspect, kFeedPlaceholderRatio);
    });

    /// 无图帖不该凭空长出一个图片区（现状即如此，这条防回归）。
    testWidgets('无图帖不渲染图片区', (tester) async {
      final aspect = await _pumpAspect(tester, _item(image: null), maxImageHeight: 900);
      expect(aspect, isNull);
      expect(find.byType(FeedImage), findsNothing);
    });
  });

  group('AC3 线格式解析', () {
    /// ⚠️ 后端无图时**整个字段都不下发**（Jackson NON_NULL），解析必须容忍缺失。
    test('缺字段 / null 元素 / 长度不符都不炸', () {
      final noField = FeedItem.fromJson({
        'id': 1,
        'authorId': 2,
        'type': 'DAILY',
        'createdAt': '2026-06-02T00:00:00Z',
      });
      expect(noField.imageSizes, isEmpty);
      expect(noField.firstImageSize, isNull);

      final mismatched = FeedItem.fromJson({
        'id': 1,
        'authorId': 2,
        'type': 'DAILY',
        'createdAt': '2026-06-02T00:00:00Z',
        'firstImageUrl': 'https://cdn.example.com/a.jpg',
        // 两张图但只给了一个尺寸 —— 后端集成测试里明确覆盖了这种情况
        'imageSizes': [
          {'w': 1200, 'h': 1600},
        ],
      });
      expect(mismatched.firstImageSize!.ratio, 0.75);

      final nullFirst = FeedItem.fromJson({
        'id': 1,
        'authorId': 2,
        'type': 'DAILY',
        'createdAt': '2026-06-02T00:00:00Z',
        'imageSizes': [null, {'w': 800, 'h': 800}],
      });
      expect(nullFirst.firstImageSize, isNull);
    });
  });
}
