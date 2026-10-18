import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/growth/data/growth_frame_controller.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';
import 'package:tailtopia/features/growth/presentation/frame_picker_row.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 ① Story 3.3 · AC3 / AC5：等级页头像框一排（C1 ~ C5，L0 结构断言）。
class _FakeCtrl implements GrowthFrameController {
  final calls = <String>[];
  Completer<GrowthFrames>? gate;
  Object? error;

  @override
  Future<GrowthFrames> choose(String frame) async {
    calls.add(frame);
    if (gate != null) return gate!.future;
    if (error != null) throw error!;
    return frames(effective: frame, level: 10);
  }
}

/// 解锁等级来自「等级表」夹具：改这里，锁标签跟着变。
GrowthFrames frames({String? effective, required int level, int basicAt = 5, int upAt = 10, int animAt = 15}) =>
    GrowthFrames(effective: effective, options: [
      const GrowthFrameOption(frame: 'NONE', unlocked: true, unlockLevel: 1),
      GrowthFrameOption(frame: 'BASIC', unlocked: level >= basicAt, unlockLevel: basicAt),
      GrowthFrameOption(frame: 'UPGRADED', unlocked: level >= upAt, unlockLevel: upAt),
      GrowthFrameOption(frame: 'ANIMATED', unlocked: level >= animAt, unlockLevel: animAt),
    ]);

Future<List<GrowthFrames>> _pump(WidgetTester tester, GrowthFrames f, _FakeCtrl ctrl) async {
  final changed = <GrowthFrames>[];
  await tester.pumpWidget(ProviderScope(
    overrides: [growthFrameControllerProvider.overrideWithValue(ctrl)],
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('id'),
      home: Scaffold(
        body: StatefulBuilder(
          builder: (context, setState) => FramePickerRow(
            frames: f,
            nickname: 'Rina',
            onChanged: (n) => setState(() {
              changed.add(n);
              f = n;
            }),
          ),
        ),
      ),
    ),
  ));
  return changed;
}

Finder _card(String code) => find.byKey(ValueKey('growthFrameCard_$code'));
Finder _check(String code) => find.byKey(ValueKey('growthFrameCheck_$code'));

void main() {
  tearDown(() => Analytics.debugCaptureSink = null);

  testWidgets('Lv10（自动生效 UPGRADED）：BASIC / UPGRADED 可点、ANIMATED 锁 Lv15；勾在生效档', (tester) async {
    await _pump(tester, frames(effective: 'UPGRADED', level: 10), _FakeCtrl());
    expect(find.text('Tanpa bingkai'), findsOneWidget);
    expect(find.text('Dasar'), findsOneWidget);
    expect(find.text('Lanjutan'), findsOneWidget);
    expect(find.text('Animasi'), findsOneWidget);
    expect(_check('UPGRADED'), findsOneWidget);
    expect(find.byKey(const ValueKey('growthFrameLock_ANIMATED')), findsOneWidget);
    expect(find.descendant(of: _card('ANIMATED'), matching: find.text('Lv15')), findsOneWidget);
    expect(find.byKey(const ValueKey('growthFrameLock_BASIC')), findsNothing);
  });

  testWidgets('锁等级来自等级表：门槛改成 12 → 显示 Lv12', (tester) async {
    await _pump(tester, frames(effective: 'BASIC', level: 10, upAt: 12), _FakeCtrl());
    expect(find.descendant(of: _card('UPGRADED'), matching: find.text('Lv12')), findsOneWidget);
  });

  testWidgets('Lv3（C5）：只有 Tanpa bingkai 可选且选中', (tester) async {
    await _pump(tester, frames(effective: 'NONE', level: 3), _FakeCtrl());
    expect(_check('NONE'), findsOneWidget);
    for (final c in ['BASIC', 'UPGRADED', 'ANIMATED']) {
      expect(find.byKey(ValueKey('growthFrameLock_$c')), findsOneWidget);
    }
  });

  testWidgets('点未解锁卡（C4）→ 只 toast「Terbuka di Lv15」，不发请求', (tester) async {
    final ctrl = _FakeCtrl();
    await _pump(tester, frames(effective: 'UPGRADED', level: 10), ctrl);
    await tester.tap(_card('ANIMATED'));
    await tester.pump();
    expect(find.text('Terbuka di Lv15'), findsOneWidget);
    expect(ctrl.calls, isEmpty);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('点当前卡 → 无反应、不发请求', (tester) async {
    final ctrl = _FakeCtrl();
    await _pump(tester, frames(effective: 'UPGRADED', level: 10), ctrl);
    await tester.tap(_card('UPGRADED'));
    await tester.pump();
    expect(ctrl.calls, isEmpty);
  });

  testWidgets('点可选卡（C1 → C2）：转圈 + 其余卡不可点 → 成功勾移动 + toast + 上报一次', (tester) async {
    final seen = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => seen.add((e, p));
    final ctrl = _FakeCtrl()..gate = Completer<GrowthFrames>();
    final changed = await _pump(tester, frames(effective: 'UPGRADED', level: 10), ctrl);

    await tester.tap(_card('BASIC'));
    await tester.pump();
    expect(find.byKey(const ValueKey('growthFrameSpinner')), findsOneWidget);
    await tester.tap(_card('NONE')); // 切换中其余卡不可点
    await tester.pump();
    expect(ctrl.calls, ['BASIC']);

    ctrl.gate!.complete(frames(effective: 'BASIC', level: 10));
    await tester.pump();
    await tester.pump();
    expect(changed, hasLength(1));
    expect(_check('BASIC'), findsOneWidget);
    expect(_check('UPGRADED'), findsNothing);
    expect(find.text('Bingkai avatar diganti'), findsOneWidget);
    expect(seen.where((e) => e.$1 == 'avatar_frame_changed').map((e) => e.$2), [
      {'frame_id': 'basic'},
    ]);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('失败（C3，含 409）→ 勾留原卡 + 失败 toast + 不上报', (tester) async {
    final seen = <String>[];
    Analytics.debugCaptureSink = (e, p) => seen.add(e);
    final ctrl = _FakeCtrl()..error = Exception('409 frame-locked');
    final changed = await _pump(tester, frames(effective: 'UPGRADED', level: 10), ctrl);

    await tester.tap(_card('NONE'));
    await tester.pump();
    await tester.pump();
    expect(changed, isEmpty);
    expect(_check('UPGRADED'), findsOneWidget);
    expect(find.text('Gagal mengganti bingkai, coba lagi'), findsOneWidget);
    expect(find.byKey(const ValueKey('growthFrameSpinner')), findsNothing);
    expect(seen, isNot(contains('avatar_frame_changed')));
    await tester.pump(const Duration(seconds: 3));
  });
}
