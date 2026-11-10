import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/mention/domain/mention_context.dart';
import 'package:tailtopia/features/mention/domain/mention_view.dart';
import 'package:tailtopia/shared/rich_text/rich_body_text.dart';

/// L0：正文展示组件（V1.4.2 social-topic · Story 4.4 · AC2 · C5 / D-19）。
void main() {
  List<TextSpan> spansOf(WidgetTester tester) {
    final rich = tester.widget<RichText>(
        find.descendant(of: find.byType(RichBodyText), matching: find.byType(RichText)).first);
    final out = <TextSpan>[];
    void walk(InlineSpan span) {
      if (span is TextSpan) {
        if (span.text != null) out.add(span);
        for (final child in span.children ?? const <InlineSpan>[]) {
          walk(child);
        }
      }
    }
    walk(rich.text);
    return out;
  }

  Future<(List<int>, List<String>)> pump(
    WidgetTester tester,
    String text, {
    List<String> topicNames = const [],
    List<MentionView> mentions = const [],
  }) async {
    final users = <int>[];
    final topics = <String>[];
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: RichBodyText(
          text: text,
          mentions: mentions,
          topicNames: topicNames,
          onTapUser: users.add,
          onTapTopic: topics.add,
          mentionContext: MentionContext.post,
        ),
      ),
    ));
    await tester.pumpAndSettle();
    return (users, topics);
  }

  testWidgets('topicNames 内的话题：mint600 + w600、可点，回调收到标准名', (tester) async {
    final (_, topics) = await pump(tester, 'halo #KucingOren dan #lain', topicNames: ['kucingoren']);
    final hits = spansOf(tester).where((s) => s.recognizer != null).toList();
    // 正文按作者原写法显示（C8）。
    expect(hits.map((s) => s.text), ['#KucingOren']);
    expect(hits.single.style!.color, AppColors.mint600);
    expect(hits.single.style!.fontWeight, FontWeight.w600);
    expect(hits.single.style!.decoration, isNot(TextDecoration.underline)); // 无下划线
    await tester.tapOnText(find.textRange.ofSubstring('#KucingOren'));
    expect(topics, ['kucingoren']);
  });

  testWidgets('不在 topicNames 的话题（禁用 / 未关联）→ 普通文字、无 recognizer', (tester) async {
    await pump(tester, 'halo #dilarang', topicNames: const []);
    expect(find.text('halo #dilarang'), findsOneWidget); // 纯 Text
    await pump(tester, 'halo #dilarang #oke', topicNames: ['oke']);
    expect(spansOf(tester).where((s) => s.recognizer != null).map((s) => s.text), ['#oke']);
  });

  testWidgets('同名重复处都高亮；@ 行为回归（点击报 mention_tapped）', (tester) async {
    final events = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    addTearDown(() => Analytics.debugCaptureSink = null);
    final (users, topics) = await pump(tester, '@Aurel #kucing ya #Kucing',
        topicNames: ['kucing'], mentions: const [MentionView(userId: 42, nickname: 'Aurel', tappable: true)]);
    expect(spansOf(tester).where((s) => s.recognizer != null).map((s) => s.text),
        ['@Aurel', '#kucing', '#Kucing']);
    await tester.tapOnText(find.textRange.ofSubstring('@Aurel'));
    expect(users, [42]);
    expect(topics, isEmpty);
    expect(events.single.$1, 'mention_tapped');
    expect(events.single.$2, {'context': 'post'});
  });

  testWidgets('NFD 正文（e + U+0301）能对上服务端 NFC 名，点击回调给服务端名', (tester) async {
    const nfd = 'enak #Cafe\u0301 ya';
    final (_, topics) = await pump(tester, nfd, topicNames: ['caf\u00e9']);
    final hits = spansOf(tester).where((s) => s.recognizer != null).toList();
    expect(hits.map((s) => s.text), ['#Cafe\u0301']); // 按作者原写法显示
    await tester.tapOnText(find.textRange.ofSubstring('#Cafe\u0301'));
    expect(topics, ['caf\u00e9']);
  });

  testWidgets('希腊词尾 Σ / 土耳其 İ：App 标准名与服务端一致即可高亮', (tester) async {
    final (_, topics) = await pump(tester, '#ΟΔΟΣ #İzmir', topicNames: ['οδος', 'i\u0307zmir']);
    expect(spansOf(tester).where((s) => s.recognizer != null).map((s) => s.text), ['#ΟΔΟΣ', '#İzmir']);
    await tester.tapOnText(find.textRange.ofSubstring('#ΟΔΟΣ'));
    expect(topics, ['οδος']);
  });

  testWidgets('onTapTopic 为 null（评论区 / MentionText）→ 不解析话题', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: RichBodyText(
          text: 'halo #kucing',
          topicNames: const ['kucing'],
          onTapUser: (_) {},
          mentionContext: MentionContext.comment,
        ),
      ),
    ));
    expect(find.text('halo #kucing'), findsOneWidget);
  });

  testWidgets('🔴 无关重建不重建 recognizer；topicNames 真变了才换', (tester) async {
    final rebuild = ValueNotifier<int>(0);
    addTearDown(rebuild.dispose);
    var names = <String>['kucing'];
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: ValueListenableBuilder<int>(
          valueListenable: rebuild,
          builder: (context, tick, _) => Column(children: [
            Text('tick $tick'),
            RichBodyText(
              text: 'halo #kucing #lucu',
              topicNames: List<String>.of(names), // 每次重建都是新实例，内容不变
              onTapTopic: (_) {},
              mentionContext: MentionContext.post,
            ),
          ]),
        ),
      ),
    ));
    final first = spansOf(tester).firstWhere((s) => s.recognizer != null).recognizer;
    rebuild.value = 1;
    await tester.pump();
    expect(spansOf(tester).firstWhere((s) => s.recognizer != null).recognizer, same(first));
    names = ['kucing', 'lucu'];
    rebuild.value = 2;
    await tester.pump();
    final after = spansOf(tester).where((s) => s.recognizer != null).toList();
    expect(after.map((s) => s.text), ['#kucing', '#lucu']);
    expect(after.first.recognizer, isNot(same(first)));
  });
}
