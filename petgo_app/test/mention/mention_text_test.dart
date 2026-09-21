import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/mention/domain/mention_context.dart';
import 'package:tailtopia/features/mention/domain/mention_view.dart';
import 'package:tailtopia/features/mention/presentation/mention_text.dart';

/// L0：@ 的高亮与点击（V1.3.0 batch-b1 Story 3.3 · AC1/AC2/AC3/AC4）。
///
/// <h3>🔴 本组件里没有任何拉黑 / 注销判定 —— 那是服务端的事</h3>
/// 所以下面的用例都是「给它一个后端已经算好的 [MentionView]，看它照做了没有」，
/// 而**不是**「构造一个拉黑关系看它算得对不对」。后端那一半由
/// `MentionViewServiceTest` 覆盖。
void main() {
  Future<List<int>> pumpText(
    WidgetTester tester,
    String text,
    List<MentionView> mentions, {
    List<int>? sink,
  }) async {
    final taps = sink ?? <int>[];
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: MentionText(
          text: text,
          mentions: mentions,
          onTapUser: taps.add,
          mentionContext: MentionContext.post,
        ),
      ),
    ));
    await tester.pumpAndSettle();
    return taps;
  }

  /// 把 RichText 的整棵 span 树拍平。
  ///
  /// ⚠️ `Text.rich(..., style: ...)` 会在外面再包一层带 style 的 TextSpan，
  /// 所以 `children` 的第一层只有一个元素 —— 直接断言它的长度会看错。
  List<TextSpan> spansOf(WidgetTester tester) {
    // ⚠️ 只看 MentionText 自己那棵 —— 同屏可能还有别的 Text（它们也是 RichText）。
    final rich = tester.widget<RichText>(find
        .descendant(of: find.byType(MentionText), matching: find.byType(RichText))
        .first);
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

  MentionView tappable(int id, String nickname) =>
      MentionView(userId: id, nickname: nickname, tappable: true);

  /// 后端对「拉黑（AC3）/ 注销（AC4）」下发的形态：只有 id，没有昵称。
  MentionView blocked(int id) => MentionView(userId: id, tappable: false);

  group('AC1 高亮', () {
    testWidgets('可点的 @ 走富文本（切成了多个片段）', (tester) async {
      await pumpText(tester, 'halo @Aurel apa kabar', [tappable(42, 'Aurel')]);
      final spans = spansOf(tester);
      expect(spans.map((s) => s.text), ['halo ', '@Aurel', ' apa kabar']);
      final hit = spans[1];
      expect(hit.recognizer, isNotNull);
      // 高亮 = 变色 + 加粗（刻意不加下划线：正文里一段下划线更像链接广告）。
      expect(hit.style!.fontWeight, FontWeight.w600);
      expect(hit.style!.decoration, isNot(TextDecoration.underline));
    });

    testWidgets('一段文字里的多处 @ 都高亮', (tester) async {
      await pumpText(tester, '@A dan @B', [tappable(1, 'A'), tappable(2, 'B')]);
      expect(spansOf(tester).where((s) => s.recognizer != null).map((s) => s.text),
          ['@A', '@B']);
    });

    testWidgets('🔴 昵称是别的昵称前缀时，长的先匹配', (tester) async {
      // 短的先来会把「@Ana」切成「@An」+「a」，点进去是**错的人**。
      final taps = await pumpText(tester, 'halo @Ana', [tappable(1, 'An'), tappable(2, 'Ana')]);
      expect(spansOf(tester).where((s) => s.recognizer != null).map((s) => s.text), ['@Ana']);
      await tester.tapOnText(find.textRange.ofSubstring('@Ana'));
      await tester.pumpAndSettle();
      expect(taps, [2]);
    });
  });

  group('AC2 点击跳主页', () {
    testWidgets('点高亮片段 → 回调带的是 userId', (tester) async {
      final taps = await pumpText(tester, 'halo @Aurel', [tappable(42, 'Aurel')]);
      await tester.tapOnText(find.textRange.ofSubstring('@Aurel'));
      await tester.pumpAndSettle();
      expect(taps, [42]);
    });
  });

  group('AC3/AC4 不可点的 @ 就是普通文字', () {
    testWidgets('后端说不可点 → 退化成纯 Text，一个 recognizer 都不挂', (tester) async {
      await pumpText(tester, 'halo @Aurel', [blocked(42)]);
      // 🔴 纯 Text 而不是富文本：没有可高亮的片段就不该白建 span 树、不挂 recognizer。
      // 顺带保证既有那些 find.text(comment.body) 的回归用例继续成立。
      expect(find.text('halo @Aurel'), findsOneWidget);
      expect(spansOf(tester).where((s) => s.recognizer != null), isEmpty);
    });

    testWidgets('tappable=true 但后端没给昵称 → 同样不高亮', (tester) async {
      // 没有昵称就没法在正文里定位那一段文字，高亮无处可施。
      await pumpText(tester, 'halo @Aurel', [
        const MentionView(userId: 42, nickname: null, tappable: true),
      ]);
      expect(find.text('halo @Aurel'), findsOneWidget);
    });

    testWidgets('一处可点一处不可点 → 只高亮可点那处', (tester) async {
      final taps = await pumpText(
          tester, '@A dan @B', [tappable(1, 'A'), blocked(2)]);
      expect(spansOf(tester).where((s) => s.recognizer != null).map((s) => s.text), ['@A']);
      await tester.tapOnText(find.textRange.ofSubstring('@A'));
      await tester.pumpAndSettle();
      expect(taps, [1]);
    });
  });

  group('🔴 code-review 2026-09-15 修掉的三条', () {
    testWidgets('前缀昵称：可点的那个是短的、长的不可点 → 短的不许抢前缀', (tester) async {
      // 靠「长的先排」挡不住这一种：长的（Ana）被拉黑后压根不在候选里，
      // 短的（An）就会把「@Ana」切成 @An + a，点进去是**错的人**，还多出一个孤零零的 a。
      final taps = await pumpText(tester, 'halo @Ana apa kabar',
          [tappable(1, 'An'), blocked(2)]);
      expect(find.text('halo @Ana apa kabar'), findsOneWidget); // 整段纯文字
      expect(spansOf(tester).where((s) => s.recognizer != null), isEmpty);
      expect(taps, isEmpty);
    });

    testWidgets('@ 不在词首（邮箱）不高亮', (tester) async {
      await pumpText(tester, 'tulis ke aku@Aurel ya', [tappable(42, 'Aurel')]);
      expect(find.text('tulis ke aku@Aurel ya'), findsOneWidget);
    });

    testWidgets('手写括号里的 @ 仍然算（边界判的是非单词字符，不是空白）', (tester) async {
      final taps = await pumpText(tester, 'halo (@Aurel)', [tappable(42, 'Aurel')]);
      await tester.tapOnText(find.textRange.ofSubstring('@Aurel'));
      await tester.pumpAndSettle();
      expect(taps, [42]);
    });

    testWidgets('两个人昵称一模一样 → 那一处一律不高亮（宁可不可点，不许点错人）', (tester) async {
      final taps = await pumpText(tester, 'halo @Budi dan @Budi',
          [tappable(1, 'Budi'), tappable(2, 'Budi')]);
      expect(find.text('halo @Budi dan @Budi'), findsOneWidget);
      expect(spansOf(tester).where((s) => s.recognizer != null), isEmpty);
      expect(taps, isEmpty);
    });

    testWidgets('🔴 无关重建不得打断正在竞技的手势（recognizer 不在 build 里建）', (tester) async {
      // OneSequenceGestureRecognizer.dispose() 会 resolve(rejected)：在 build 里 dispose
      // 上一批 recognizer，等于「按下与抬起之间发生任何重建 → 这个手势判负、外层赢」。
      final taps = <int>[];
      final rebuild = ValueNotifier<int>(0);
      addTearDown(rebuild.dispose);
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: ValueListenableBuilder<int>(
            valueListenable: rebuild,
            builder: (context, tick, _) => Column(
              children: [
                Text('tick \$tick'),
                MentionText(
                  text: 'halo @Aurel',
                  mentions: const [
                    MentionView(userId: 42, nickname: 'Aurel', tappable: true),
                  ],
                  onTapUser: taps.add,
                  mentionContext: MentionContext.post,
                ),
              ],
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();
      final recognizer =
          spansOf(tester).firstWhere((s) => s.recognizer != null).recognizer;

      // 与被测行为等价的最小重现：按下 → 无关重建 → 抬起。
      final gesture = await tester.startGesture(
          tester.getCenter(find.byType(MentionText)));
      rebuild.value = 1;
      await tester.pump();
      await gesture.up();
      await tester.pumpAndSettle();

      // 重建之后还是**同一个** recognizer（没被换掉、没被 dispose）。
      expect(spansOf(tester).firstWhere((s) => s.recognizer != null).recognizer,
          same(recognizer));
    });

    testWidgets('内容真的变了才换 recognizer', (tester) async {
      Future<void> pumpWith(String text) => tester.pumpWidget(MaterialApp(
            home: Scaffold(
              body: MentionText(
                text: text,
                mentions: const [
                  MentionView(userId: 42, nickname: 'Aurel', tappable: true),
                ],
                onTapUser: (_) {},
                mentionContext: MentionContext.post,
              ),
            ),
          ));
      await pumpWith('halo @Aurel');
      await tester.pumpAndSettle();
      final first = spansOf(tester).firstWhere((s) => s.recognizer != null).recognizer;
      await pumpWith('halo @Aurel lagi');
      await tester.pumpAndSettle();
      expect(spansOf(tester).firstWhere((s) => s.recognizer != null).recognizer,
          isNot(same(first)));
    });
  });

  group('退化与健壮性', () {
    testWidgets('没有 mentions → 纯 Text（存量内容恒如此，Story 3.5 AC1）', (tester) async {
      await pumpText(tester, '存量正文，没人被 @', const []);
      expect(find.text('存量正文，没人被 @'), findsOneWidget);
    });

    testWidgets('🔸 昵称改过、正文里那串字对不上 → 不高亮，但内容照常显示', (tester) async {
      // 正文存的是写入那一刻的「@旧昵称」，后端下发的是当前昵称（AC1）。
      // 两者不一致时定位不到 —— 已知取舍，见 story Completion Notes。
      await pumpText(tester, 'halo @旧名字', [tappable(42, '新名字')]);
      expect(find.text('halo @旧名字'), findsOneWidget);
    });

    testWidgets('maxLines / overflow 在两条分支上都生效', (tester) async {
      for (final mentions in <List<MentionView>>[const [], [tappable(1, 'A')]]) {
        await tester.pumpWidget(MaterialApp(
          home: Scaffold(
            body: MentionText(
              text: '@A ' * 200,
              mentions: mentions,
              onTapUser: (_) {},
              mentionContext: MentionContext.post,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ));
        await tester.pumpAndSettle();
        final rich = tester.widget<RichText>(find.byType(RichText).first);
        expect(rich.maxLines, 2);
        expect(rich.overflow, TextOverflow.ellipsis);
      }
    });

    testWidgets('🔴 recognizer 随重建与 dispose 一起释放（否则随滚动持续泄漏）', (tester) async {
      // story Dev Notes 明写了这一条。用例的形式是：反复重建 + 最后整棵树换掉，
      // 全过程不得抛「A TapGestureRecognizer was used after being disposed」
      // 或在 tearDown 触发未释放断言。
      for (var i = 0; i < 3; i++) {
        await tester.pumpWidget(MaterialApp(
          home: Scaffold(
            body: MentionText(
              text: 'halo @Aurel $i',
              mentions: [tappable(42, 'Aurel')],
              onTapUser: (_) {},
              mentionContext: MentionContext.post,
            ),
          ),
        ));
        await tester.pumpAndSettle();
      }
      await tester.pumpWidget(const MaterialApp(home: Scaffold(body: SizedBox())));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  });

  group('MentionView 解析', () {
    test('后端省略 mentions 字段时是空表而不是 null', () {
      expect(MentionView.listFromJson(null), isEmpty);
      expect(MentionView.listFromJson(const []), isEmpty);
    });

    test('不可点的那一行只有 id（后端不下发昵称）', () {
      final v = MentionView.fromJson({'userId': 42, 'tappable': false});
      expect(v.nickname, isNull);
      expect(v.highlightable, isFalse);
    });

    test('老后端不下发 tappable 时按不可点处理（宁可少一个高亮）', () {
      expect(MentionView.fromJson({'userId': 42}).tappable, isFalse);
    });
  });
}
