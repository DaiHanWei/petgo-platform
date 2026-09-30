import 'package:flutter/widgets.dart';

/// 内容表 `**…**` 加粗标记 → 富文本（V1.3.2 Story 2.4 · AC3；Story 2.5 复用）。
///
/// 只认**成对**的 `**`：不成对的那个原样输出、不吞字（内容写错时宁可露出星号，也不丢文案）。
List<InlineSpan> tsRichSpans(String text, {TextStyle bold = const TextStyle(fontWeight: FontWeight.w700)}) {
  final spans = <InlineSpan>[];
  var i = 0;
  while (i < text.length) {
    final open = text.indexOf('**', i);
    if (open < 0) break;
    final close = text.indexOf('**', open + 2);
    if (close < 0) break;
    if (open > i) spans.add(TextSpan(text: text.substring(i, open)));
    spans.add(TextSpan(text: text.substring(open + 2, close), style: bold));
    i = close + 2;
  }
  if (i < text.length) spans.add(TextSpan(text: text.substring(i)));
  return spans;
}

/// 去掉成对的 `**` 标记（给只接受纯文本的地方用，如确认抽屉正文）。
String tsPlainText(String text) =>
    tsRichSpans(text).map((s) => (s as TextSpan).text ?? '').join();

/// `**…**` 渲染成加粗的文本组件。
class TsRichText extends StatelessWidget {
  const TsRichText(this.text, {super.key, this.style, this.maxLines, this.overflow});

  final String text;
  final TextStyle? style;
  final int? maxLines;
  final TextOverflow? overflow;

  @override
  Widget build(BuildContext context) {
    return Text.rich(
      TextSpan(children: tsRichSpans(text)),
      style: style,
      maxLines: maxLines,
      overflow: overflow ?? (maxLines == null ? null : TextOverflow.clip),
    );
  }
}
