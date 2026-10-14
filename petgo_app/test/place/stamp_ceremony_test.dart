import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/rive/rive_runtime.dart';
import 'package:tailtopia/features/place/presentation/widgets/stamp_ceremony.dart';

void main() {
  testWidgets('unavailableRuntimeCallsOnError', (tester) async {
    RiveRuntime.available = false;
    Object? err;
    await tester.pumpWidget(MaterialApp(
      home: StampCeremony(stampPng: Uint8List(1), onError: (e) => err = e),
    ));
    await tester.pump();
    expect(err, isNotNull);
  });
}
