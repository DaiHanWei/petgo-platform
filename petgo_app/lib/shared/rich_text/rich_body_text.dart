import 'package:flutter/foundation.dart' show listEquals, setEquals;
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';

import '../../core/analytics/analytics.dart';
import '../../core/theme/colors.dart';
import '../../features/mention/domain/mention_context.dart';
import '../../features/mention/domain/mention_view.dart';
import 'body_tokens.dart';

/// 正文展示：@ 与话题高亮可点（V1.4.2 social-topic · Story 4.4 · C5 · D-19，取代只认 @ 的 `MentionText._spans`）。
///
/// - @：只高亮后端说可点的（[MentionView.highlightable]）、同名多人一律不高亮；点击报 `mention_tapped {context}` 后
///   [onTapUser]。[onTapUser] 为 null → @ 不高亮。
/// - 话题：[topicsIn] 里标准名命中 [topicNames]（精确，或 NFD 容错 `matchServerTopicName`）（服务端下发，仅 ACTIVE / HIDDEN）的出现处高亮可点 → [onTapTopic]（标准名）；
///   其余（禁用、第 6 个、未关联）普通文字。[onTapTopic] 为 null → 话题不解析（评论区）。
/// - @ 与 # 重叠时 @ 优先（[mergeMarks]）。
/// - 正文按作者原写法显示（`#KucingOren` 照样高亮）；样式 mint600 + w600、无下划线。
///
/// <h3>🔴 `TapGestureRecognizer` 只在 [State] 生命周期里建与放，绝不在 `build` 里</h3>
/// `dispose()` 会 `resolve(rejected)`：在 `build` 里换 recognizer = 手指按下到抬起之间任何一次重建
/// （provider 刷新、键盘 inset、祖先 setState）都让正在竞技的手势判负、外层手势反而赢
/// （见 `MentionText` 历史注释，code-review 2026-09-15）。所以只在内容真的变了时（initState / didUpdateWidget）重建。
class RichBodyText extends StatefulWidget {
  const RichBodyText({
    super.key,
    required this.text,
    this.mentions = const <MentionView>[],
    this.topicNames = const <String>[],
    this.onTapUser,
    this.onTapTopic,
    required this.mentionContext,
    this.style,
    this.maxLines,
    this.overflow,
  });

  final String text;

  /// 后端下发的 @ 投影。
  final List<MentionView> mentions;

  /// 本帖关联的话题标准名（服务端下发）；只有命中它的话题才高亮。
  final Iterable<String> topicNames;

  final void Function(int userId)? onTapUser;
  final void Function(String topicName)? onTapTopic;

  /// 埋点 `mention_tapped.context`（正文 / 评论）。
  final MentionContext mentionContext;

  final TextStyle? style;
  final int? maxLines;
  final TextOverflow? overflow;

  @override
  State<RichBodyText> createState() => _RichBodyTextState();
}

class _RichBodyTextState extends State<RichBodyText> {
  List<_Hit> _hits = const <_Hit>[];

  @override
  void initState() {
    super.initState();
    _rebuildHits();
  }

  @override
  void didUpdateWidget(RichBodyText oldWidget) {
    super.didUpdateWidget(oldWidget);
    // ⚠️ 只在内容真的变了时重算（回调本身换了不必：recognizer 闭包里读的是 widget.*）。
    //    回调「有无」变了要重算：它决定哪类标记参与高亮。
    if (widget.text != oldWidget.text ||
        !listEquals(widget.mentions, oldWidget.mentions) ||
        !setEquals(widget.topicNames.toSet(), oldWidget.topicNames.toSet()) ||
        (widget.onTapUser == null) != (oldWidget.onTapUser == null) ||
        (widget.onTapTopic == null) != (oldWidget.onTapTopic == null)) {
      _rebuildHits();
    }
  }

  @override
  void dispose() {
    _disposeHits();
    super.dispose();
  }

  void _disposeHits() {
    for (final hit in _hits) {
      hit.recognizer.dispose();
    }
    _hits = const <_Hit>[];
  }

  void _rebuildHits() {
    _disposeHits();
    final String text = widget.text;
    final List<MarkRange> mentions = widget.onTapUser == null
        ? const <MarkRange>[]
        : mentionRanges(
            text,
            widget.mentions
                .where((m) => m.highlightable)
                .map((m) => (userId: m.userId, nickname: m.nickname!)),
            dropAmbiguous: true,
          );
    final Set<String> names = widget.topicNames.toSet();
    // App 的 norm 对到服务端名（精确 → NFD 容错兜底，规则只在 body_tokens.dart）；点击回调给服务端名。
    final List<TopicOccurrence> topics = <TopicOccurrence>[];
    final Map<int, String> serverNameAt = <int, String>{};
    if (widget.onTapTopic != null && names.isNotEmpty) {
      for (final t in topicsIn(text)) {
        final String? server = matchServerTopicName(t.norm, names);
        if (server == null) continue;
        topics.add(t);
        serverNameAt[t.start] = server;
      }
    }
    _hits = mergeMarks(mentions, topics).map((m) {
      final TapGestureRecognizer r = TapGestureRecognizer();
      if (m.isMention) {
        final int userId = m.userId!;
        r.onTap = () => _onTapUser(userId);
      } else {
        final String name = serverNameAt[m.start] ?? m.topicNorm!;
        r.onTap = () => widget.onTapTopic?.call(name);
      }
      return _Hit(start: m.start, end: m.end, recognizer: r);
    }).toList(growable: false);
  }

  void _onTapUser(int userId) {
    // 属性只有 context，不传昵称 / 正文片段。事件名与属性 Map 写字面量（埋点守卫正则扫源码）。
    Analytics.capture('mention_tapped', {'context': widget.mentionContext.wire});
    widget.onTapUser?.call(userId);
  }

  @override
  Widget build(BuildContext context) {
    // 一处可点都没有 → 普通 Text（不白建富文本、不挂 recognizer）。
    if (_hits.isEmpty) {
      return Text(widget.text,
          style: widget.style, maxLines: widget.maxLines, overflow: widget.overflow);
    }
    final TextStyle base = widget.style ?? DefaultTextStyle.of(context).style;
    final TextStyle highlight = base.copyWith(color: AppColors.mint600, fontWeight: FontWeight.w600);
    final List<InlineSpan> spans = <InlineSpan>[];
    int cursor = 0;
    for (final hit in _hits) {
      if (hit.start > cursor) {
        spans.add(TextSpan(text: widget.text.substring(cursor, hit.start)));
      }
      // 品牌色 + 中等字重；刻意不加下划线（像链接广告）。
      spans.add(TextSpan(
        text: widget.text.substring(hit.start, hit.end),
        style: highlight,
        recognizer: hit.recognizer,
      ));
      cursor = hit.end;
    }
    if (cursor < widget.text.length) {
      spans.add(TextSpan(text: widget.text.substring(cursor)));
    }
    return Text.rich(
      TextSpan(children: spans),
      style: widget.style,
      maxLines: widget.maxLines,
      overflow: widget.overflow,
    );
  }
}

class _Hit {
  const _Hit({required this.start, required this.end, required this.recognizer});

  final int start;
  final int end;
  final TapGestureRecognizer recognizer;
}
