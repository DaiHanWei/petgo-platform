import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import '../../features/mention/domain/mention_draft.dart';
import 'body_tokens.dart';

/// 输入框里的话题 / @ 高亮（V1.4.2 social-topic · Story 4.4 · 架构 §2.1 · C4）。自写，不引第三方富文本库。
///
/// - 话题（[highlightTopics] 为真）：[topicsIn] 中 `effective` 的出现处 —— 前 5 个不同话题（同名重复处也高亮），
///   第 6 个起普通色；
/// - @：只高亮 [mentionRefs] 里点选插入过、且仍完整在文中的昵称（`mentionRanges(dropAmbiguous: false)`，
///   边界判定与 `MentionDraft.userIdsIn` 同一份 `MentionToken`）；手打的 `@abc` 不变色。
///
/// <h3>🔴 只改颜色</h3>
/// 高亮段只叠 `color: mint600`，**不改字号 / 字重 / 字距** —— 否则光标与选区按另一套字形宽度算，会错位。
///
/// <h3>🔴 输入法联想（composing）下划线</h3>
/// 照抄框架默认实现的判定（`withComposing && value.isComposingRangeValid`）与样式（underline）：
/// 把高亮端点与 composing 端点合起来切段，颜色与下划线可同时存在。
/// 没有任何高亮区间 → 直接 `super.buildTextSpan`（逐字节等同现状）。
/// 子段 `text` 依序拼接**必须恰好等于** `value.text`（多 / 少一个字符光标就错位）。
///
/// 不挂任何 recognizer（输入框里不可点）。
class TopicMentionTextController extends TextEditingController {
  TopicMentionTextController({
    required this.mentionRefs,
    this.highlightTopics = true,
    super.text,
  });

  /// 当前已点选插入的 @（发布页 = `PublishController.mentions.refs`，评论框 = `_mentions.refs`）。
  final List<MentionRef> Function() mentionRefs;

  /// 评论框传 false：评论不解析话题（D-19）。
  final bool highlightTopics;

  /// 当前文本里要高亮的区间（已排序、不重叠；@ 优先）。
  List<MarkRange> marksFor(String text) {
    final List<MarkRange> mentions = mentionRanges(
      text,
      mentionRefs().map((r) => (userId: r.userId, nickname: r.nickname)),
      dropAmbiguous: false,
    );
    final Iterable<TopicOccurrence> topics =
        highlightTopics ? topicsIn(text).where((t) => t.effective) : const <TopicOccurrence>[];
    return mergeMarks(mentions, topics);
  }

  @override
  TextSpan buildTextSpan({
    required BuildContext context,
    TextStyle? style,
    required bool withComposing,
  }) {
    final String t = value.text;
    final List<MarkRange> marks = marksFor(t);
    if (marks.isEmpty) {
      return super.buildTextSpan(context: context, style: style, withComposing: withComposing);
    }
    final bool composingOn = withComposing && value.isComposingRangeValid;
    final TextRange c = composingOn ? value.composing : TextRange.empty;
    final List<int> cuts = <int>{
      0,
      t.length,
      for (final m in marks) ...<int>[m.start, m.end],
      if (composingOn) ...<int>[c.start, c.end],
    }.where((i) => i >= 0 && i <= t.length).toList()
      ..sort();
    final List<TextSpan> children = <TextSpan>[];
    for (int i = 0; i + 1 < cuts.length; i++) {
      final int a = cuts[i];
      final int b = cuts[i + 1];
      if (a >= b) continue;
      final bool marked = marks.any((m) => m.start <= a && b <= m.end);
      final bool composing = composingOn && c.start <= a && b <= c.end;
      // 子段只带差量（颜色 / 下划线），其余继承父 style —— 字号字重与无高亮时完全一致。
      final TextStyle? seg = (marked || composing)
          ? TextStyle(
              color: marked ? AppColors.mint600 : null,
              decoration: composing ? TextDecoration.underline : null,
            )
          : null;
      children.add(TextSpan(text: t.substring(a, b), style: seg));
    }
    return TextSpan(style: style, children: children);
  }
}
