import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/startup/startup_popup_ledger.dart';

/// V1.3.4 Story 5.3 · AC4.2：登记点接线（源码扫描）。
///
/// ATT / 首启推送权限 / 启动链走平台插件与 `main()`，L0 驱动不了，故按源码位置钉住。
String _src(String path) => File(path).readAsStringSync();

/// 去掉注释行，避免注释里的字样误命中。
String _code(String src) => src
    .split('\n')
    .where((l) => !l.trimLeft().startsWith('//'))
    .join('\n');

int _count(String haystack, String needle) => needle.allMatches(haystack).length;

void main() {
  setUp(StartupPopupLedger.instance.resetForTest);

  test('att_gate：record(att) 在 `if (await shown)` 与 `_waitUntilDetermined` 之间，且恰好一处', () {
    final code = _code(_src('lib/core/analytics/att_gate.dart'));
    const rec = 'StartupPopupLedger.instance.record(StartupPopupKind.att);';
    expect(_count(code, rec), 1);
    final shown = code.indexOf('if (await shown) {');
    final recAt = code.indexOf(rec);
    final wait = code.indexOf('return _waitUntilDetermined();');
    expect(shown, greaterThan(-1));
    expect(wait, greaterThan(-1));
    expect(recAt, greaterThan(shown));
    expect(recAt, lessThan(wait));
    // 「已决定直接 return true」在登记之前（老用户不登记）。
    expect(code.indexOf('if (status != TrackingStatus.notDetermined) return true;'), lessThan(recAt));
  });

  test('main.dart：markStartupChainDone() 位于 finally 块内，且在 runColdStartCheck 之后', () {
    final code = _code(_src('lib/main.dart'));
    expect(_count(code, 'StartupPopupLedger.instance.markStartupChainDone();'), 1);
    final check = code.indexOf('await AppUpdateBootstrap.runColdStartCheck();');
    final fin = code.indexOf('} finally {', check);
    final mark = code.indexOf('StartupPopupLedger.instance.markStartupChainDone();');
    expect(check, greaterThan(-1));
    expect(fin, greaterThan(check));
    expect(mark, greaterThan(fin));
    // finally 块内（finally 之后、下一个右花括号之前）。
    final close = code.indexOf('}', fin + '} finally {'.length);
    expect(mark, lessThan(close));
    // 首帧回调里 try 包住了 ATT 起点。
    final cb = code.indexOf('addPostFrameCallback((_) async {');
    final tryAt = code.indexOf('try {', cb);
    expect(cb, greaterThan(-1));
    expect(tryAt, lessThan(code.indexOf('final Future<void> afInit', cb)));
  });

  test('app_update_presenter：LEDGER-HOOK(5.3) 已替换为 record(forceUpdate)，且在 show 之前', () {
    final src = _src('lib/features/notify/presentation/app_update_presenter.dart');
    expect(src, isNot(contains('LEDGER-HOOK(5.3)')));
    final code = _code(src);
    const rec = 'StartupPopupLedger.instance.record(StartupPopupKind.forceUpdate);';
    expect(_count(code, rec), 1);
    final fn = code.indexOf('Future<void> showForcedUpdateDialog(');
    final recAt = code.indexOf(rec);
    expect(recAt, greaterThan(code.indexOf('if (_forcedShowing) return;', fn)));
    expect(recAt, greaterThan(code.indexOf('final ctx = rootNavigatorKey.currentContext;', fn)));
    expect(recAt, lessThan(code.indexOf('decision: UpdateDecision.forced', fn)));
    // 推荐态不登记。
    expect(code.indexOf('showRecommendedUpdateDialog'), greaterThan(recAt));
    expect(_count(code, 'StartupPopupKind.'), 1);
  });

  test('V1.4.0 ① 2.3 growth_popup_coordinator：庆祝 / 汇总登记各恰好一处，且在各自 show 之前', () {
    final code = _code(_src('lib/features/growth/presentation/growth_popup_coordinator.dart'));
    const cel = 'StartupPopupLedger.instance.record(StartupPopupKind.growthCelebration);';
    const bf = 'StartupPopupLedger.instance.record(StartupPopupKind.growthBackfill);';
    expect(_count(code, cel), 1);
    expect(_count(code, bf), 1);
    expect(code.indexOf(cel), lessThan(code.indexOf('await _showCelebration(')));
    expect(code.indexOf(bf), lessThan(code.indexOf('await _showBackfill(')));
  });

  test('其余登记点各恰好一次', () {
    expect(
        _count(_code(_src('lib/features/notify/domain/push_permission_bootstrap.dart')),
            'StartupPopupLedger.instance.record(StartupPopupKind.pushPermission);'),
        1);
    expect(
        _count(_code(_src('lib/shared/widgets/coachmark_overlay.dart')),
            'StartupPopupLedger.instance.record(StartupPopupKind.coachmark);'),
        1);
    expect(
        _count(_code(_src('lib/features/profile/presentation/widgets/milestone_celebration.dart')),
            'StartupPopupLedger.instance.record(StartupPopupKind.milestoneCelebration);'),
        1);
  });

  test('推送权限：登记在 request 之前、已问过分支之后', () {
    final code = _code(_src('lib/features/notify/domain/push_permission_bootstrap.dart'));
    final asked = code.indexOf('if (p.pushPermissionAsked) {');
    final rec = code.indexOf('record(StartupPopupKind.pushPermission)');
    final req = code.indexOf('await (request ?? _request)();');
    expect(asked, lessThan(rec));
    expect(rec, lessThan(req));
  });

  test('庆祝页：登记在埋点之前（函数体第一行）', () {
    final code = _code(_src('lib/features/profile/presentation/widgets/milestone_celebration.dart'));
    final rec = code.indexOf('record(StartupPopupKind.milestoneCelebration)');
    expect(rec, lessThan(code.indexOf("Analytics.capture('milestone_celebration_shown'")));
    expect(rec, greaterThan(code.indexOf('Future<void> showMilestoneCelebration(')));
  });

  test('横幅与弹窗组件本身不引用 ledger（推荐横幅不算弹窗；推荐态弹窗不登记）', () {
    expect(_src('lib/features/notify/presentation/app_update_banner.dart'),
        isNot(contains('StartupPopupLedger')));
    expect(_src('lib/features/notify/presentation/app_update_dialog.dart'),
        isNot(contains('StartupPopupLedger')));
  });

  test('4.3 当场庆祝调用处不重复登记，说明注释保留', () {
    final src = _src('lib/features/profile/presentation/milestone_instant_celebration.dart');
    expect(src, isNot(contains('StartupPopupLedger')));
    expect(src, contains('冷启动功能弹窗登记（AD-12）由 Story 5.3 在 showMilestoneCelebration 入口统一完成，此处不另登记。'));
  });
}
