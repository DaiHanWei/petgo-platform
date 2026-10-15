import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/startup/startup_popup_ledger.dart';

/// V1.3.4 Story 5.3 · AC1：本次冷启动功能弹窗记录（AD-12，只登记不排队）。
void main() {
  final ledger = StartupPopupLedger.instance;

  setUp(ledger.resetForTest);

  test('初始态：无记录、启动链未结束', () {
    expect(ledger.hasAny, isFalse);
    expect(ledger.recorded, isEmpty);
    expect(ledger.isStartupChainDone, isFalse);
  });

  test('record：登记后 hasAny / recorded 反映；同一种重复登记无副作用', () {
    ledger.record(StartupPopupKind.att);
    ledger.record(StartupPopupKind.att);
    ledger.record(StartupPopupKind.coachmark);
    expect(ledger.hasAny, isTrue);
    expect(ledger.recorded, {StartupPopupKind.att, StartupPopupKind.coachmark});
  });

  test('recorded 是只读视图（改不动内部记录）', () {
    ledger.record(StartupPopupKind.forceUpdate);
    final view = ledger.recorded;
    expect(() => view.add(StartupPopupKind.att), throwsUnsupportedError);
    expect(() => view.clear(), throwsUnsupportedError);
    expect(ledger.recorded, {StartupPopupKind.forceUpdate});
  });

  test('markStartupChainDone 幂等；startupChainDone 完成一次', () async {
    var completions = 0;
    final f = ledger.startupChainDone.then((_) => completions++);
    ledger.markStartupChainDone();
    ledger.markStartupChainDone(); // 再调不抛（Completer 不会二次 complete）
    await f;
    expect(ledger.isStartupChainDone, isTrue);
    expect(completions, 1);
    // 已完成后再取 future 立即完成。
    await ledger.startupChainDone;
  });

  test('resetForTest：清空记录 + 换新的未完成 future', () async {
    ledger.record(StartupPopupKind.milestoneCelebration);
    ledger.markStartupChainDone();
    final old = ledger.startupChainDone;
    await old;

    ledger.resetForTest();
    expect(ledger.hasAny, isFalse);
    expect(ledger.isStartupChainDone, isFalse);
    expect(identical(ledger.startupChainDone, old), isFalse);

    var done = false;
    unawaited(ledger.startupChainDone.then((_) => done = true));
    await Future<void>.delayed(Duration.zero);
    expect(done, isFalse, reason: '新 future 未完成');
  });
}
