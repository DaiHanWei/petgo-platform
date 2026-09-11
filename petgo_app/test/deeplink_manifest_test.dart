import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// L0：深链声明护栏 —— Dart 侧认得的每个 `tailtopia://<host>`，安卓必须也认。
///
/// 🔴 守的是 Bug 20260910-487（2026-09-11 修）：`app.dart` 的 `deepLinkToLocation`
/// 一直支持 `tailtopia://post/{token}`，iOS 注册的是**整个 scheme**（host 通吃）所以没事，
/// 唯独 `AndroidManifest.xml` 漏了 `host="post"` 那条 intent-filter。
/// 后果是安卓系统压根不认这个链接：分享页想唤起 App 必然失败，用户被一路送去应用商店。
///
/// 🛡 **为什么必须自动比对而不是"记得加"**：这两处一个是 Dart、一个是 XML，
/// 分属两次改动、两个文件、两种语言，加映射的人不会自然想到去改 manifest；
/// 而漏了之后**所有测试照样绿**——深链唤起是系统行为，单测和渲染测试都碰不到。
/// 症状只会出现在真机上，且表现为"跳去商店"，很容易被当成产品设计而不是 bug。
void main() {
  test('app.dart 里映射的每个 tailtopia host，AndroidManifest 都有对应 intent-filter', () {
    final dart = File('lib/app.dart').readAsStringSync();
    final manifest = File('android/app/src/main/AndroidManifest.xml').readAsStringSync();

    // `uri.host == 'card'` 这类判定即"Dart 认得这个 host"。
    final hosts = RegExp(r"uri\.host\s*==\s*'([a-z0-9_]+)'")
        .allMatches(dart)
        .map((m) => m.group(1)!)
        .toSet();

    expect(hosts, isNotEmpty, reason: 'app.dart 里一个 host 判定都没抓到，正则该跟着改法更新');

    final declared = RegExp(r'android:scheme="tailtopia"\s+android:host="([a-z0-9_]+)"')
        .allMatches(manifest)
        .map((m) => m.group(1)!)
        .toSet();

    expect(declared, containsAll(hosts),
        reason: 'AndroidManifest 缺少 intent-filter 的 host：${hosts.difference(declared)}');
  });

  test('iOS 注册了 tailtopia scheme（host 通吃，无需逐个声明）', () {
    final plist = File('ios/Runner/Info.plist').readAsStringSync();
    expect(plist.contains('<string>tailtopia</string>'), isTrue,
        reason: 'Info.plist 的 CFBundleURLSchemes 里没有 tailtopia，iOS 侧唤起会全线失效');
  });
}
