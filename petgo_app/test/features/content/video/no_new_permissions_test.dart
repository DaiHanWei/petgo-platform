import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// L0：专题线 video Story 1.5 · 一致性规则 9 —— 选视频不新增任何系统权限。
void main() {
  test('AndroidManifest 不含视频 / 录音权限', () {
    final m = File('android/app/src/main/AndroidManifest.xml').readAsStringSync();
    expect(m, isNot(contains('READ_MEDIA_VIDEO')));
    expect(m, isNot(contains('RECORD_AUDIO')));
  });

  test('Info.plist 不含麦克风用途说明', () {
    final p = File('ios/Runner/Info.plist').readAsStringSync();
    expect(p, isNot(contains('NSMicrophoneUsageDescription')));
  });
}
