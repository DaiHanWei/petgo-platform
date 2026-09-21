/// 一处 @ 落在哪种内容里（V1.3.0 batch-b1 Story 3.5 · AC4 的 `context` 属性）。
///
/// <h3>🔴 为什么是枚举而不是 String</h3>
/// AC4 的红线是「属性里**不含**任何昵称、正文等自由文本」，而 Dev Notes 又明说
/// 「**源头就不要传，不要依赖兜底**」。用 String 的话，哪天有人把昵称插值进来
/// （`'post:$nickname'`）既编译得过、也过得了埋点层的脱敏 ——
/// `context` 这个键不在任何黑名单上（code-review 2026-09-15）。
/// 换成枚举之后，**类型系统就是那道红线**：这里只有两个值可选。
///
/// <h3>⚠️ 事件名与属性 Map 刻意写在调用点、用字面量</h3>
/// 埋点守卫（`test/analytics/v112_events_test.dart` 的命名检查、
/// `v140_events_inventory_test.dart` 的 NFR-5 全局体检）是**正则扫源码里的
/// `Analytics.capture('<字面量>', {'<键>': …})`**。抽成常量看着更整齐，
/// 代价是这两个事件从此对所有守卫隐身 —— 而它们正是「别把 PII 传进埋点」那道网。
/// 所以调用点长这样，别"顺手"再抽回常量：
/// ```dart
/// Analytics.capture('mention_tapped', {'context': MentionContext.post.wire});
/// ```
enum MentionContext {
  /// 正文（发布页输入框 / 帖子正文里的 @）。
  post('post'),

  /// 评论（评论输入框 / 评论正文里的 @）。
  comment('comment');

  const MentionContext(this.wire);

  /// 埋点属性值（snake_case，与看板口径一致）。
  final String wire;
}
