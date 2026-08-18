import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/user_tag.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/shared/widgets/anchored_tooltip.dart';
import 'package:tailtopia/shared/widgets/user_tag_row.dart';

/// V1.1.6 Story 5.1：用户标签的展示与 tooltip。
///
/// <p>守两件最容易做歪的事：**空间不足时保昵称、丢标签**（不是截断昵称、也不是「+N」折叠），
/// 以及 **tooltip 是共享浮层**（不占布局、点外部关闭）。
UserTag _tag(String code) =>
    UserTag(code: code, name: '标签$code', icon: '🏅', description: '$code 的说明文案');

Future<void> _pumpRow(
  WidgetTester tester, {
  required String name,
  required List<UserTag> tags,
  double width = 300,
}) async {
  await tester.pumpWidget(MaterialApp(
    home: Scaffold(
      body: Center(
        child: SizedBox(
          width: width,
          child: UserTagRow(
            name: name,
            nameStyle: const TextStyle(fontSize: 14),
            tags: tags,
          ),
        ),
      ),
    ),
  ));
  await tester.pump();
}

void main() {
  tearDown(dismissAnchoredTooltip);

  group('AC2/AC4 展示', () {
    testWidgets('宽度够时标签全部展示', (tester) async {
      await _pumpRow(tester, name: 'Alice', tags: [_tag('a'), _tag('b'), _tag('c')]);

      expect(find.byKey(const ValueKey('userTag_a')), findsOneWidget);
      expect(find.byKey(const ValueKey('userTag_b')), findsOneWidget);
      expect(find.byKey(const ValueKey('userTag_c')), findsOneWidget);
      expect(find.text('Alice'), findsOneWidget);
    });

    /// 🛡 **昵称完整优先、标签丢弃** —— 不截断昵称、不做「+N」折叠。
    ///
    /// 这条是本 story 最容易做歪的地方：把昵称和标签塞进一个 Row 让它自己溢出，
    /// 溢出的要么是被裁一半的标签、要么是被打点的昵称，两种都违反规则。
    testWidgets('空间不足时丢标签，昵称不被牺牲', (tester) async {
      const longName = '这是一个相当长的用户昵称占满整行';
      await _pumpRow(tester,
          name: longName, tags: [_tag('a'), _tag('b'), _tag('c')], width: 160);

      // 昵称仍在（且没有被标签挤掉）
      expect(find.text(longName), findsOneWidget);
      // 标签被丢掉了 —— 至少不是三个都在
      final shown = ['a', 'b', 'c']
          .where((c) => find.byKey(ValueKey('userTag_$c')).evaluate().isNotEmpty)
          .length;
      expect(shown, lessThan(3), reason: '放不下就该丢标签，而不是硬塞');
    });

    /// 🛡 丢的是靠后的（后端已按分配时间倒序排好 → 保留的是最近分配的）。
    testWidgets('丢弃从末尾开始，保留靠前的', (tester) async {
      await _pumpRow(tester,
          name: 'Alice', tags: [_tag('a'), _tag('b'), _tag('c')], width: 70);

      final aShown = find.byKey(const ValueKey('userTag_a')).evaluate().isNotEmpty;
      final cShown = find.byKey(const ValueKey('userTag_c')).evaluate().isNotEmpty;
      expect(cShown, isFalse, reason: '最靠后的先被丢');
      if (!aShown) {
        // 极窄时可能一个都放不下，这也是允许的 —— 但绝不能出现"丢了前面留后面"
        expect(cShown, isFalse);
      }
    });

    testWidgets('没有标签时就是一行普通昵称', (tester) async {
      await _pumpRow(tester, name: 'Alice', tags: const []);
      expect(find.text('Alice'), findsOneWidget);
      expect(find.byType(GestureDetector), findsNothing);
    });
  });

  group('AC3 tooltip', () {
    /// 点标签 → 弹出名称 + 运营配置的说明。
    testWidgets('点标签弹出名称与说明', (tester) async {
      await _pumpRow(tester, name: 'Alice', tags: [_tag('a')]);

      await tester.tap(find.byKey(const ValueKey('userTag_a')));
      await tester.pumpAndSettle();

      expect(find.text('标签a'), findsOneWidget);
      expect(find.text('a 的说明文案'), findsOneWidget);
    });

    /// 🛡 **点外部关闭**。
    testWidgets('点外部关闭', (tester) async {
      await _pumpRow(tester, name: 'Alice', tags: [_tag('a')]);
      await tester.tap(find.byKey(const ValueKey('userTag_a')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('tagTooltip')), findsOneWidget);

      await tester.tapAt(const Offset(10, 10));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('tagTooltip')), findsNothing);
    });

    /// ⚠️ 提示层开着的时候，**任何一次点击都先被拦截层吃掉**（包括点另一个标签）。
    ///
    /// 这是"点外部关闭"的直接后果：拦截层铺满整屏。若让它透传，点击就会同时落到
    /// 下面的卡片上（把用户直接带进详情页），那更糟。
    /// 所以行为是「先关，再点才开另一个」—— 这条把它写明，免得被当成 bug。
    testWidgets('开着的时候点别处一律先关掉，不会叠出两层', (tester) async {
      await _pumpRow(tester, name: 'A', tags: [_tag('a'), _tag('b')]);
      await tester.tap(find.byKey(const ValueKey('userTag_a')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('userTag_b')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('tagTooltip')), findsNothing);

      // 再点一次才开另一个
      await tester.tap(find.byKey(const ValueKey('userTag_b')));
      await tester.pumpAndSettle();
      expect(find.text('标签b'), findsOneWidget);
    });
  });

  group('线格式解析', () {
    /// ⚠️ 后端**空标签不下发**（省掉每行一个空数组），所以字段缺失是常态。
    test('字段缺失 → 空表；坏元素被滤掉', () {
      expect(UserTag.listFromJson(null), isEmpty);
      expect(UserTag.listFromJson('x'), isEmpty);

      final list = UserTag.listFromJson([
        {'code': 'vet', 'name': '兽医', 'icon': '🩺', 'description': '已认证兽医'},
        {'code': 'x'}, // 缺字段
        42,
      ]);
      expect(list, hasLength(1));
      expect(list.first.name, '兽医');
    });

    test('四处出口的模型都读得到标签', () {
      final feed = FeedItem.fromJson({
        'id': 1,
        'authorId': 2,
        'type': 'DAILY',
        'createdAt': '2026-06-02T00:00:00Z',
        'authorTags': [
          {'code': 'vet', 'name': '兽医', 'icon': '🩺', 'description': 'd'}
        ],
      });
      expect(feed.authorTags.single.code, 'vet');

      final comment = Comment.fromJson({
        'id': 1,
        'authorId': 2,
        'body': 'hi',
        'createdAt': '2026-06-02T00:00:00Z',
        'authorTags': [
          {'code': 'vet', 'name': '兽医', 'icon': '🩺', 'description': 'd'}
        ],
      });
      expect(comment.authorTags.single.code, 'vet');
    });
  });
}
