import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/core/theme/colors.dart';
import 'package:petgo/features/triage/domain/triage_navigation.dart';
import 'package:petgo/l10n/app_localizations.dart';
import 'package:petgo/shared/widgets/red_alert_overlay.dart';

Future<void> _pump(
  WidgetTester tester, {
  required VoidCallback onAcknowledge,
  MapsLauncher? launcher,
  int lockSeconds = 5,
}) async {
  final container = ProviderContainer(overrides: [
    if (launcher != null) triageMapsLauncherProvider.overrideWithValue(launcher),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: RedAlertOverlay(
          title: '请立即带 Momo 就医',
          lockSeconds: lockSeconds,
          onAcknowledge: onAcknowledge,
        ),
      ),
    ),
  ));
  await tester.pump();
}

void main() {
  testWidgets('AC1: 红底 #C97A7A + ⚠️ + display 文案 + 5s 锁定双按钮禁用 + 倒计时', (tester) async {
    await _pump(tester, onAcknowledge: () {});

    expect(find.text('请立即带 Momo 就医'), findsOneWidget);
    expect(find.byKey(const ValueKey('triageRedIcon')), findsOneWidget);
    expect(find.byKey(const ValueKey('triageRedCountdown')), findsOneWidget);

    // 锁定期双按钮禁用
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('triageRedNavigate'))).onPressed,
        isNull);
    expect(tester.widget<OutlinedButton>(find.byKey(const ValueKey('triageRedLater'))).onPressed,
        isNull);

    // 红底
    final box = tester.widget<Container>(find
        .descendant(of: find.byType(RedAlertOverlay), matching: find.byType(Container))
        .first);
    expect(box.color, AppColors.triageRed);

    await tester.pump(const Duration(seconds: 5)); // 倒计时结束（定时器自取消）
  });

  testWidgets('AC1: 5s 后双按钮启用、倒计时消失', (tester) async {
    await _pump(tester, onAcknowledge: () {});
    await tester.pump(const Duration(seconds: 5));
    expect(find.byKey(const ValueKey('triageRedCountdown')), findsNothing);
    expect(tester.widget<FilledButton>(find.byKey(const ValueKey('triageRedNavigate'))).onPressed,
        isNotNull);
    expect(tester.widget<OutlinedButton>(find.byKey(const ValueKey('triageRedLater'))).onPressed,
        isNotNull);
  });

  testWidgets('🔒 AC1: overlay 内零兽医 / 零变现引流', (tester) async {
    await _pump(tester, onAcknowledge: () {});
    await tester.pump(const Duration(seconds: 5));
    // 不得出现兽医软引导 / 存档 / 任何引流入口
    expect(find.text('Or contact a vet directly'), findsNothing);
    expect(find.byKey(const ValueKey('triageContactVet')), findsNothing);
    expect(find.byKey(const ValueKey('triageSaveToArchive')), findsNothing);
    expect(find.byKey(const ValueKey('triageEntryVet')), findsNothing);
  });

  testWidgets('AC2: 解锁后「去导航」→ 系统确认 → 调系统地图', (tester) async {
    var launchedQuery = '';
    await _pump(
      tester,
      onAcknowledge: () {},
      launcher: (q) async {
        launchedQuery = q;
        return true;
      },
    );
    await tester.pump(const Duration(seconds: 5));
    await tester.tap(find.byKey(const ValueKey('triageRedNavigate')));
    await tester.pumpAndSettle(); // 确认 dialog
    await tester.tap(find.byKey(const ValueKey('triageRedNavConfirm')));
    await tester.pumpAndSettle();
    expect(launchedQuery, isNotEmpty); // 传入搜索词调起地图
  });

  testWidgets('AC2: 解锁后「稍后处理」→ 二次确认 → onAcknowledge 关闭', (tester) async {
    var acked = false;
    await _pump(tester, onAcknowledge: () => acked = true);
    await tester.pump(const Duration(seconds: 5));
    await tester.tap(find.byKey(const ValueKey('triageRedLater')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('triageRedRiskConfirm')));
    await tester.pumpAndSettle();
    expect(acked, isTrue);
  });

  testWidgets('AC3: 无障碍 liveRegion(assertive) 语义 + ⚠️ 非颜色单一', (tester) async {
    await _pump(tester, onAcknowledge: () {});
    final semantics = tester.getSemantics(find.byType(RedAlertOverlay));
    // liveRegion 打断式播报存在（语义子树含 isLiveRegion）
    expect(
      find.descendant(of: find.byType(RedAlertOverlay), matching: find.byIcon(Icons.warning_amber_rounded)),
      findsOneWidget,
    );
    expect(semantics, isNotNull);
    await tester.pump(const Duration(seconds: 5));
  });
}
