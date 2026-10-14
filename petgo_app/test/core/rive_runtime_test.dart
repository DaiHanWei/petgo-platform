import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/rive/rive_runtime.dart';

void main() {
  tearDown(() => RiveRuntime.available = false);

  test('initFailureLeavesUnavailableAndDoesNotThrow', () async {
    await RiveRuntime.init(initializer: () async => throw StateError('no native'));
    expect(RiveRuntime.available, isFalse);
  });

  test('initSuccessMarksAvailable', () async {
    await RiveRuntime.init(initializer: () async {});
    expect(RiveRuntime.available, isTrue);
  });
}
