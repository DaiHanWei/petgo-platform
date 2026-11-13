import 'package:flutter/widgets.dart';

/// 列表卡片正文截断时，保证读者看得出「后面还有」（修复线 2026-11-13，实机反馈）。
///
/// ## 为什么要它
/// Flutter 的 `TextOverflow.ellipsis` 只在**最后一行本身写不下**时才画「…」。
/// 若截断点恰好落在**换行符**上（第 2 行是一句完整短句、第 3 行另起一行），
/// 第 2 行完整显示、没有任何省略号 —— 看起来像帖子已经写完了。
/// 典型例子：多行对白体「🐈：…」「👩：…」，前两句都短，后面几句全被吞掉。
///
/// ## 做法
/// 用与实际渲染同样的样式、宽度、行数先排一遍版：没超出 → 原样返回；
/// 超出且最后一行是在换行符处结束 → 截到该行末尾并补「…」；
/// 最后一行是自然折行被截断 → 原样返回（系统 ellipsis 自己会画「…」）。
///
/// 只截取原文前缀，@ / 话题按名字在文本里匹配，前缀里的高亮位置不受影响。
String previewTextWithEllipsis(
  String text, {
  required TextStyle style,
  required double maxWidth,
  required int maxLines,
  TextScaler textScaler = TextScaler.noScaling,
  TextDirection textDirection = TextDirection.ltr,
}) {
  if (!text.contains('\n') || maxWidth <= 0 || !maxWidth.isFinite) return text;
  final painter = TextPainter(
    text: TextSpan(text: text, style: style),
    maxLines: maxLines,
    textDirection: textDirection,
    textScaler: textScaler,
  )..layout(maxWidth: maxWidth);
  try {
    if (!painter.didExceedMaxLines) return text;
    final lines = painter.computeLineMetrics();
    if (lines.isEmpty) return text;
    final last = lines.last;
    // 最后一行右端（行中线处）对应的文本位置 → 该行的边界。
    final pos = painter.getPositionForOffset(
        Offset(maxWidth, last.baseline - last.ascent / 2));
    final end = painter.getLineBoundary(pos).end;
    if (end <= 0 || end >= text.length) return text;
    // 只处理「在换行处被截断」；自然折行交给系统 ellipsis。
    final rest = text.substring(end);
    if (!rest.startsWith('\n') && !rest.startsWith('\r')) return text;
    return '${text.substring(0, end).trimRight()}…';
  } finally {
    painter.dispose();
  }
}
