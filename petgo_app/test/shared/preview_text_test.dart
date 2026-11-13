import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/utils/preview_text.dart';

void main() {
  const style = TextStyle(fontSize: 14);
  String run(String t, {double w = 1000}) =>
      previewTextWithEllipsis(t, style: style, maxWidth: w, maxLines: 2);

  test('截断点在换行符上：第 2 行末补「…」（实机：对白体帖子看不出后面还有）', () {
    const t = 'cat: "mom mom what is this?"\nme: "no u had breakfast"\ncat: "can i eat this"\nme: "ok"';
    expect(run(t), 'cat: "mom mom what is this?"\nme: "no u had breakfast"…');
  });

  test('没超出两行：原样返回', () {
    expect(run('line one\nline two'), 'line one\nline two');
    expect(run('single line'), 'single line');
  });

  test('第 2 行是自然折行被截断：原样返回（交给系统 ellipsis）', () {
    final long = 'first\n${'word ' * 80}';
    expect(run(long), long);
  });

  test('第 2 行末尾的空格不留在「…」前', () {
    expect(run('a\nb   \nc'), 'a\nb…');
  });
}
