/// 灯箱手势的**判定纯逻辑**。V1.3.0 批次 A · Story 3.2（FR-115 · AD-A15.2/.3/.4）。
///
/// 三个手势（图内平移 / 翻页 / 下滑关闭）全是「手指往下或往旁边划」——
/// **不定优先级就必然互相抢**：下滑关闭挂在最外层，放大后想平移就把页面关了；
/// 横滑翻页与图内平移各自判定，放大态一滑就翻页。
///
/// 所以判定被抽成这个文件里的纯函数：widget 只负责把当前状态喂进来、按结果接线，
/// **顺序这件事有测试钉着**（AC5），改不动、也换不了位置。
library;

/// 一次拖拽该被谁接走。
enum LightboxGesture {
  /// 在图片内部平移（已放大）。
  panInsideImage,

  /// 翻到上/下一张。
  changePage,

  /// 下滑关闭。
  dismiss,
}

/// 🔴 **手势优先级，从高到低（AD-A15.2，顺序不可调换）**：
///
/// 1. `已放大 → 图内平移`
/// 2. `到达图片边缘继续拖 → 翻页`
/// 3. `未放大且下拖 → 关闭`
///
/// 这个顺序就是防冲突的全部依据：
/// - 第 1 条压住第 3 条 → 放大后下拖是看图的下半部分，**不是关闭**（AC2）；
/// - 第 2 条是第 1 条的唯一出口 → 放大态横滑先平移，**平移到头了才翻页**（AC4）；
/// - 第 3 条只在「没放大」时才轮得到 → 它天然不会和前两条打架（AC1）。
///
/// [zoomed] 当前图是否已放大（scale > 1）。
/// [horizontal] 这次拖拽的主方向是否为横向。
/// [atEdgeInDragDirection] 图片在**这次拖拽的方向上**是否已经贴边（拖不动了）。
LightboxGesture resolveLightboxGesture({
  required bool zoomed,
  required bool horizontal,
  required bool atEdgeInDragDirection,
}) {
  if (zoomed) {
    // 第 2 条：唯一能从「图内平移」手里抢走拖拽的情形 —— 横向且已经贴边。
    if (horizontal && atEdgeInDragDirection) return LightboxGesture.changePage;
    // 第 1 条：其余一律图内平移。**纵向也在这里** —— 放大态下拖是平移，不是关闭。
    return LightboxGesture.panInsideImage;
  }
  // 未放大：横滑翻页，纵拖关闭。
  return horizontal ? LightboxGesture.changePage : LightboxGesture.dismiss;
}

/// 下滑关闭的量化口径（AC1）。
class LightboxDismissMetrics {
  LightboxDismissMetrics._();

  /// 关闭阈值：下移超过视口高度的这个比例就松手即关。
  static const double travelRatio = 0.18;

  /// 快速下甩的速度阈值（px/s）：没到位移阈值但甩得够快，也算关闭 ——
  /// 否则用户「啪」地一甩反而关不掉，只能慢慢拖。
  static const double flingVelocity = 700;

  /// 背景最多淡到这个程度（不是全透明：全透明时正在拖的图会飘在详情页上，很怪）。
  static const double maxFade = 0.85;

  /// 松手是否应当关闭。
  static bool shouldDismiss({required double dy, required double velocity, required double viewportHeight}) {
    if (dy <= 0) return false; // 只认**下**拖；上拖一律回弹
    return dy > viewportHeight * travelRatio || velocity > flingVelocity;
  }

  /// 拖拽进度 0~1，驱动背景透明度与悬浮控件淡出。
  static double progress({required double dy, required double viewportHeight}) {
    if (dy <= 0 || viewportHeight <= 0) return 0;
    final p = dy / (viewportHeight * travelRatio);
    return p > 1 ? 1 : p;
  }
}

/// 单击关闭与双击缩放的仲裁窗口（AC6）。
///
/// 🔴 **不能直接用 `GestureDetector` 的 `onTap` + `onDoubleTap`**：两者同时注册时，
/// 单击要等双击判定超时（Flutter 的 `kDoubleTapTimeout`，300ms）才触发 ——
/// 而「单击关闭」是 Story 3.1 保留的既有修复（bug 20260701-192），
/// 变钝就是把一条既有体验做坏了。
///
/// 这里自己仲裁：第二次点击落在窗口内 → 双击缩放；窗口内没有第二次 → 关闭。
/// 窗口比系统默认短一截，手感更利落；再短就会把稍慢的双击误判成关闭。
const Duration kLightboxSingleTapDelay = Duration(milliseconds: 220);
