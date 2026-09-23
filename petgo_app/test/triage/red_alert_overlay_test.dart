import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/red_alert_overlay.dart';

Future<void> _pump(
  WidgetTester tester, {
  required VoidCallback onAcknowledge,
  int lockSeconds = 5,
  List<String> emergencySteps = const <String>[],
  List<String> emergencyAvoid = const <String>[],
  Locale locale = const Locale('en'),
}) async {
  final container = ProviderContainer();
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      locale: locale,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: RedAlertOverlay(
          title: '请立即带 Momo 去宠物医院就诊',
          lockSeconds: lockSeconds,
          emergencySteps: emergencySteps,
          emergencyAvoid: emergencyAvoid,
          onAcknowledge: onAcknowledge,
        ),
      ),
    ),
  ));
  await tester.pump();
}

void main() {
  testWidgets('AC1: 红底 #C97A7A + ⚠️ + display 文案 + 5s 锁定单按钮禁用 + 倒计时', (tester) async {
    await _pump(tester, onAcknowledge: () {});

    expect(find.text('请立即带 Momo 去宠物医院就诊'), findsOneWidget);
    expect(find.byKey(const ValueKey('triageRedIcon')), findsOneWidget);
    expect(find.byKey(const ValueKey('triageRedCountdown')), findsOneWidget);

    // 锁定期单按钮「我已知晓」禁用
    expect(
        tester.widget<FilledButton>(find.byKey(const ValueKey('triageRedAcknowledge'))).onPressed,
        isNull);

    // 红底
    final box = tester.widget<Container>(find
        .descendant(of: find.byType(RedAlertOverlay), matching: find.byType(Container))
        .first);
    expect(box.color, AppColors.triageRed);

    await tester.pump(const Duration(seconds: 5)); // 倒计时结束（定时器自取消）
  });

  testWidgets('AC2: 5s 后「我已知晓」按钮启用、倒计时消失', (tester) async {
    await _pump(tester, onAcknowledge: () {});
    await tester.pump(const Duration(seconds: 5));
    expect(find.byKey(const ValueKey('triageRedCountdown')), findsNothing);
    expect(
        tester.widget<FilledButton>(find.byKey(const ValueKey('triageRedAcknowledge'))).onPressed,
        isNotNull);
  });

  testWidgets('🔒 AC1/AC2: overlay 内零兽医 / 零变现 / 零地图导航 / 零医院推荐', (tester) async {
    await _pump(tester, onAcknowledge: () {});
    await tester.pump(const Duration(seconds: 5));
    // 不得出现兽医软引导 / 存档 / 任何引流入口 / 地图导航 / 医院推荐（F3 去导航化）
    expect(find.text('Or contact a vet directly'), findsNothing);
    expect(find.byKey(const ValueKey('triageContactVet')), findsNothing);
    expect(find.byKey(const ValueKey('triageSaveToArchive')), findsNothing);
    expect(find.byKey(const ValueKey('triageEntryVet')), findsNothing);
    // 旧版「去导航 / 稍后处理」双按钮已彻底移除
    expect(find.byKey(const ValueKey('triageRedNavigate')), findsNothing);
    expect(find.byKey(const ValueKey('triageRedLater')), findsNothing);
  });

  testWidgets('AC2: 解锁后单一「我已知晓」→ 直接 onAcknowledge 关闭（无地图、无二次确认）', (tester) async {
    var acked = false;
    await _pump(tester, onAcknowledge: () => acked = true);
    await tester.pump(const Duration(seconds: 5));
    await tester.tap(find.byKey(const ValueKey('triageRedAcknowledge')));
    await tester.pump();
    expect(acked, isTrue);
  });

  testWidgets('AC3: 无障碍 liveRegion(assertive) 语义 + ⚠️ 非颜色单一', (tester) async {
    await _pump(tester, onAcknowledge: () {});
    final semantics = tester.getSemantics(find.byType(RedAlertOverlay));
    // liveRegion 打断式播报存在（语义子树含 isLiveRegion）
    expect(
      find.descendant(
          of: find.byType(RedAlertOverlay), matching: find.byIcon(Icons.warning_amber_rounded)),
      findsOneWidget,
    );
    expect(semantics, isNotNull);
    await tester.pump(const Duration(seconds: 5));
  });

  testWidgets('对症应急：有 AI 步骤时渲染 AI「现在该做 + 切勿」，不出现通用兜底', (tester) async {
    await _pump(tester,
        onAcknowledge: () {},
        emergencySteps: <String>['移到阴凉通风处', '常温水浸湿身体辅助降温'],
        emergencyAvoid: <String>['切勿强行喂食或灌水']);
    expect(find.text('移到阴凉通风处'), findsOneWidget);
    expect(find.text('常温水浸湿身体辅助降温'), findsOneWidget);
    // 切勿区（仅 AI 有禁忌时出现）
    expect(find.text('切勿强行喂食或灌水'), findsOneWidget);
    expect(find.text('DO NOT'), findsOneWidget); // triageRedAvoidHeader 大写
    // 通用兜底步骤不应出现（有对症内容时不回退）
    expect(find.text('Minimize movement and handling during transport'), findsNothing);
    await tester.pump(const Duration(seconds: 5));
  });

  // bug 20260715-290：整页一个滚动，红头随内容上滑收缩 —— 但红色警示绝不完全消失。
  testWidgets('滚动后红色标题仍可见（紧凑红底栏 ⚠️ + 标题钉在顶部），「我已知晓」仍固定可见', (tester) async {
    await _pump(tester,
        onAcknowledge: () {},
        emergencySteps: <String>[for (var i = 0; i < 12; i++) '步骤$i 很长的一段应急说明文字'],
        emergencyAvoid: <String>[for (var i = 0; i < 6; i++) '切勿$i']);
    // 初始：展开态，没有紧凑栏（同屏不出现两份标题）。
    expect(find.byKey(const ValueKey('triageRedCompactBar')), findsNothing);
    final headerTopBefore = tester.getTopLeft(find.byKey(const ValueKey('triageRedIcon'))).dy;
    final ackRectBefore = tester.getRect(find.byKey(const ValueKey('triageRedAcknowledge')));

    await tester.drag(find.byKey(const ValueKey('triageRedScroll')), const Offset(0, -3000));
    await tester.pump();

    // 收起后：紧凑红底栏 + ⚠️ + 完整标题文本在屏幕内。
    final compactTitle = find.byKey(const ValueKey('triageRedCompactTitle'));
    expect(compactTitle, findsOneWidget);
    expect(tester.widget<Text>(compactTitle).data, '请立即带 Momo 去宠物医院就诊');
    expect(find.byKey(const ValueKey('triageRedCompactIcon')), findsOneWidget);
    final screen = Offset.zero & tester.view.physicalSize / tester.view.devicePixelRatio;
    final titleRect = tester.getRect(compactTitle);
    expect(screen.contains(titleRect.topLeft) && screen.contains(titleRect.bottomRight), isTrue);
    expect(titleRect.top, lessThan(100), reason: '紧凑栏钉在顶部');
    // 紧凑栏底色是红色（不是白卡盖住了它）。
    final bar = tester.widget<Container>(find
        .ancestor(of: find.byKey(const ValueKey('triageRedCompactBar')), matching: find.byType(Container))
        .first);
    expect(bar.color, AppColors.triageRed);
    // 展开态已收掉；白卡内容确实滚上来了。
    expect(find.byKey(const ValueKey('triageRedIcon')), findsNothing);
    expect(headerTopBefore, greaterThanOrEqualTo(0));
    // 「我已知晓」位置不随滚动变化（固定底部）。
    expect(tester.getRect(find.byKey(const ValueKey('triageRedAcknowledge'))), ackRectBefore);

    // 5s 锁定不受滚动影响：仍禁用；到点后解锁。
    expect(
        tester.widget<FilledButton>(find.byKey(const ValueKey('triageRedAcknowledge'))).onPressed,
        isNull);
    await tester.pump(const Duration(seconds: 5));
    expect(
        tester.widget<FilledButton>(find.byKey(const ValueKey('triageRedAcknowledge'))).onPressed,
        isNotNull);
  });

  testWidgets('对症应急：AI 步骤为空时回退通用步骤（修订后中性文案，无切勿区）', (tester) async {
    await _pump(tester, onAcknowledge: () {}); // emergencySteps/avoid 默认空
    // 回退到通用三步；修订后 step2 不再是有害的「裹暖布」
    expect(find.text('Minimize movement and handling during transport'), findsOneWidget);
    expect(find.textContaining('warm cloth'), findsNothing);
    // 切勿区仅 AI 有禁忌时渲染，回退态不出现
    expect(find.text('DO NOT'), findsNothing);
    await tester.pump(const Duration(seconds: 5));
  });
}
