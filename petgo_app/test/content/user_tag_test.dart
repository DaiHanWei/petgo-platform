import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/user_tag.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/shared/widgets/anchored_tooltip.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/shared/widgets/tag_icon.dart';
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
            position: 'feed',
          ),
        ),
      ),
    ),
  ));
  await tester.pump();
}

/// 当前渲染出的所有圆形衬底的颜色（按出现顺序）。
List<Color?> _circleColors(WidgetTester tester) =>
    tester.widgetList<Container>(find.byType(Container))
        .where((c) => c.decoration is BoxDecoration
            && (c.decoration! as BoxDecoration).shape == BoxShape.circle)
        .map((c) => (c.decoration! as BoxDecoration).color)
        .toList();

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

  /// 🔴 **图标必须有金色圆底**（UI 稿 `.utag-icon`：14×14 / 圆 / 金色底 / 白色字形）。
  ///
  /// 此前实现成"裸图标、无衬底"（bug 20260828）。后果不是少了个装饰：
  /// 稿子里图标是**白色**的，运营照稿做一枚白色图标传上来，在白色 Feed 背景上
  /// 完全看不见；而 `TagIcon` 加载失败时同样收缩为零 —— 两种情况长得一模一样，
  /// 「图标没显示」这件事在实机上因此无法自证，实机排查绕了一大圈。
  group('bug 20260828 · 图标衬底', () {
    testWidgets('每个标签图标都套在金色圆底上', (tester) async {
      await _pumpRow(tester, name: 'Alice', tags: [_tag('a'), _tag('b')]);

      final circles = tester.widgetList<Container>(find.byType(Container))
          .where((c) => c.decoration is BoxDecoration
              && (c.decoration! as BoxDecoration).shape == BoxShape.circle)
          .toList();
      expect(circles, hasLength(2),
          reason: '🔴 图标没有圆形衬底 —— 白色图标在白底上会完全看不见');
      for (final c in circles) {
        expect((c.decoration! as BoxDecoration).color, AppColors.gold,
            reason: '🔴 衬底不是稿子里的金色');
      }
    });

    testWidgets('圆底 14、内圈 8 —— 与 UI 稿同比例，图标不顶到边缘', (tester) async {
      await _pumpRow(tester, name: 'Alice', tags: [_tag('a')]);

      final circle = tester.widgetList<Container>(find.byType(Container))
          .firstWhere((c) => c.decoration is BoxDecoration
              && (c.decoration! as BoxDecoration).shape == BoxShape.circle);
      expect(circle.constraints?.maxWidth ?? 0, closeTo(14, 0.01));
      // 内圈按 8/14 收 —— 直接铺满会让图标压在圆的描边上。
      expect(tester.widget<TagIcon>(find.byType(TagIcon)).size, closeTo(8, 0.01));
    });
  });

  /// 🔴 **底色按标签走**（2026-08-28，UI 稿 `.utag-icon`：官方号金、最佳新人紫）。
  ///
  /// 此前圆底是写死的金色，运营配不出第二种 —— 而稿子里颜色正是区分标签类别的手段。
  group('bug 20260828 · 徽章底色按标签配', () {
    testWidgets('后端给了色值就用它', (tester) async {
      const violet = Color(0xFF845EC9);
      await _pumpRow(tester, name: 'Alice', tags: [
        UserTag(code: 'star', name: '最佳新人', icon: '★', description: 'x',
            badgeColor: violet),
      ]);

      expect(_circleColors(tester), [violet],
          reason: '🔴 底色仍写死 —— 运营配的颜色没生效');
    });

    testWidgets('没给色值回落金色（稿子的默认值）', (tester) async {
      await _pumpRow(tester, name: 'Alice', tags: [_tag('a')]);
      expect(_circleColors(tester), [AppColors.gold]);
    });

    /// 🛡 **色值解析不出来不许炸**：它是展示层的锦上添花，
    /// 一个格式不对的字符串不该让整页 Feed 解析失败。
    test('坏色值当没给，不抛异常', () {
      for (final bad in ['', 'red', '#GGGGGG', '#12345', 'F6A609']) {
        final t = UserTag.fromJson({
          'code': 'c', 'name': 'n', 'icon': 'i', 'description': 'd', 'badgeColor': bad,
        });
        expect(t, isNotNull, reason: '坏色值 "$bad" 把整条标签解析掉了');
        expect(t!.badgeColor, isNull);
      }
    });

    test('合法色值解析成对应颜色', () {
      final t = UserTag.fromJson({
        'code': 'c', 'name': 'n', 'icon': 'i', 'description': 'd', 'badgeColor': '#845EC9',
      });
      expect(t!.badgeColor, const Color(0xFF845EC9));
    });
  });
}
