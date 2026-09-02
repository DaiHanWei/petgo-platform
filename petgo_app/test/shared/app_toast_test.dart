import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/shared/widgets/app_toast.dart';

/// 全 App 的短提示统一入口。
///
/// 2026-09-02 为 R-3（购物车删除撤销）加了**动作位**。本组的第一要务不是测新功能，
/// 而是钉住**既有 91 处调用的默认行为一个字节都没变** —— 尤其是「点击穿透」：
/// 一个 2.6 秒的纯提示如果开始吃点击，会在全 App 范围内挡住用户正要点的东西，
/// 而这种回归不会有任何报错，只会表现为「有时候按钮点不动」。
void main() {
  Widget host({String? actionLabel, VoidCallback? onAction, Duration? duration}) =>
      MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (ctx) => ElevatedButton(
              onPressed: () => showAppToast(ctx, '已删除',
                  duration: duration ?? const Duration(milliseconds: 2600),
                  actionLabel: actionLabel,
                  onAction: onAction),
              child: const Text('show'),
            ),
          ),
        ),
      );

  /// toast 挂 root Overlay + 带自动消失 Timer，用例结束前必须放掉，
  /// 否则 flutter_test 会以「Timer is still pending」判红。
  Future<void> drain(WidgetTester tester) async =>
      tester.pump(const Duration(seconds: 6));

  group('默认（无动作）—— 既有 91 处调用的路径', () {
    testWidgets('🔴 仍然被 IgnorePointer 包着：点击穿透到底下的页面', (tester) async {
      await tester.pumpWidget(host());
      await tester.tap(find.text('show'));
      await tester.pumpAndSettle();

      expect(find.text('已删除'), findsOneWidget);
      expect(
        find.ancestor(of: find.text('已删除'), matching: find.byType(IgnorePointer)),
        findsOneWidget,
        reason: '纯提示吃掉点击，会在全 App 范围内挡住用户正要点的东西',
      );
      await drain(tester);
    });

    testWidgets('不渲染动作位', (tester) async {
      await tester.pumpWidget(host());
      await tester.tap(find.text('show'));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('appToastAction')), findsNothing);
      await drain(tester);
    });

    testWidgets('到点自动消失', (tester) async {
      await tester.pumpWidget(host());
      await tester.tap(find.text('show'));
      // ⚠️ 这里不能用 pumpAndSettle：它会一直泵到没有待处理帧为止，
      //    连自动消失那一下也一并等掉，断言「还在」就必然落空。
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300)); // 淡入结束
      expect(find.text('已删除'), findsOneWidget);

      await tester.pump(const Duration(seconds: 3));
      expect(find.text('已删除'), findsNothing);
    });
  });

  group('带动作（R-3）', () {
    testWidgets('🔴 不再被 IgnorePointer 罩住，否则按钮点不动', (tester) async {
      await tester.pumpWidget(host(actionLabel: '撤销', onAction: () {}));
      await tester.tap(find.text('show'));
      await tester.pumpAndSettle();

      expect(
        find.ancestor(
            of: find.byKey(const ValueKey('appToastAction')),
            matching: find.byType(IgnorePointer)),
        findsNothing,
        reason: '这条提示的全部意义就是那个可点的动作',
      );
      await drain(tester);
    });

    testWidgets('🔴 点动作 → 回调触发，且 toast 立刻收起', (tester) async {
      var tapped = 0;
      await tester.pumpWidget(host(actionLabel: '撤销', onAction: () => tapped++));
      await tester.tap(find.text('show'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('appToastAction')));
      await tester.pumpAndSettle();

      expect(tapped, 1);
      expect(find.text('已删除'), findsNothing,
          reason: '动作已经执行，提示条再挂几秒只是挡视线');
    });

    testWidgets('动作文案照原样显示', (tester) async {
      await tester.pumpWidget(host(actionLabel: '撤销', onAction: () {}));
      await tester.tap(find.text('show'));
      await tester.pumpAndSettle();

      expect(find.text('撤销'), findsOneWidget);
      await drain(tester);
    });
  });

  testWidgets('🔴 单实例：连弹两条只剩最后一条', (tester) async {
    // 购物车连删两行时靠的就是这条 —— 叠着的旧撤销条点下去，
    // 撤销的不是用户以为的那一行。
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (ctx) => Column(children: [
            ElevatedButton(
                onPressed: () => showAppToast(ctx, '第一条'), child: const Text('a')),
            ElevatedButton(
                onPressed: () => showAppToast(ctx, '第二条'), child: const Text('b')),
          ]),
        ),
      ),
    ));
    await tester.tap(find.text('a'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('b'));
    await tester.pumpAndSettle();

    expect(find.text('第一条'), findsNothing);
    expect(find.text('第二条'), findsOneWidget);
    await drain(tester);
  });
}
