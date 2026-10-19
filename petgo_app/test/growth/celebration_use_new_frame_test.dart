import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';
import 'package:tailtopia/features/growth/domain/growth_popups.dart';
import 'package:tailtopia/features/growth/presentation/level_up_celebration.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 ① Story 3.3 · AC4 / AC5：庆祝页「Pakai Bingkai Baru」一键戴上（D3 → D5 / D6）。
const _offer = GrowthCelebration(
  fromLevel: 4,
  toLevel: 5,
  tierUp: true,
  toTier: GrowthTier.buddy,
  frameAction: GrowthFrameAction.offerNewFrame,
);

Future<List<GrowthPopupOutcome?>> _pump(WidgetTester tester, GrowthCelebration c,
    Future<bool> Function(String frame)? apply) async {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final results = <GrowthPopupOutcome?>[];
  await tester.pumpWidget(MaterialApp(
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    locale: const Locale('id'),
    // 动效框 / 彩屑在「移除动画」下静止，避免 pumpAndSettle 卡住。
    builder: (context, child) =>
        MediaQuery(data: MediaQuery.of(context).copyWith(disableAnimations: true), child: child!),
    home: Builder(
      builder: (context) => Center(
        child: TextButton(
          onPressed: () async => results.add(await showLevelUpCelebration(context,
              celebration: c, nickname: 'Rina', onApplyFrame: apply)),
          child: const Text('OPEN'),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('OPEN'));
  await tester.pumpAndSettle();
  return results;
}

final _primary = find.byKey(const ValueKey('growthCelebrationPrimary'));

void main() {
  tearDown(() => Analytics.debugCaptureSink = null);

  testWidgets('成功（D5）：调 setFrame(新段位最高档) → 按钮变「Bingkai terpasang ✓」不可点、头像换框、页面不关、上报一次',
      (tester) async {
    final seen = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => seen.add((e, p));
    final calls = <String>[];
    final gate = Completer<bool>();
    final out = await _pump(tester, _offer, (f) {
      calls.add(f);
      return gate.future;
    });
    expect(find.text('Pakai Bingkai Baru'), findsOneWidget);
    expect(find.byKey(const ValueKey('growthFrame_basic')), findsNothing);

    await tester.tap(_primary);
    await tester.pump();
    await tester.tap(_primary); // 请求中不可再点
    await tester.pump();
    expect(calls, ['BASIC']);

    gate.complete(true);
    await tester.pump();
    await tester.pump();
    expect(find.text('Bingkai terpasang ✓'), findsOneWidget);
    expect(find.text('Bingkai avatar diganti'), findsOneWidget);
    expect(find.byKey(const ValueKey('growthFrame_basic')), findsOneWidget);
    expect(find.byKey(const ValueKey('growthCelebrationFull')), findsOneWidget);
    expect(out, isEmpty, reason: '庆祝页不自动关闭');
    await tester.tap(_primary);
    await tester.pump();
    expect(calls, hasLength(1));
    expect(seen.where((e) => e.$1 == 'avatar_frame_changed').map((e) => e.$2), [
      {'frame_id': 'basic'},
    ]);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('失败（D6）：按钮恢复可点、头像不变、失败 toast、不上报', (tester) async {
    final seen = <String>[];
    Analytics.debugCaptureSink = (e, p) => seen.add(e);
    var n = 0;
    await _pump(tester, _offer, (f) async {
      n++;
      return false;
    });
    await tester.tap(_primary);
    await tester.pump();
    await tester.pump();
    expect(find.text('Gagal mengganti bingkai, coba lagi'), findsOneWidget);
    expect(find.text('Pakai Bingkai Baru'), findsOneWidget);
    expect(find.byKey(const ValueKey('growthFrame_basic')), findsNothing);
    expect(seen, isNot(contains('avatar_frame_changed')));
    await tester.tap(_primary);
    await tester.pump();
    expect(n, 2, reason: '失败后可重试');
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('段位取最高档：GUARDIAN → UPGRADED、STAR → ANIMATED', (tester) async {
    for (final (tier, want) in [(GrowthTier.guardian, 'UPGRADED'), (GrowthTier.star, 'ANIMATED')]) {
      final calls = <String>[];
      await _pump(
          tester,
          GrowthCelebration(
              fromLevel: 9, toLevel: 10, tierUp: true, toTier: tier, frameAction: GrowthFrameAction.offerNewFrame),
          (f) async {
        calls.add(f);
        return true;
      });
      await tester.tap(_primary);
      await tester.pump();
      expect(calls, [want]);
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpWidget(const SizedBox()); // 下一轮从干净的树开始（否则上一轮的对话框还在）
    }
  });

  testWidgets('AUTO（D2）/ NONE：没有「Pakai Bingkai Baru」', (tester) async {
    for (final action in [GrowthFrameAction.auto, GrowthFrameAction.none]) {
      await _pump(
          tester,
          GrowthCelebration(fromLevel: 4, toLevel: 5, tierUp: true, toTier: GrowthTier.buddy, frameAction: action),
          (f) async => true);
      expect(find.text('Pakai Bingkai Baru'), findsNothing);
      expect(find.text('Lihat Level Saya'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('growthCelebrationClose')));
      await tester.pumpAndSettle();
      await tester.pumpWidget(const SizedBox());
    }
  });
}
