/// @ 候选人与我的关系（V1.4.2 social-topic · Story 5.2 · AC4 · PRD §4 FR-157）。
///
/// 服务端（Story 5.1）每个候选带 `relation`（小写）：命中多类时取排序靠前的一类
/// （关注的 → 粉丝 → 互动过的）。App 只拿它报 `mention_inserted.relation`，行上**不标**关系类型（UI E1）。
///
/// 写法同 [MentionContext]：枚举 + [wire]，埋点属性值只能取这三个之一，
/// 类型系统就是「属性里不含自由文本」那道红线。调用点照样写字面量：
/// ```dart
/// Analytics.capture('mention_inserted', {'context': MentionContext.post.wire, 'relation': c.relation.wire, ...});
/// ```
enum MentionRelation {
  /// 我关注的人。
  following('following'),

  /// 关注我的人（粉丝）。
  follower('follower'),

  /// 互动过的人（评论 / 点赞往来）。
  interacted('interacted');

  const MentionRelation(this.wire);

  /// 接口 / 埋点取值（小写）。
  final String wire;

  /// 未知 / 缺失 → [interacted]（仅兼容「App 先于 5.1 后端上线」的窗口，story 待确认第 3 条）。
  static MentionRelation parse(String? raw) {
    for (final MentionRelation r in values) {
      if (r.wire == raw) return r;
    }
    return interacted;
  }
}
