import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/mention/domain/mention_draft.dart';
import 'package:tailtopia/shared/rich_text/topic_mention_text_controller.dart';

/// L0：输入框高亮控制器（V1.4.2 social-topic · Story 4.4 · AC3 / AC7 · 架构 §2.1）。
void main() {
  const base = TextStyle(fontSize: 14, height: 1.6, color: AppColors.ink, fontWeight: FontWeight.w400);

  late BuildContext ctx;
  Future<void> grabContext(WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp(home: Builder(builder: (c) {
      ctx = c;
      return const SizedBox();
    })));
  }

  /// 叶子段（文本, 子段自带的高亮色, 是否下划线）。根上的 style 是父基础样式，不算「高亮」。
  List<(String, Color?, bool)> leaves(TextSpan root) {
    if (root.children == null) {
      return [(root.text ?? '', null, root.style?.decoration == TextDecoration.underline)];
    }
    return [
      for (final c in root.children!)
        ((c as TextSpan).text ?? '', c.style?.color, c.style?.decoration == TextDecoration.underline),
    ];
  }

  String joined(TextSpan root) =>
      root.children == null ? (root.text ?? '') : root.children!.map((c) => (c as TextSpan).text).join();

  TopicMentionTextController ctl(String text,
          {List<MentionRef> refs = const [], bool topics = true, TextRange composing = TextRange.empty}) =>
      TopicMentionTextController(mentionRefs: () => refs, highlightTopics: topics)
        ..value = TextEditingValue(
            text: text, selection: TextSelection.collapsed(offset: text.length), composing: composing);

  TextSpan build(TopicMentionTextController c, {bool withComposing = true}) =>
      c.buildTextSpan(context: ctx, style: base, withComposing: withComposing);

  testWidgets('子段文本拼接 == value.text（多种组合）', (tester) async {
    await grabContext(tester);
    const refs = [MentionRef(userId: 1, nickname: 'Rara')];
    final cases = <TopicMentionTextController>[
      ctl('halo biasa'),
      ctl('#kucing lucu'),
      ctl('hai @Rara ya', refs: refs),
      ctl('@Rara #kucing dan #lucu', refs: refs),
      ctl('#a1 #b2 #c3 #d4 #e5 #f6 lagi'),
      ctl('#kucing lucu', composing: const TextRange(start: 3, end: 10)),
      ctl('@Rara #kucing', refs: refs, composing: const TextRange(start: 2, end: 9)),
    ];
    for (final c in cases) {
      expect(joined(build(c)), c.text, reason: c.text);
    }
  });

  testWidgets('composing 与话题交叠：颜色与下划线可同时存在', (tester) async {
    await grabContext(tester);
    final c = ctl('#kucing lucu', composing: const TextRange(start: 3, end: 10));
    expect(leaves(build(c)), [
      ('#ku', AppColors.mint600, false),
      ('cing', AppColors.mint600, true),
      (' lu', null, true),
      ('cu', null, false),
    ]);
    // withComposing: false → 全文无下划线。
    expect(leaves(build(c, withComposing: false)).any((l) => l.$3), isFalse);
  });

  testWidgets('composing 为空 / 折叠（无联想区间）→ 无下划线且不抛', (tester) async {
    // ⚠️ 越界的 composing 在 `TextEditingController.value=` 处就被框架断言拒掉（进不到 buildTextSpan）；
    //    这里覆盖能进来的两种「无效」形态：TextRange.empty（-1,-1）与折叠区间。
    await grabContext(tester);
    for (final composing in [TextRange.empty, const TextRange.collapsed(3)]) {
      final c = ctl('#kucing', composing: composing);
      late TextSpan span;
      expect(() => span = build(c), returnsNormally);
      expect(leaves(span).any((l) => l.$3), isFalse);
      expect(joined(span), '#kucing');
    }
  });

  testWidgets('无任何高亮 → 与 TextEditingController 默认结果相等（含 composing）', (tester) async {
    await grabContext(tester);
    for (final composing in [TextRange.empty, const TextRange(start: 2, end: 5)]) {
      final c = ctl('halo #a biasa', composing: composing);
      final plain = TextEditingController.fromValue(c.value);
      expect(build(c), plain.buildTextSpan(context: ctx, style: base, withComposing: true));
    }
  });

  testWidgets('只改颜色：高亮段不带字号 / 字重（全部继承父 style）', (tester) async {
    await grabContext(tester);
    final span = build(ctl('ayo #kucing'));
    expect(span.style, base);
    final hit = span.children!.cast<TextSpan>().firstWhere((s) => s.style?.color == AppColors.mint600);
    expect(hit.style!.fontSize, isNull);
    expect(hit.style!.fontWeight, isNull);
    expect(hit.style!.letterSpacing, isNull);
    expect(hit.recognizer, isNull);
  });

  testWidgets('第 6 个话题无颜色；highlightTopics: false 时话题无颜色', (tester) async {
    await grabContext(tester);
    final six = leaves(build(ctl('#a1 #b2 #c3 #d4 #e5 #f6')));
    expect(six.where((l) => l.$2 == AppColors.mint600).map((l) => l.$1), ['#a1', '#b2', '#c3', '#d4', '#e5']);
    expect(six.firstWhere((l) => l.$1.contains('#f6')).$2, isNull);
    expect(leaves(build(ctl('#kucing', topics: false))).any((l) => l.$2 != null), isFalse);
  });

  testWidgets('@：点选的有颜色；删掉其中一个字后无颜色；手打 @abc 无颜色', (tester) async {
    await grabContext(tester);
    const refs = [MentionRef(userId: 1, nickname: 'Rara')];
    expect(leaves(build(ctl('hai @Rara', refs: refs))).last, ('@Rara', AppColors.mint600, false));
    expect(leaves(build(ctl('hai @Rar', refs: refs))).any((l) => l.$2 != null), isFalse);
    expect(leaves(build(ctl('hai @abc', refs: refs))).any((l) => l.$2 != null), isFalse);
  });

  testWidgets('AC7 混排：6 个话题 + 点选 @ + 手打 @abc', (tester) async {
    await grabContext(tester);
    final draft = MentionDraft();
    const before = '#a1 #b2 #c3 #d4 #e5 #f6 @';
    final ins = draft.insert(before, MentionDraft.queryAt(before, before.length)!, 7, 'Rara')!;
    final text = '${ins.text}dan @abc';
    final c = TopicMentionTextController(mentionRefs: () => draft.refs)..text = text;
    final colored = leaves(build(c)).where((l) => l.$2 == AppColors.mint600).map((l) => l.$1).toList();
    expect(colored, ['#a1', '#b2', '#c3', '#d4', '#e5', '@Rara']);
    expect(draft.userIdsIn(text), [7]);
  });
}
