import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/domain/detail_image_layout.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';

/// V1.3.0 批次 A · Story 2.2（L0）：详情页图片按原比例通栏（FR-113 · AD-A11 / AD-A12）。
///
/// 要消灭的问题：详情页原本是 `AspectRatio(1)` + `BoxFit.cover`，强制裁成方图、两侧留边 ——
/// 从 Feed 点进详情，**同一张图会从原比例通栏跳变成 1:1 裁切**。
///
/// 「像不像、好不好看」属 L2 真机；这里钉两件 L0 能钉死的：
/// **三段口径走的是同一个函数**，以及**护栏口径确实是详情页自己的、没照抄 Feed**。
void main() {
  group('AC2 🔴 三段口径与 Feed 共用同一个出口函数', () {
    /// 只要护栏上限相同，详情页与 Feed 必须给出**逐位相等**的比例。
    ///
    /// 这条断言的意义不在于"两个函数碰巧结果一样"，而在于：详情页那层是个薄壳，
    /// 计算全在 [resolveFeedImageAspect] 里。哪天有人在详情页偷偷加一句 clamp 或兜底，
    /// 这里立刻不等 —— 而线上的表现就是从首页点进详情时图片跳一下。
    test('同一张图、同一护栏上限 → 详情与 Feed 结果完全一致', () {
      const width = 360.0;
      // 让两边的护栏上限相等，从而只比较"计算过程"本身。
      const viewport = 600.0;
      final maxH = DetailImageMetrics.maxImageHeight(viewport);

      for (final size in <ImageSize?>[
        null, // 存量内容
        const ImageSize(1200, 900), // 4:3 横
        const ImageSize(900, 1200), // 3:4 竖
        const ImageSize(1080, 1080), // 方
        const ImageSize(800, 3000), // 极端长图
        const ImageSize(3000, 800), // 极端宽图
      ]) {
        expect(
          resolveDetailImageAspect(size: size, width: width, viewportHeight: viewport),
          resolveFeedImageAspect(size: size, width: width, maxImageHeight: maxH),
          reason: '详情页那层必须是薄壳，$size 上出现差异说明有人在详情侧另写了一套',
        );
      }
    });

    test('闭区间端点：3:4 竖拍（恰为 0.75）不被夹走', () {
      final a = resolveDetailImageAspect(
        size: const ImageSize(900, 1200),
        width: 360,
        viewportHeight: 2000, // 视口足够大 → 护栏不介入
      );
      expect(a, closeTo(0.75, 1e-9));
    });

    test('超出闭区间的被收敛：极端长图 → 0.75，极端宽图 → 1.34', () {
      expect(
        resolveDetailImageAspect(
            size: const ImageSize(400, 3000), width: 360, viewportHeight: 2000),
        closeTo(kFeedRatioMin, 1e-9),
      );
      expect(
        resolveDetailImageAspect(
            size: const ImageSize(3000, 400), width: 360, viewportHeight: 2000),
        closeTo(kFeedRatioMax, 1e-9),
      );
    });
  });

  group('AC3 🔴 护栏口径按详情页取，未照抄 Feed', () {
    /// 详情页没有「下一条露出」这个概念，所以它的被减项**必然与 Feed 不同**。
    /// 数值相等就说明有人把 Feed 的常量抄过来了 —— 那会算错，而且是往小了算
    /// （多扣了不存在的露出余量），结果详情页的竖图裁得比 Feed 还狠。
    test('chrome 常量与 Feed 不是同一个数', () {
      expect(
        DetailImageMetrics.chrome,
        isNot(FeedCardMetrics.chrome),
        reason: '详情页不截断正文、没有下一条露出、有常驻底栏 —— 三点都与 Feed 不同',
      );
    });

    test('同一视口下，详情页给图片的上限比 Feed 宽松', () {
      const viewport = 600.0;
      expect(
        DetailImageMetrics.maxImageHeight(viewport),
        greaterThan(FeedCardMetrics.maxImageHeight(viewport)),
        reason: '详情页不必给「下一条」留露出余量，本就该比列表宽松',
      );
    });

    /// 🔴 底栏不重复扣：`LayoutBuilder` 量到的视口高度已经把常驻底栏排除在外了。
    /// 两边各记一遍就是 Feed 那次实机复核抓到的同类错误（条目间隔被重复计数）。
    test('护栏只减 chrome，不再重复扣常驻底栏', () {
      const viewport = 600.0;
      expect(
        DetailImageMetrics.maxImageHeight(viewport),
        closeTo(viewport - DetailImageMetrics.chrome, 1e-9),
      );
      expect(
        DetailImageMetrics.maxImageHeight(viewport),
        isNot(closeTo(
            viewport - DetailImageMetrics.chrome - DetailImageMetrics.fixedBottomBar, 1e-9)),
      );
    });

    test('极端小视口不算出负数，留 80 的下限', () {
      expect(DetailImageMetrics.maxImageHeight(50), 80);
      expect(DetailImageMetrics.maxImageHeight(0), 80);
    });

    /// 小屏机（360×640 量级）上极端长图必须被护栏压住。
    /// 真机观感属 L2，这里只验「上限确实生效」这条算术事实。
    test('小屏 + 极端长图 → 护栏生效，高度不超上限', () {
      const width = 360.0;
      const viewport = 470.0; // 360×640 机型的滚动视口量级
      final aspect = resolveDetailImageAspect(
        size: const ImageSize(800, 4000),
        width: width,
        viewportHeight: viewport,
      );
      final height = width / aspect;

      expect(height, lessThanOrEqualTo(DetailImageMetrics.maxImageHeight(viewport) + 1e-9));
    });
  });

  group('AC7 存量占位兜底与 Feed 同一套', () {
    test('无尺寸 → 走 kFeedPlaceholderRatio（视口足够大时护栏不介入）', () {
      expect(
        resolveDetailImageAspect(size: null, width: 360, viewportHeight: 2000),
        closeTo(kFeedPlaceholderRatio, 1e-9),
      );
    });

    test('不可用尺寸（0 宽 / 0 高）同样退回占位', () {
      expect(
        resolveDetailImageAspect(
            size: const ImageSize(0, 100), width: 360, viewportHeight: 2000),
        closeTo(kFeedPlaceholderRatio, 1e-9),
      );
    });
  });

  group('AC1/AC4 模型：尺寸按下标安全取', () {
    ContentDetail detail({List<ImageSize?> sizes = const []}) => ContentDetail(
          id: 1,
          authorId: 7,
          authorDeleted: false,
          type: 'DAILY',
          likeCount: 0,
          commentCount: 0,
          liked: false,
          isAuthor: false,
          createdAt: DateTime.utc(2026, 6, 5),
          imageUrls: const ['a', 'b'],
          imageSizes: sizes,
        );

    test('容器按首图锁定 → 取的是下标 0', () {
      final d = detail(sizes: const [ImageSize(1200, 900), ImageSize(900, 1200)]);
      // ⚠️ 逐字段比而不是比对象：ImageSize 没有重写 ==，而它属 `feed_image_layout.dart`
      // （本 story 明示只读复用、不改）。给它加值语义是另一件事，不在本 story 范围内。
      expect(d.sizeAt(0)!.w, 1200);
      expect(d.sizeAt(0)!.h, 900);
    });

    test('越界 / 缺失 → null，不抛（老响应体可能短一截）', () {
      expect(detail().sizeAt(0), isNull);
      expect(detail(sizes: const [ImageSize(1200, 900)]).sizeAt(1), isNull);
      expect(detail(sizes: const [ImageSize(1200, 900)]).sizeAt(-1), isNull);
    });

    test('线格式：整字段缺失 / 元素为 null 都要容忍', () {
      final legacy = ContentDetail.fromJson({
        'id': 1,
        'authorId': 7,
        'type': 'DAILY',
        'createdAt': '2026-06-05T00:00:00Z',
        'imageUrls': ['a', 'b'],
        // 刻意没有 imageSizes —— 存量内容 / 老后端
      });
      expect(legacy.imageSizes, isEmpty);
      expect(legacy.sizeAt(0), isNull);

      final partial = ContentDetail.fromJson({
        'id': 1,
        'authorId': 7,
        'type': 'DAILY',
        'createdAt': '2026-06-05T00:00:00Z',
        'imageUrls': ['a', 'b'],
        'imageSizes': [null, {'w': 800, 'h': 800}],
      });
      expect(partial.sizeAt(0), isNull);
      expect(partial.sizeAt(1)!.w, 800);
      expect(partial.sizeAt(1)!.h, 800);
    });
  });
}
