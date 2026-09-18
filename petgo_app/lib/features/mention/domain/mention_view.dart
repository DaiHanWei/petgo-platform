/// 正文 / 评论里一处 @ 的渲染投影（V1.3.0 batch-b1 Story 3.3 · AC1/AC3/AC4）。
///
/// 对应后端 `MentionView`。
///
/// 🔴 **可点与否是后端算好的，客户端只照做**（story Dev Notes）：拉黑关系与注销状态
/// 都不该让客户端自己算 —— 客户端判定只是"看不见"，而且两处判定迟早分叉。
/// 所以这里**没有**任何 `if 被拉黑` 的逻辑，只有 [tappable] 这个开关。
///
/// [nickname] 是**当前**昵称（AC1：对方改名后自动跟着变）；不可点时后端不下发昵称，
/// 此处为 null，那一处 @ 按普通文字渲染。
class MentionView {
  const MentionView({required this.userId, this.nickname, required this.tappable});

  final int userId;
  final String? nickname;
  final bool tappable;

  /// 这一处 @ 能不能高亮 + 点击。
  ///
  /// ⚠️ 必须同时要求 [tappable] 与非空昵称：没有昵称就没法在正文里定位那一段文字，
  /// 高亮无处可施。
  bool get highlightable => tappable && nickname != null && nickname!.isNotEmpty;

  /// 值相等 —— `MentionText` 用它判断「这批 @ 真的换了没有」。
  ///
  /// ⚠️ 不能省：少了它，每次父级重建都被当成"内容变了"，而那一刻 recognizer 会跟着
  /// 重建 —— 正在竞技的手势被判负（见 `MentionText` 类注释那一条）。
  @override
  bool operator ==(Object other) =>
      other is MentionView &&
      other.userId == userId &&
      other.nickname == nickname &&
      other.tappable == tappable;

  @override
  int get hashCode => Object.hash(userId, nickname, tappable);

  factory MentionView.fromJson(Map<String, dynamic> json) => MentionView(
        userId: json['userId'] as int,
        nickname: json['nickname'] as String?,
        tappable: (json['tappable'] ?? false) as bool,
      );

  /// 解析后端的 `mentions` 字段。空表不下发（后端 NON_NULL），所以 null 是常态。
  static List<MentionView> listFromJson(Object? raw) => raw is List
      ? raw
          .map((e) => MentionView.fromJson((e as Map).cast<String, dynamic>()))
          .toList(growable: false)
      : const <MentionView>[];
}
