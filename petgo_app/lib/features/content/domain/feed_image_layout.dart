/// Feed 图片渲染的**三段口径**（V1.1.6 Story 3.3 · FR-71 / AD-6）。
///
/// ## 🛡 三段顺序不可调换
/// ① 取图片实际比例 → ② 收敛到 `0.75~1.34` **闭区间** → ③ 施加高度护栏。
///
/// 换序不是"风格差异"，是**功能性错误**：护栏是硬物理约束（图片区必须装得下），
/// 而 clamp 会把护栏算出来的比例又拉回区间内 —— 于是护栏形同虚设、下一条内容露不出来。
/// [resolveFeedImageAspect] 的实现顺序就是这条口径本身，改动前先看 `feed_image_layout_test.dart`。
///
/// ## 施加位置定死在客户端
/// 服务端**只下发原始宽高**，不下发比例、也不下发算好的高度。
/// 护栏依赖可视区高度（因机而异），服务端算不了；而服务端若先 clamp 一遍，
/// 客户端再 clamp 一遍就是**双重裁切**。后端有专门的契约测试守这条。
library;

/// 图片原始像素尺寸（对应后端 `ImageSize {w, h}`）。
///
/// ⚠️ **存量内容永远是 null** —— 尺寸列是 V1.1.6 Story 3.1 才加的，
/// 之前发布的内容没有也不会回填（AD-5）。占位兜底因此不可取消。
class ImageSize {
  const ImageSize(this.w, this.h);

  final int w;
  final int h;

  /// 宽高都为正才可用。后端已挡过离谱值，这里只做最后一道防线。
  bool get isUsable => w > 0 && h > 0;

  /// 宽 ÷ 高。竖图 < 1，横图 > 1。
  double get ratio => w / h;

  /// 单个尺寸对；形状不对一律返回 null（当作"这张测不出来"处理）。
  static ImageSize? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final w = raw['w'];
    final h = raw['h'];
    if (w is! int || h is! int) return null;
    final size = ImageSize(w, h);
    return size.isUsable ? size : null;
  }

  /// 尺寸数组。
  ///
  /// 🔴 必须容忍三件事，缺一就会在真实数据上炸：
  /// - **整字段缺失**（纯文字帖 / 老后端）→ 返回空表
  /// - **元素为 null**（那一张测不出来）→ 该位置留 null
  /// - **长度与图片数不一致** → 不在这里对齐，由取用方按下标安全取
  static List<ImageSize?> listFromJson(Object? raw) {
    if (raw is! List) return const [];
    return raw.map(ImageSize.fromJson).toList(growable: false);
  }
}

/// 容差区间下界。**闭区间含本值** —— 3:4 竖拍恰为 0.75 且最常见。
const double kFeedRatioMin = 0.75;

/// 容差区间上界。**闭区间含本值** —— 手机默认 4:3 横拍约 1.333。
const double kFeedRatioMax = 1.34;

/// 尺寸未知时的预留比例（存量内容）。
///
/// 选 1:1 的理由：因为有 clamp，最终比例必落在 `0.75~1.34`；
/// 从 1:1 出发，最坏也只是变高三成或变矮四分之一 —— **跳动有硬上下界**。
/// 若没有 clamp，一张长截图能把下面所有内容顶飞。
const double kFeedPlaceholderRatio = 1.0;

/// 高度护栏的**露出余量**（逻辑像素）。
///
/// ✅ **已由 2026-08-18 的小屏实机复核定死**（OA-1 闭合）。起点值原为 56，实测后改为 40。
///
/// 口径 = 条目间隔 25（分隔线上下留白）+ 下一条头像露出 15。
/// 32 的头像露出将近一半，"下面还有内容"这件事就已经不含糊了。
///
/// 🔴 **实机上量到的问题**（不是纸面推演）：360×640 小屏的滚动视口只有 470，
/// 原先的 56 + 把条目间隔重复计一遍，合计吃掉 223（视口的 47%），
/// 图片区上限被压到 247 —— 比改版前写死的 4:3（270）**还矮**。
/// 也就是说小屏上竖图被裁得比改版前更狠，与本次改版"少裁"的初衷正好相反。
/// 改为 40 且不再重复计间隔后上限回到 288，**在最小屏上也不会比改版前更差**。
///
/// 判据始终不是这个数字，而是可观测的那句：**下一条内容的顶部总能露出来**。
const double kFeedRevealMargin = 40;

