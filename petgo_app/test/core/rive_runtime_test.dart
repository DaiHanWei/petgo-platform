import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/rive/rive_runtime.dart';

void main() {
  tearDown(() => RiveRuntime.available = false);

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
}
