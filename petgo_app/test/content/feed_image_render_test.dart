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
  List<double?> offsets = const [],
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
      imageCropOffsets: offsets,
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

    testWidgets('超出上界的全景图夹到 1.78', (tester) async {
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

  group('V1.3.4 2.2 开窗', () {
    Image feedImg(WidgetTester tester) => tester.widget<Image>(find.descendant(
        of: find.byType(FeedImage), matching: find.byType(Image)));

    testWidgets('3:1 宽图 + offset 0 → 贴左开窗、cover、比例 kFeedRatioMax', (tester) async {
      final aspect = await _pumpAspect(
          tester, _item(sizes: const [ImageSize(3000, 1000)], offsets: const [0.0]),
          maxImageHeight: 10000);
      expect(aspect, kFeedRatioMax);
      final img = feedImg(tester);
      expect(img.alignment, const Alignment(-1, 0));
      expect(img.fit, BoxFit.cover);
    });

    testWidgets('1:2 竖图 + offset 1 → 贴下开窗、比例 kFeedRatioMin', (tester) async {
      final aspect = await _pumpAspect(
          tester, _item(sizes: const [ImageSize(1000, 2000)], offsets: const [1.0]),
          maxImageHeight: 10000);
      expect(aspect, kFeedRatioMin);
      expect(feedImg(tester).alignment, const Alignment(0, 1));
    });

    /// 🔴 AC5.1 向后兼容硬验收：无 offset 与 offset 为 null 渲染参数完全相同。
    testWidgets('AC5.1 无 offset 与 [null] 均居中且比例相等', (tester) async {
      final a = await _pumpAspect(
          tester, _item(sizes: const [ImageSize(3000, 1000)]),
          maxImageHeight: 10000);
      final imgA = feedImg(tester);
      expect(imgA.alignment, Alignment.center);
      expect(imgA.fit, BoxFit.cover);

      final b = await _pumpAspect(
          tester, _item(sizes: const [ImageSize(3000, 1000)], offsets: const [null]),
          maxImageHeight: 10000);
      final imgB = feedImg(tester);
      expect(imgB.alignment, Alignment.center);
      expect(imgB.fit, BoxFit.cover);
      expect(b, a);
    });

    testWidgets('区间内 4:3 带 offset 0 → 仍居中', (tester) async {
      await _pumpAspect(
          tester, _item(sizes: const [ImageSize(1600, 1200)], offsets: const [0.0]),
          maxImageHeight: 10000);
      expect(feedImg(tester).alignment, Alignment.center);
    });

    test('fromJson 读 imageCropOffsets；缺字段为空表；copyWith 透传', () {
      final base = {
        'id': 1,
        'authorId': 2,
        'createdAt': '2026-10-12T00:00:00Z',
      };
      final withOffsets = FeedItem.fromJson({
        ...base,
        'imageCropOffsets': [0, null],
      });
      expect(withOffsets.imageCropOffsets, [0.0, null]);
      expect(withOffsets.cropOffsetAt(0), 0.0);
      expect(withOffsets.cropOffsetAt(1), isNull);
      expect(withOffsets.cropOffsetAt(5), isNull);
      expect(withOffsets.copyWith(likeCount: 3).imageCropOffsets, [0.0, null]);
      expect(FeedItem.fromJson(base).imageCropOffsets, isEmpty);
    });
  });
}
