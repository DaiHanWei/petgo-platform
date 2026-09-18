import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/mention/domain/mention_context.dart';
import 'package:tailtopia/features/mention/domain/mention_view.dart';
import 'package:tailtopia/features/mention/presentation/mention_text.dart';

/// L0：@ 的存量边界与埋点（V1.3.0 batch-b1 Story 3.5 · AC1/AC3/AC4）。
///
/// <h3>🔴 AC4 的红线不是"靠兜底"，是"源头就不要传"</h3>
/// 埋点层有兜底黑名单（`name` / `nickname` / `text` / `content` 这类键**整条丢弃**），
/// 但 story Dev Notes 写的是「**源头就不要传，不要依赖兜底**」。
/// 所以下面既断言属性只有一个 `context`，也断言它**真的出得去**
/// （`context` 与黑名单里的 `text` / `content` 不是同一个键 —— 那是精确匹配）。
void main() {
  final List<(String, Map<String, Object>?)> captured = [];

  setUp(() {
    captured.clear();
    Analytics.debugCaptureSink = (event, props) => captured.add((event, props));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<void> pumpTappableMention(WidgetTester tester, MentionContext context) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: MentionText(
          text: 'halo @Aurel',
          mentions: const [MentionView(userId: 42, nickname: 'Aurel', tappable: true)],
          onTapUser: (_) {},
          mentionContext: context,
        ),
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tapOnText(find.textRange.ofSubstring('@Aurel'));
    await tester.pumpAndSettle();
  }

  group('AC4 埋点', () {
    test('🔴 context 只有两种取值，而且是**枚举**不是 String', () {
      // 用 String 的话，哪天有人把昵称插值进来（'post:\$nickname'）既编译得过、
      // 也过得了埋点层的脱敏 —— `context` 这个键不在任何黑名单上
      // （code-review 2026-09-15）。换成枚举之后，类型系统就是那道红线。
      expect(MentionContext.values.map((e) => e.wire), ['post', 'comment']);
    });

    test('🔴 事件名与属性 Map 在源码里是**字面量**（否则对埋点守卫隐身）', () {
      // v112 的命名检查与 v140 的 NFR-5 全局体检都是正则扫
      // `Analytics.capture('<字面量>', {'<键>': …})`。抽成常量看着整齐，
      // 代价是这两个事件从此躲过所有守卫 —— 而那正是「别把 PII 传进埋点」那道网
      // （code-review 2026-09-15）。
      const sites = [
        'lib/features/mention/presentation/mention_text.dart',
        'lib/features/content/presentation/comment_composer.dart',
        'lib/features/content/presentation/publish_compose_page.dart',
      ];
      final joined = sites.map((p) => File(p).readAsStringSync()).join('\n');
      expect(joined, contains("Analytics.capture('mention_tapped', {'context': "));
      expect(joined, contains("Analytics.capture('mention_inserted', {'context': "));
    });

    testWidgets('点正文里的 @ → mention_tapped(context=post)', (tester) async {
      await pumpTappableMention(tester, MentionContext.post);
      expect(captured.where((e) => e.$1 == 'mention_tapped').map((e) => e.$2),
          [{'context': 'post'}]);
    });

    testWidgets('点评论里的 @ → mention_tapped(context=comment)', (tester) async {
      await pumpTappableMention(tester, MentionContext.comment);
      expect(captured.where((e) => e.$1 == 'mention_tapped').map((e) => e.$2),
          [{'context': 'comment'}]);
    });

    testWidgets('🔴 context 属性真的出得去（没被兜底黑名单整键丢掉）', (tester) async {
      // ⚠️ 黑名单对 `text` / `content` 是**精确匹配**，`context` 不命中；
      //    但 PII 那张表是**后缀匹配**（`name` / `phone` / `address`…）——
      //    哪天有人往后缀表里加了 `text`，这条用例会立刻红，而不是看板上悄悄少一截。
      await pumpTappableMention(tester, MentionContext.post);
      final props = captured.firstWhere((e) => e.$1 == 'mention_tapped').$2;
      expect(props, isNotNull);
      expect(props!['context'], 'post');
    });

    testWidgets('不可点的 @ 点不动，自然也不报 mention_tapped', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: MentionText(
            text: 'halo @Aurel',
            mentions: const [MentionView(userId: 42, tappable: false)],
            onTapUser: (_) {},
            mentionContext: MentionContext.post,
          ),
        ),
      ));
      await tester.pumpAndSettle();
      await tester.tapOnText(find.textRange.ofSubstring('@Aurel'));
      await tester.pumpAndSettle();
      expect(captured.where((e) => e.$1 == 'mention_tapped'), isEmpty);
    });
  });

  group('AC1 存量不回溯解析', () {
    testWidgets('🔴 正文里写着 @名字 但后端没给 mentions → 一律纯文字', (tester) async {
      // 这就是存量内容的形态（`mentioned_user_ids` 列为 NULL → 空表不下发）。
      // 客户端**绝不自己扫文本找 @** —— 那正是 AC1 禁止的"回溯解析"。
      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
          body: MentionText(
            text: '上线前就发过的老帖，正文里写着 @阿花 和 @Budi',
            mentions: [],
            onTapUser: _noop,
            mentionContext: MentionContext.post,
          ),
        ),
      ));
      await tester.pumpAndSettle();
      expect(find.text('上线前就发过的老帖，正文里写着 @阿花 和 @Budi'), findsOneWidget);
      // 一个 recognizer 都没挂 → 点哪儿都不会有 mention_tapped。
      await tester.tapOnText(find.textRange.ofSubstring('@阿花'));
      await tester.pumpAndSettle();
      expect(captured, isEmpty);
    });

    test('🔴 没有任何迁移回填 mentioned_user_ids（存量一律 NULL）', () {
      final dir = Directory('../petgo-backend/src/main/resources/db/migration');
      expect(dir.existsSync(), isTrue, reason: '找不到迁移目录：${dir.absolute.path}');
      for (final f in dir.listSync().whereType<File>()) {
        final sql = f.readAsStringSync();
        if (!sql.contains('mentioned_user_ids')) continue;
        // ⚠️ 先剥掉 `--` 注释行再看：注释里正写着「不要为了好看去 UPDATE 存量行」，
        //    不剥的话这条用例会被自己的说明文字绊倒。
        final statements = sql
            .split('\n')
            .where((l) => !l.trimLeft().startsWith('--'))
            .join('\n')
            .toUpperCase();
        // 只允许 ALTER TABLE ... ADD COLUMN；出现 UPDATE 就是在回填存量。
        expect(statements, isNot(contains('UPDATE ')),
            reason: '${f.path} 在回填 mentioned_user_ids —— AC1 要求存量零回填');
      }
    });
  });

  group('AC2 删除后的边界', () {
    /// 🔴 **统一空态，不给 @ 另开一个**。
    ///
    /// 含 @ 的内容被删 / 被下架之后，通知**不撤回**（AD-10 Rule 6，2026-08-28 拍板不做
    /// 撤回链路）。被 @ 者点进去走的是既有那条路：深链 → `/content/{postId}` → 详情 404 →
    /// `ContentLoadErrorKind.gone` → `detailGoneTitle` 空态
    /// （既有用例 `test/content/content_detail_test.dart` 的「AC4: 404 → 失效页」钉着它）。
    ///
    /// 本组用例守的是**别为 @ 新开一个空态**：多一种说法就等于把「这条内容删了」和
    /// 「那个 @ 没了」变成两句话，而用户看到的是同一件事。
    test('详情页的失效态仍然只有三种，没有为 @ 新增分支', () {
      final src = File('lib/features/content/presentation/content_detail_page.dart')
          .readAsStringSync();
      // 三种 kind 各一支，且 `_errorBody` 里不出现任何 mention 字样。
      for (final kind in ['gone', 'forbidden', 'network']) {
        expect(src, contains('ContentLoadErrorKind.$kind'), reason: kind);
      }
      final errorBody = src.substring(src.indexOf('Widget _errorBody('));
      final body = errorBody.substring(0, errorBody.indexOf('\n  }'));
      expect(body.toLowerCase(), isNot(contains('mention')),
          reason: '失效态是**统一**空态，不许为 @ 另开一支（AC2）');
    });

    test('ContentLoadErrorKind 没有因为 @ 多出取值', () {
      final src = File('lib/features/content/domain/content_detail.dart').readAsStringSync();
      final line = src
          .split('\n')
          .firstWhere((l) => l.contains('enum ContentLoadErrorKind'));
      expect(line, contains('gone, forbidden, network'));
    });
  });

  group('AC3 审核链路一行未动', () {
    /// 🔴 @ **不改变**既有内容审核链路。机械判据：审核那几个类里
    /// 不许出现任何 mention 字样 —— 一旦有人把 @ 名单塞进审核入参，这条就红。
    test('审核相关源码里没有任何 mention 字样', () {
      const paths = [
        '../petgo-backend/src/main/java/com/tailtopia/content/service/ContentModerationService.java',
        '../petgo-backend/src/main/java/com/tailtopia/content/service/ManualReviewGate.java',
      ];
      for (final path in paths) {
        final f = File(path);
        expect(f.existsSync(), isTrue, reason: '找不到 $path');
        expect(f.readAsStringSync().toLowerCase(), isNot(contains('mention')),
            reason: '$path 里出现了 mention —— @ 不该改动审核链路（AC3）');
      }
    });
  });
}

void _noop(int _) {}
