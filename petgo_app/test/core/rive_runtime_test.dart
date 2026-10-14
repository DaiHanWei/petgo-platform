import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/rive/rive_runtime.dart';

void main() {
  setUp(RiveRuntime.resetForTest);
  tearDown(RiveRuntime.resetForTest);

  test('initFailureLeavesUnavailableAndDoesNotThrow', () async {
    await RiveRuntime.init(
        initializer: () async => throw StateError('no native'));
    expect(RiveRuntime.available, isFalse);
  });

  test('initReturningFalseLeavesUnavailable', () async {
    await RiveRuntime.init(initializer: () async => false);
    expect(RiveRuntime.available, isFalse);
  });

  test('initSuccessMarksAvailable', () async {
    await RiveRuntime.init(initializer: () async => true);
    expect(RiveRuntime.available, isTrue);
  });

  test('initTimeoutLeavesUnavailableAndDoesNotThrow', () async {
    await RiveRuntime.init(
      initializer: () => Completer<bool>().future,
      timeout: const Duration(milliseconds: 50),
    );
    expect(RiveRuntime.available, isFalse);
  });

  test('initIsIdempotent', () async {
    var calls = 0;
    Future<bool> init() async {
      calls++;
      return true;
    }

    final a = RiveRuntime.init(initializer: init);
    final b = RiveRuntime.init(initializer: init);
    expect(identical(a, b), isTrue);
    await a;
    expect(calls, 1);
  });

  test('ensureReadyWaitsForPendingInit', () async {
    final gate = Completer<bool>();
    unawaited(RiveRuntime.init(initializer: () => gate.future));
    final ready = RiveRuntime.ensureReady(wait: const Duration(seconds: 1));
    gate.complete(true);
    expect(await ready, isTrue);
  });

  test('ensureReadyGivesUpAfterWaitWithoutBlocking', () async {
    unawaited(RiveRuntime.init(initializer: () => Completer<bool>().future));
    final sw = Stopwatch()..start();
    expect(await RiveRuntime.ensureReady(wait: const Duration(milliseconds: 50)), isFalse);
    expect(sw.elapsedMilliseconds, lessThan(1000));
  });

  test('ensureReadyWithoutInitReturnsAvailable', () async {
    expect(await RiveRuntime.ensureReady(), isFalse);
    RiveRuntime.available = true;
    expect(await RiveRuntime.ensureReady(), isTrue);
  });
}
