import 'package:flutter/material.dart';

import '../../../shared/rich_text/rich_body_text.dart';
import '../domain/mention_context.dart';
import '../domain/mention_view.dart';

/// 把「正文里的 @昵称」渲染成可点高亮片段（V1.3.0 batch-b1 Story 3.3 · AC1/AC2）。
///
/// <h3>🔴 只高亮后端说可点的那些（AC3/AC4）</h3>
/// 本组件**不做任何拉黑 / 注销判定** —— 那是服务端的事（`MentionView.tappable`）。
/// 不可点的 @ 原样当普通文字渲染：不变色、点不动。
///
/// <h3>🔴 `TapGestureRecognizer` 只在 [State] 生命周期里建与放，绝不在 `build` 里</h3>
/// `OneSequenceGestureRecognizer.dispose()` 会 `resolve(rejected)`。所以在 `build` 里
/// dispose 上一批 recognizer 是个**间歇性 bug**：手指按下到抬起之间只要发生一次重建
/// （provider 刷新、键盘 inset 变化、祖先 setState），正在竞技的那个 recognizer 被判负，
/// **外层手势反而赢** —— Feed 卡片变成进详情页、评论行变成弹回复框，`onTapUser`
/// 根本不触发（code-review 2026-09-15）。
/// 所以切片与 recognizer 都在 `initState` / `didUpdateWidget` 里算好缓存，
/// `build` 只负责拼 span。
///
/// <h3>⚠️ 定位靠「当前昵称」，所以改名过的 @ 会退化成普通文字</h3>
/// 正文里存的是写入那一刻的「@旧昵称」，而后端下发的是**当前**昵称（AC1）。
/// 两者不一致时这里找不到那一段，于是不高亮 —— 内容照常显示，只是点不动。
/// 这与 Story 3.5「存量 @ 不回溯解析」渲染出来的样子是同一种（已知取舍，
/// 见 story Completion Notes 的 🔸 一条）。
///
/// <h3>V1.4.2 social-topic Story 4.4（D-19）：本类改为 [RichBodyText] 的薄包装</h3>
/// 切片、recognizer 生命周期与 `mention_tapped` 埋点都搬进了 [RichBodyText]（定位逻辑在
/// `shared/rich_text/body_tokens.dart` 的 `mentionRanges`）。构造参数不变；**不解析话题**
/// （`topicNames` 恒空、`onTapTopic` 恒 null）—— 评论区继续用它（D-19：评论不解析话题）。
class MentionText extends StatelessWidget {
  const MentionText({
    super.key,
    required this.text,
    required this.mentions,
    required this.onTapUser,
    required this.mentionContext,
    this.style,
    this.maxLines,
    this.overflow,
  });

  final String text;

  /// 后端下发的 @ 投影（空表 = 这段文字里没有可点的 @，退化成纯 [Text]）。
  final List<MentionView> mentions;

  /// 点了某个 @ → 去那个人的公开主页（AC2）。
  final void Function(int userId) onTapUser;

  /// 这段文字是正文还是评论（Story 3.5 · AC4 的 `context` 属性）；埋点由 [RichBodyText] 自己发。
  final MentionContext mentionContext;

  final TextStyle? style;
  final int? maxLines;
  final TextOverflow? overflow;

  @override
  Widget build(BuildContext context) => RichBodyText(
        text: text,
        mentions: mentions,
        onTapUser: onTapUser,
        mentionContext: mentionContext,
        style: style,
        maxLines: maxLines,
        overflow: overflow,
      );
}
