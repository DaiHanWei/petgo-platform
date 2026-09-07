import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/widgets/tag_icon.dart';

/// L0：标签图标渲染（Story 11.5 · AC5）。
void main() {
  Future<void> pump(WidgetTester tester, Widget child) =>
      tester.pumpWidget(MaterialApp(home: Scaffold(body: Center(child: child))));

  test('是不是图片 URL 的判定', () {
    expect(TagIcon.isImage('https://cdn/a.png'), isTrue);
    expect(TagIcon.isImage('http://cdn/a.png'), isTrue);
    expect(TagIcon.isImage('🏅'), isFalse);
    expect(TagIcon.isImage(''), isFalse);
    // 🛡 别把「看着像路径」的字符串当 URL
    expect(TagIcon.isImage('/a/b.png'), isFalse);
    expect(TagIcon.isImage('cdn/a.png'), isFalse);
  });

  testWidgets('URL → 渲染图片，不渲染文本', (tester) async {
    await pump(tester, const TagIcon(icon: 'https://cdn/a.png', size: 12));

    expect(find.byType(Image), findsOneWidget);
    expect(find.byType(Text), findsNothing);
  });

  /// ⚠️ 过渡兼容：本地/测试库里还有 emoji 存量。
  /// 🔴 这是**过渡**不是双模式 —— 待确认无非 URL 图标后应删掉那条分支。
  testWidgets('存量 emoji → 渲染文本，不渲染图片', (tester) async {
    await pump(tester, const TagIcon(icon: '🏅', size: 12));

    expect(find.text('🏅'), findsOneWidget);
    expect(find.byType(Image), findsNothing);
  });

  /// 🛡 图标挂在对象存储上，网络差时会晚到甚至不到。
  /// 那时整个图标位**不占空间**，让标签只显示名称 —— 而不是留一个碎图框或灰底。
  testWidgets('空值 → 收缩为零，不占位', (tester) async {
    await pump(tester, const TagIcon(icon: '', size: 12));

    expect(tester.getSize(find.byType(TagIcon)), Size.zero);
    expect(find.byType(Image), findsNothing);
    expect(find.byType(Text), findsNothing);
  });

  testWidgets('文本形态占满给定边长（上游的"放得下几个"依赖它）', (tester) async {
    await pump(tester, const TagIcon(icon: '🏅', size: 14));
    expect(tester.getSize(find.byType(TagIcon)), const Size(14, 14));
  });
}
