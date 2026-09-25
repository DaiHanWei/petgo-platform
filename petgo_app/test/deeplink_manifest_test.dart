import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';

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
    // 里程碑分享页（bug 20260910-487 延伸，cherry-pick 自 c4e5c38d）：那次提交补了清单与 Dart 映射，
    // 但源分支的本测试还没有下面的「反向」检查，这张表没跟着加 → 合进 dev_1.3.0 后反向检查红。
    'milestone': '里程碑分享页（/m）→ 自己的里程碑列表',
    'place': '场所分享页（/place，Story 1.10）',
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
      expect(deepLinkToLocation(Uri.parse('tailtopia://place/abc')), '/places/abc?from=share');
      // 里程碑刻意不带 token：落观看者自己的列表（见 deepLinkToLocation 注释）。
      expect(deepLinkToLocation(Uri.parse('tailtopia://milestone/abc')), '/profile/milestones');
      expect(deepLinkToLocation(Uri.parse('tailtopia://milestone')), '/profile/milestones');
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

  // ===== bug 20260910-487：iOS 唤起后「Page Not Found / no routes for location: tailtopia://card/…」 =====
  //
  // 根因：iOS Info.plist 漏了 FlutterDeepLinkingEnabled=false，引擎把原始 URL 交给 go_router。
  // 两端都必须关掉引擎自动深链、只走 app_links —— 缺哪一端都只有真机才看得出来。
  group('🔴 两端都关掉 Flutter 引擎自动深链（bug 487）', () {
    test('iOS Info.plist：FlutterDeepLinkingEnabled = false', () {
      final plist = File('ios/Runner/Info.plist').readAsStringSync();
      expect(
        RegExp(r'<key>FlutterDeepLinkingEnabled</key>\s*<false\s*/>').hasMatch(plist),
        isTrue,
        reason: '🔴 iOS 缺 FlutterDeepLinkingEnabled=false —— 点分享链接唤起后引擎把 '
            'tailtopia://… 原样交给 go_router，落 Page Not Found',
      );
    });

    test('安卓清单：flutter_deeplinking_enabled = false', () {
      expect(
        RegExp(r'android:name="flutter_deeplinking_enabled"\s*android:value="false"')
            .hasMatch(manifest),
        isTrue,
        reason: '🔴 安卓清单缺 flutter_deeplinking_enabled=false —— 与 iOS 同一个坑',
      );
    });
  });

  group('原始 tailtopia:// URI 漏进 go_router 的兜底改写（bug 487）', () {
    test('认得的 host → 与 app_links 同一张映射', () {
      expect(rawDeepLinkRedirect(Uri.parse('tailtopia://card/abc')), '/pet/abc');
      expect(rawDeepLinkRedirect(Uri.parse('tailtopia://milestone')), '/profile/milestones');
    });

    test('不认识的 host → /home（绝不落 404 页）', () {
      expect(rawDeepLinkRedirect(Uri.parse('tailtopia://whatever/abc')), '/home');
    });

    test('站内路径 / 其它 scheme → null，不干预既有门控', () {
      expect(rawDeepLinkRedirect(Uri.parse('/profile/milestones')), isNull);
      expect(rawDeepLinkRedirect(Uri.parse('https://s.tailtopia.id/p/abc')), isNull);
    });

    test('tailtopia://milestone 改写后仍走受控前缀门控：游客被弹回、登录用户放行', () {
      final loc = rawDeepLinkRedirect(Uri.parse('tailtopia://milestone'))!;
      expect(redirectWouldRewrite(const AuthState(status: AuthStatus.guest), loc), isTrue);
      expect(
        redirectWouldRewrite(
            const AuthState(status: AuthStatus.authenticated, role: 'USER'), loc),
        isFalse,
      );
    });

    /// 机制验证：go_router 对「无匹配路由」的原始 URI 也会跑顶层 redirect，
    /// 且改写后的落点会**再过一遍** redirect（门控才能生效）。
    testWidgets('go_router 真的在 404 之前调顶层 redirect，并对改写结果再跑门控', (tester) async {
      Widget page(String t) => Scaffold(body: Text(t));
      final router = GoRouter(
        initialLocation: '/home',
        redirect: (c, s) {
          final raw = rawDeepLinkRedirect(s.uri);
          if (raw != null) return raw;
          // 模拟受控前缀门控：游客不进 /profile/*。
          if (s.matchedLocation.startsWith('/profile/')) return '/home';
          return null;
        },
        routes: [
          GoRoute(path: '/home', builder: (c, s) => page('home')),
          GoRoute(path: '/pet/:token', builder: (c, s) => page('pet ${s.pathParameters['token']}')),
          GoRoute(path: '/profile/milestones', builder: (c, s) => page('milestones')),
        ],
        errorBuilder: (c, s) => page('404'),
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(MaterialApp.router(routerConfig: router));
      await tester.pumpAndSettle();

      router.go('tailtopia://card/abc');
      await tester.pumpAndSettle();
      expect(find.text('pet abc'), findsOneWidget);
      expect(find.text('404'), findsNothing);

      router.go('tailtopia://milestone');
      await tester.pumpAndSettle();
      expect(find.text('home'), findsOneWidget); // 改写后被门控弹回

      router.go('tailtopia://whatever/x');
      await tester.pumpAndSettle();
      expect(find.text('home'), findsOneWidget);
      expect(find.text('404'), findsNothing);
    });
  });

  // 合并 main（PR #38 那版本测试）带来的一条：iOS 的 scheme 注册是 host 通吃，只需保证 scheme 在。
  test('iOS 注册了 tailtopia scheme（host 通吃，无需逐个声明）', () {
    final plist = File('ios/Runner/Info.plist').readAsStringSync();
    expect(plist.contains('<string>tailtopia</string>'), isTrue,
        reason: 'Info.plist 的 CFBundleURLSchemes 里没有 tailtopia，iOS 侧唤起会全线失效');
  });
}
