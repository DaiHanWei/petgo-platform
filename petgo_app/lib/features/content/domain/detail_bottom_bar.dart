/// 详情页固定底栏的**两态规则与度量**（V1.3.0 批次 A · Story 2.3 · AD-A13）。
///
/// ## 这不只是挪位置，是多了一个交互状态
/// UI 稿 D1 的原话：「比"挪位置"更深的交互状态变化」。不把规则定死，两个实现会对
/// 「打字时点赞按钮还在不在」「评论数在哪显示」给出不同答案 —— 而这两件事用户一眼就能看出不一致。
///
/// 规则本身很短，所以刻意抽成纯函数放在 domain 层：它必须**L0 可测**，
/// 不能只存在于某个 widget 的 build 方法里。
library;

/// 底栏右侧此刻该显示哪一组动作。
///
/// 🔴 **两态互斥，没有第三种组合**：不存在「点赞 + 发送同时在」，也不存在「两个都不在」。
/// 枚举只有两个值，这一点就在类型上成立了 —— 别改成两个 bool，那会立刻多出两种非法组合。
enum DetailBottomBarMode {
  /// 未聚焦且没输入 → 点赞 + 分享。这是默认态，用户进页面看到的就是它。
  actions,

  /// 已聚焦**或**已开始输入 → 发送。
  ///
  /// 「或」而不是「且」：用户点了输入框还没打字，此刻他显然是要评论，
  /// 这时候还摆着点赞分享就是干扰；反过来，输入框有草稿但焦点被别处抢走时，
  /// 发送键必须还在，否则他打的字没地方交。
  compose,
}

/// 按「是否聚焦」与「是否有内容」判定底栏形态。
///
/// [hasText] 指**去掉首尾空白后仍非空**（调用方负责 trim）—— 只敲了几个空格不算开始输入。
DetailBottomBarMode resolveBottomBarMode({
  required bool focused,
  required bool hasText,
}) =>
    (focused || hasText) ? DetailBottomBarMode.compose : DetailBottomBarMode.actions;

/// 底栏动作图标的度量（AC4）。
class DetailBarMetrics {
  const DetailBarMetrics._();

  /// 图标的**可见**尺寸。
  static const double iconSize = 19;

  /// 两个动作图标之间的间距。
  static const double iconGap = 22;

  /// 可点击热区的最小边长。
  ///
  /// 🔴 **热区是隐性扩展的，不改变可见大小**：19px 的图标直接当按钮，手指根本点不准
  /// （44 是移动端可达性的通行下限）。做法是给图标套一个 44×44 的透明命中框，
  /// 图标仍然画成 19 —— 而不是把图标画大。
  static const double minTapTarget = 44;
}
