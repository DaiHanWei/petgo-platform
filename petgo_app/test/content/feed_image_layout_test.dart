import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';

/// V1.1.6 Story 3.3：Feed 图片渲染的**三段口径**。
///
/// <p>这组测试守的是三件很容易被"顺手优化"掉的事：三段的**顺序**、闭区间的**端点归属**、
/// 以及尺寸数据缺失时的**容错**。三者都不是观感问题 —— 前两者会裁掉用户的构图，
/// 后者会在真实数据上直接崩。
void main() {
  group('AC1 ② 闭区间收敛', () {
    /// 🛡 端点必须**算在区间内**。3:4 竖拍恰为 0.75 且最常见 ——
    /// 若写成开区间，最该被保护的那种图恰好被判为"超出"而挨裁。
    test('端点 0.75 与 1.34 原样保留', () {
      expect(clampFeedRatio(0.75), 0.75);
      expect(clampFeedRatio(1.34), 1.34);
    });

    test('区间内原样保留', () {
      expect(clampFeedRatio(0.8), 0.8); // 4:5
      expect(clampFeedRatio(1.0), 1.0); // 方图
      expect(clampFeedRatio(1.2), 1.2);
      expect(clampFeedRatio(4 / 3), closeTo(1.3333, 0.0001)); // 手机默认横拍
    });

    test('超出者夹到最近边界', () {
      expect(clampFeedRatio(9 / 16), 0.75); // 竖屏长图
      expect(clampFeedRatio(3.0), 1.34); // 全景
    });

    /// 非法比例不能把整张卡的布局带崩（除零 / 负数 / NaN）。
    test('非法比例回落到占位比例', () {
      expect(clampFeedRatio(0), kFeedPlaceholderRatio);
      expect(clampFeedRatio(-1), kFeedPlaceholderRatio);
      expect(clampFeedRatio(double.nan), kFeedPlaceholderRatio);
      expect(clampFeedRatio(double.infinity), kFeedPlaceholderRatio);
    });
  });

  group('AC2 ③ 高度护栏', () {
    test('装得下就不动它 —— 竖图保住 0.75', () {
      // 宽 390、上限 600：0.75 对应高 520 < 600，护栏不介入。
      final r = resolveFeedImageAspect(
          size: const ImageSize(1200, 1600), width: 390, maxImageHeight: 600);
      expect(r, 0.75);
    });

    test('装不下就压到上限', () {
      // 宽 390、上限 400：0.75 对应高 520 > 400 → 比例被抬到 390/400。
      final r = resolveFeedImageAspect(
          size: const ImageSize(1200, 1600), width: 390, maxImageHeight: 400);
      expect(r, closeTo(390 / 400, 0.0001));
      expect(390 / r, closeTo(400, 0.001), reason: '实际高度必须正好落在上限上');
    });

    test('横图本来就矮，护栏不该把它拉长', () {
      final r = resolveFeedImageAspect(
          size: const ImageSize(1600, 1200), width: 390, maxImageHeight: 400);
      expect(r, closeTo(4 / 3, 0.0001));
    });

    /// 护栏的目的就这一句：**下一条内容的顶部总能露出来**。
    /// 这条把它写成算术 —— 图片 + 其余部分 + 余量 ≤ 可视区。
    test('最坏情况下仍给下一条留出露出余量', () {
      const viewport = 600.0; // 小屏可视区
      const width = 320.0;
      final maxH = FeedCardMetrics.maxImageHeight(viewport);
      // 最坏 = 最高的图，即比例被夹到下界的竖图
      final r = resolveFeedImageAspect(
          size: const ImageSize(9, 16), width: width, maxImageHeight: maxH);
      final imageHeight = width / r;
      expect(imageHeight, lessThanOrEqualTo(maxH + 0.001));
      expect(imageHeight + FeedCardMetrics.chrome + kFeedRevealMargin,
          lessThanOrEqualTo(viewport + 0.001),
          reason: '下一条内容的顶部必须露得出来');
    });

    test('可视区极小时不至于算出负高度', () {
      expect(FeedCardMetrics.maxImageHeight(100), greaterThan(0));
      expect(FeedCardMetrics.maxImageHeight(0), greaterThan(0));
    });
  });

  group('AC2/AC4 露出余量的实机口径（OA-1 已闭合）', () {
    /// 🔴 这条是实机复核**量出来的**，不是纸面推演。
    ///
    /// 360×640 小屏的滚动视口实测 470。原起点值 56 + 把条目间隔重复计一遍，
    /// 合计吃掉视口的 47%，图片区上限被压到 247 —— 比改版前写死的 4:3（270）**还矮**，
    /// 也就是小屏上竖图裁得比改版前更狠，与"少裁"的初衷正好相反。
    ///
    /// 这条把结论钉死：**最小屏上也不得比改版前的固定 4:3 更矮**。
    /// 谁再想把余量调大，先过这一关。
    test('最小屏上图片区不得比改版前的固定 4:3 更矮', () {
      const width = 360.0; // 360×640 小屏
      const viewport = 470.0; // 实机量得
      final maxH = FeedCardMetrics.maxImageHeight(viewport);
      const legacyFourThree = width / (4 / 3); // 改版前的固定高度
      expect(maxH, greaterThanOrEqualTo(legacyFourThree),
          reason: '护栏比改版前还狠 = 本次改版在小屏上帮了倒忙');
    });

    /// 条目间隔只能算一次 —— 它已经在露出余量里了。
    test('条目其余部分不重复计入条目间隔', () {
      expect(FeedCardMetrics.chrome,
          FeedCardMetrics.authorRow +
              FeedCardMetrics.actionRow +
              FeedCardMetrics.bodyRows +
              FeedCardMetrics.timeRow);
      expect(kFeedRevealMargin, greaterThan(25),
          reason: '余量须覆盖 25 的条目间隔，之外还要露出下一条的一部分');
    });

    /// ⚠️ 实机复核暴露的一个**产品事实**：3:4 竖图在常见机型上也刚好差一点点装不下。
    ///
    /// 390×844 的滚动视口约 700，扣掉其余部分与余量后上限 518，
    /// 而 3:4 需要 520 —— 差 2，裁掉不到 0.5%，肉眼完全看不出。
    /// 但这意味着「容差区间内原样展示、绝不裁剪」对 3:4 **严格来说从来不成立**
    /// （架构文档已把这条记为 FR-71 的已知削弱）。
    ///
    /// 这条守的是"削弱幅度可以忽略"，而不是假装它等于零。
    test('常见机型上 3:4 竖图的裁切可忽略（不到 5%）', () {
      const width = 390.0;
      const viewport = 700.0;
      final maxH = FeedCardMetrics.maxImageHeight(viewport);
      final r = resolveFeedImageAspect(
          size: const ImageSize(3, 4), width: width, maxImageHeight: maxH);
      final shown = width / r;
      final wanted = width / 0.75;
      expect(shown / wanted, greaterThan(0.95), reason: '常见机型上不该有肉眼可见的裁切');
    });

    /// 更高的机型（如 393×873）上则完全不裁 —— "在装得下的屏幕上不裁"确有其屏。
    test('高屏机型上 3:4 竖图完全不裁', () {
      const width = 393.0;
      const viewport = 760.0;
      final r = resolveFeedImageAspect(
          size: const ImageSize(3, 4),
          width: width,
          maxImageHeight: FeedCardMetrics.maxImageHeight(viewport));
      expect(r, 0.75, reason: '装得下就必须原样展示');
    });
  });

  group('AC1 🛡 三段顺序不可调换', () {
    /// 🔴 这条是本 story 的核心防线。
    ///
    /// 构造一个「先护栏后 clamp」会**违反护栏**的场景：可视区被压得很扁，
    /// 以致护栏要求的比例本身就超出了 1.34 上界。
    /// - 正确顺序（clamp → 护栏）：护栏最后施加，结果必然满足上限。
    /// - 错误顺序（护栏 → clamp）：clamp 把 1.6 拉回 1.34，图片高度随即**超出上限** —— 护栏白做。
    test('先 clamp 后护栏：结果必须满足高度上限', () {
      const width = 320.0;
      const maxH = 200.0; // 320/200 = 1.6，已经越过 1.34 上界

      final correct = resolveFeedImageAspect(
          size: const ImageSize(3, 4), width: width, maxImageHeight: maxH);
      expect(width / correct, lessThanOrEqualTo(maxH + 0.001));
      expect(correct, closeTo(1.6, 0.0001));

      // 错误顺序的等价演算：先套护栏、再 clamp
      final wrongOrder = clampFeedRatio(1.6);
      expect(wrongOrder, 1.34);
      expect(width / wrongOrder, greaterThan(maxH),
          reason: '这正是换序会造成的后果：图片区超出护栏上限');
    });
  });

  group('AC3 尺寸缺失与容错', () {
    test('无尺寸 → 按 1:1 预留', () {
      final r = resolveFeedImageAspect(size: null, width: 390, maxImageHeight: 600);
      expect(r, kFeedPlaceholderRatio);
    });

    test('1:1 预留到最终比例的跳动幅度有硬上下界', () {
      const width = 390.0;
      const maxH = 900.0; // 护栏不介入，只看 clamp 的作用
      final placeholder = width / kFeedPlaceholderRatio;
      final tallest = width /
          resolveFeedImageAspect(
              size: const ImageSize(9, 16), width: width, maxImageHeight: maxH);
      final shortest = width /
          resolveFeedImageAspect(
              size: const ImageSize(3, 1), width: width, maxImageHeight: maxH);
      expect(tallest / placeholder, closeTo(1 / 0.75, 0.001)); // 最多变高约三成
      expect(shortest / placeholder, closeTo(1 / 1.34, 0.001)); // 最多变矮约四分之一
    });

    test('宽高非正的尺寸当作测不出来', () {
      expect(const ImageSize(0, 100).isUsable, isFalse);
      expect(const ImageSize(100, 0).isUsable, isFalse);
      expect(const ImageSize(-1, 100).isUsable, isFalse);
      final r = resolveFeedImageAspect(
          size: const ImageSize(0, 0), width: 390, maxImageHeight: 600);
      expect(r, kFeedPlaceholderRatio);
    });

    group('尺寸数组解析', () {
      test('整字段缺失 → 空表', () {
        expect(ImageSize.listFromJson(null), isEmpty);
        expect(ImageSize.listFromJson('nonsense'), isEmpty);
      });

      /// 🔴 后端明说：与图片同序等长，**测不出来的位置为 null**。
      test('元素为 null → 该位置留 null', () {
        final list = ImageSize.listFromJson([
          {'w': 1200, 'h': 1600},
          null,
          {'w': 800, 'h': 800},
        ]);
        expect(list, hasLength(3));
        expect(list[0]!.ratio, 0.75);
        expect(list[1], isNull);
        expect(list[2]!.ratio, 1.0);
      });

      test('形状不对的元素也当 null，不抛异常', () {
        final list = ImageSize.listFromJson([
          {'w': '1200', 'h': 1600},
          {'w': 1200},
          <String, Object>{},
          42,
        ]);
        expect(list, hasLength(4));
        expect(list.every((e) => e == null), isTrue);
      });
    });
  });
}
