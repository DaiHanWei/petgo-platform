import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/app.dart';

/// L0：**App 认得的每个 `tailtopia://` host，安卓清单里必须有对应声明**
/// （V1.3.0 batch-b1 Story 1.10 · AC6）。
///
/// <h2>为什么必须机械守这一条</h2>
/// 深链映射住在**两处**：Dart 侧 [deepLinkToLocation] 与
/// `android/app/src/main/AndroidManifest.xml` 的 `<intent-filter>`。
/// 漏掉清单那一处的表现是 —— **安卓上点分享链接的 CTA 什么都不会发生**
/// （系统根本不认得这个 scheme://host，连 App 都不会被唤起），
/// 而 <b>所有测试照样绿</b>：深链是系统行为，Dart 侧一行代码都没错。
///
/// <p>🔴 这条测试写下来时**当场抓到一个存量漏网**：`tailtopia://post`
/// （V1.1.6 Story 9.3 的单条内容分享）只加了 Dart 映射、没加清单声明 ——
/// 那条深链在安卓上从来没工作过。已一并补上。
///
/// ⚠️ 加新 host 时这条会自动把你网住。**不要通过把 host 从下面的清单里删掉来"修"它。**
void main() {
  /// App 认得的全部 host。
  ///
  /// ⚠️ 与 [deepLinkToLocation] 的分支一一对应 —— 加分支就加这里。
  /// `open` 是 debug-only 的导航钩子（release 里恒落首页），但清单声明照样要有：
  /// 它同时是下载引导落地页 `s.tailtopia.id/get` 的唤起目标。
  const hosts = <String, String>{
    'card': '成长档案分享页（/p）',
    'post': '单条内容分享页（/c）',
    'place': '场所分享页（/place，Story 1.10）',
    // stag 侧 Bug 20260910-487 延伸（2026-09-11 产品定）：分享的是别人达成的里程碑，
    // 站内没有「看别人里程碑」那一屏 → 落自己的列表，刻意不带 token。
    'milestone': '里程碑分享页（/m）—— 落自己的里程碑列表',
    'open': '下载引导落地页（/get）+ debug 导航钩子',
  };

  final manifest =
      File('android/app/src/main/AndroidManifest.xml').readAsStringSync();

  group('🔴 每个深链 host 都要有安卓清单声明', () {
    for (final entry in hosts.entries) {
      test('${entry.key} —— ${entry.value}', () {
        expect(
          manifest.contains('android:scheme="tailtopia" android:host="${entry.key}"'),
          isTrue,
          reason: '🔴 AndroidManifest 里缺 tailtopia://${entry.key} 的 intent-filter —— '
              '安卓上这条深链唤起必失败，而没有任何别的测试会红（深链是系统行为）',
        );
      });
    }

    /// 反过来：清单里声明了、Dart 侧却不认的 host，会把用户唤起到一个什么都不做的 App。
    test('清单里没有 Dart 侧不认识的 host', () {
      final declared = RegExp(r'android:scheme="tailtopia" android:host="(\w+)"')
          .allMatches(manifest)
          .map((m) => m.group(1)!)
          .toSet();
      expect(declared.difference(hosts.keys.toSet()), isEmpty,
          reason: '🔴 清单声明了 App 不认识的 host —— 点链接会把 App 拉起来然后停在首页');
    });
  });

  group('Dart 侧映射确实认得这些 host', () {
    test('card / post / place 各自落到自己的页面，互不串门', () {
      expect(deepLinkToLocation(Uri.parse('tailtopia://card/abc')), '/pet/abc');
      expect(deepLinkToLocation(Uri.parse('tailtopia://post/abc')), '/shared-post/abc');
      expect(deepLinkToLocation(Uri.parse('tailtopia://place/abc')), '/places/abc');
      // 里程碑刻意不接 token：带不带都落自己的列表
      expect(deepLinkToLocation(Uri.parse('tailtopia://milestone')), '/profile/milestones');
      expect(deepLinkToLocation(Uri.parse('tailtopia://milestone/abc')), '/profile/milestones');
    });

    /// 🔴 没有 token 时**不能**落到别人的东西上 —— V1.1.6 Story 2.4 修过的那个 bug
    /// 就是「点别人的分享链接看到自己家宠物」。
    test('缺 token 时落一个安全的兜底页，不落任何具体资源', () {
      expect(deepLinkToLocation(Uri.parse('tailtopia://card')), '/profile');
      expect(deepLinkToLocation(Uri.parse('tailtopia://post')), '/home');
      expect(deepLinkToLocation(Uri.parse('tailtopia://place')), '/places');
    });

    test('不认识的 scheme / host → null（不导航）', () {
      expect(deepLinkToLocation(Uri.parse('https://s.tailtopia.id/place/abc')), isNull);
      expect(deepLinkToLocation(Uri.parse('tailtopia://whatever/abc')), isNull);
    });
  });
}