/// 卡片里**图片之外**各块的高度，用于护栏的减法。
///
/// ⚠️ 架构文档只给了公式「可视区高度 − 条目其余部分高度 − 露出余量」，
/// 三个被减项**都没有定义取值口径**。这里把口径写死并逐项标注来源，
/// 实机复核时要调的就是这些常量。
class FeedCardMetrics {
  const FeedCardMetrics._();

  /// 作者行：32 头像 + 10 下边距。
  static const double authorRow = 42;

  /// 操作行：10 上边距 + 20 图标行。
  static const double actionRow = 30;

  /// 正文：10 上边距 + 2 行（正文最多 2 行，按满行估）。
  static const double bodyRows = 50;

  /// 时间行：5 上边距 + 15 行高。
  static const double timeRow = 20;

  /// 「条目其余部分高度」合计。
  ///
  /// ⚠️ **不含条目之间那 25 的间隔** —— 它已经算在 [kFeedRevealMargin] 里了。
  /// 两边各记一遍是实机复核发现的重复计数，会让护栏平白多吃掉 25。
  static const double chrome = authorRow + actionRow + bodyRows + timeRow;

  /// 给定可视区高度，得出图片区的高度上限。
  ///
  /// 可视区高度取**滚动视口的实际高度**（由列表容器用 `LayoutBuilder` 量得），
  /// 因此顶部标签行、底部导航栏都已经被扣掉了，不需要在这里再减一次。
  static double maxImageHeight(double viewportHeight) {
    final available = viewportHeight - chrome - kFeedRevealMargin;
    // 极端小视口（横屏、分屏）下别算出负数；留一个下限让图片仍可见。
    return available > 80 ? available : 80;
  }
}

/// 把比例收敛到 `[kFeedRatioMin, kFeedRatioMax]` **闭区间**。
///
/// 🛡 端点归属：**只有小于下界才夹到下界、只有大于上界才夹到上界**。
/// 端点因此天然落在区间内，全程不需要任何浮点相等判断 ——
/// 写成 `r > 0.75 && r < 1.34` 会把最常见的 3:4 竖拍排除在外、恰好裁掉最该保护的那种图。
double clampFeedRatio(double ratio) {
  if (!ratio.isFinite || ratio <= 0) return kFeedPlaceholderRatio;
  if (ratio < kFeedRatioMin) return kFeedRatioMin;
  if (ratio > kFeedRatioMax) return kFeedRatioMax;
  return ratio;
}

/// 三段口径的唯一出口：得出图片区最终使用的宽高比。
///
/// [size] 为 null（存量内容 / 尚未加载出真实尺寸）时按 [kFeedPlaceholderRatio] 预留。
/// [width] 是图片区的显示宽度（通栏 = 屏宽）。[maxImageHeight] 是护栏上限。
///
/// 🛡 **函数体内的三步就是口径本身，不要调换**：
/// 护栏放在最后，是因为它是硬约束；若先护栏后 clamp，clamp 会把护栏的结果拉回区间内，
/// 图片区随即超出上限 —— 护栏白做。
double resolveFeedImageAspect({
  required ImageSize? size,
  required double width,
  required double maxImageHeight,
}) {
  // ① 实际比例
  final raw = (size != null && size.isUsable) ? size.ratio : kFeedPlaceholderRatio;

  // ② 收敛到闭区间
  final clamped = clampFeedRatio(raw);

  // ③ 高度护栏：宽度固定，高度上限等价于**比例下限**（比例越小越高）。
  if (width <= 0 || !maxImageHeight.isFinite || maxImageHeight <= 0) return clamped;
  final floor = width / maxImageHeight;
  return clamped < floor ? floor : clamped;
}
