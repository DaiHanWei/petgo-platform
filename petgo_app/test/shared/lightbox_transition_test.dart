import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/media/image_lightbox.dart';
import 'package:tailtopia/shared/media/lightbox_gestures.dart';

/// V1.3.0 批次 A · Story 3.3（L0）：灯箱过渡动画与加载态（FR-115 · AD-A15.5/.6 · AD-A26.1）。
///
/// 动画连不连贯、模糊转清晰好不好看是 L2；这里钉 L0 能钉的：
/// **Hero tag 的唯一性来源**（AC2，B1 上线后同图同屏会直接抛异常）、
/// **埋点值域四值**（AC5，`system_back` 是此前从没人管的那条路），以及失败重试的接线（AC4）。
void main() {
  const String night = 'asset:assets/demo_diary/demo_diary_night.jpg';
  const String balcony = 'asset:assets/demo_diary/demo_diary_balcony.jpg';

  group('AC2 🔴 Hero tag 由调用方前缀决定，不从 URL 推导', () {
    /// **同一张图在一屏内出现两次**（批次 B1 场所页的典型版式：头图 + 照片网格）。
    /// 若 tag 从 URL 推导，这一屏会有两个同 tag 的 Hero —— Flutter 当场抛异常。
    testWidgets('同图两处同屏不抛异常', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: Column(
            children: [
              // 头图
              SizedBox(
                height: 100,
                child: Hero(tag: lightboxHeroTag('place_7_cover', 0), child: Image.asset(
                    night.substring(6))),
              ),
              // 照片网格里的同一张
              SizedBox(
                height: 100,
                child: Hero(tag: lightboxHeroTag('place_7_grid', 0), child: Image.asset(
                    night.substring(6))),
              ),
            ],
          ),
        ),
      ));
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(find.byType(Hero), findsNWidgets(2));
    });

    /// 反过来：**同一个前缀 + 同一个下标**才该相等 —— 这是"缩略图那侧与查看器那侧
    /// 必须用同一个函数算 tag"的另一面。
    test('同前缀同下标 → 同 tag；任一不同 → 不同 tag', () {
      expect(lightboxHeroTag('p', 2), lightboxHeroTag('p', 2));
      expect(lightboxHeroTag('p', 2), isNot(lightboxHeroTag('p', 3)));
      expect(lightboxHeroTag('p', 2), isNot(lightboxHeroTag('q', 2)));
    });

    /// tag 的计算里不许出现 URL —— 直接看实现。
    test('实现不碰 url', () {
      final src = File('lib/shared/media/image_lightbox.dart').readAsStringSync();
      final line = src
          .split('\n')
          .firstWhere((l) => l.startsWith('String lightboxHeroTag('));
      expect(line, isNot(contains('url')));
    });

    testWidgets('查看器每一页都包了 Hero，tag 用传入的前缀', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: ImageLightbox(
          urls: [night, balcony],
          initialIndex: 0,
          heroTagPrefix: 'content_detail_42',
          source: 'content_detail',
        ),
      ));
      await tester.pumpAndSettle();

      final tags = tester
          .widgetList<Hero>(find.byType(Hero))
          .map((h) => h.tag)
          .toList();
      expect(tags, contains(lightboxHeroTag('content_detail_42', 0)));
    });
  });

  group('AC5 🔴 埋点：值域四值，system_back 不漏报', () {
    List<MapEntry<String, Map<String, Object>?>> sink() {
      final seen = <MapEntry<String, Map<String, Object>?>>[];
      Analytics.debugCaptureSink = (e, p) => seen.add(MapEntry(e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);
      return seen;
    }

    Future<void> openLightbox(WidgetTester tester) async {
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              key: const ValueKey('openIt'),
              onPressed: () => ImageLightbox.open(
                context,
                urls: const [night, balcony],
                initialIndex: 0,
                heroTagPrefix: 'content_detail_42',
                source: 'content_detail',
              ),
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.byKey(const ValueKey('openIt')));
      await tester.pumpAndSettle();
    }

    Map<String, Object>? dismissed(List<MapEntry<String, Map<String, Object>?>> seen) =>
        seen.lastWhere((e) => e.key == 'lightbox_dismissed').value;

    /// 🔴 值域定死四值。`swipe_down` / `swipeDown` / `drag` 三种写法指同一件事，
    /// 正是 AD-A26 要防的看板事故 —— 所以线上值只从枚举的 wire 出来。
    test('dismiss_gesture 的值域恰好是这四个', () {
      expect(
        LightboxDismissGesture.values.map((g) => g.wire).toList(),
        ['close_button', 'tap', 'swipe_down', 'system_back'],
      );
    });

    testWidgets('打开上报 lightbox_opened，带 source', (tester) async {
      final seen = sink();
      await openLightbox(tester);

      final opened = seen.firstWhere((e) => e.key == 'lightbox_opened').value!;
      expect(opened['source'], 'content_detail');
    });

    testWidgets('点 ✕ → close_button', (tester) async {
      final seen = sink();
      await openLightbox(tester);

      await tester.tap(find.byKey(const ValueKey('lightboxClose')));
      await tester.pumpAndSettle();

      expect(dismissed(seen)!['dismiss_gesture'], 'close_button');
    });

    testWidgets('单击图片 → tap', (tester) async {
      final seen = sink();
      await openLightbox(tester);

      await tester.tap(find.byKey(const ValueKey('lightboxPager')));
      await tester.pump(kLightboxSingleTapDelay + const Duration(milliseconds: 40));
      await tester.pumpAndSettle();

      expect(dismissed(seen)!['dismiss_gesture'], 'tap');
    });

    testWidgets('下滑关闭 → swipe_down', (tester) async {
      final seen = sink();
      await openLightbox(tester);

      await tester.drag(find.byKey(const ValueKey('lightboxPager')), const Offset(0, 400));
      await tester.pumpAndSettle();

      expect(dismissed(seen)!['dismiss_gesture'], 'swipe_down');
    });

    /// 🔴 **这条路径此前没人管**：系统返回键 / iOS 侧滑一直能退出灯箱却从不上报，
    /// 于是「关闭方式的分布」这份数据一直是错的。
    /// 上报放在 dispose，所以任何没被前三条认领的退出都会落到 system_back。
    testWidgets('系统返回键 → system_back（不得漏报）', (tester) async {
      final seen = sink();
      await openLightbox(tester);

      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();

      expect(seen.map((e) => e.key), contains('lightbox_dismissed'));
      expect(dismissed(seen)!['dismiss_gesture'], 'system_back');
    });

    testWidgets('关闭必带 source 与 max_zoom_used', (tester) async {
      final seen = sink();
      await openLightbox(tester);

      await tester.tap(find.byKey(const ValueKey('lightboxClose')));
      await tester.pumpAndSettle();

      final p = dismissed(seen)!;
      expect(p['source'], 'content_detail');
      expect(p['max_zoom_used'], isA<double>());
      expect(p['max_zoom_used'], greaterThanOrEqualTo(1.0));
    });

    /// 双击放大过就该记下来 —— `max_zoom_used` 衡量的是「用户到底放大着看没看」，
    /// 恒为 1 的话这个属性等于没有。
    testWidgets('双击放大后 max_zoom_used > 1', (tester) async {
      final seen = sink();
      await openLightbox(tester);

      final pager = find.byKey(const ValueKey('lightboxPager'));
      await tester.tap(pager);
      await tester.pump(const Duration(milliseconds: 60));
      await tester.tap(pager);
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('lightboxClose')));
      await tester.pumpAndSettle();

      expect(dismissed(seen)!['max_zoom_used'] as double, greaterThan(1.0));
    });
  });

  group('AC3/AC4 加载态与失败重试', () {
    /// 缩略图**沿用轮播现有的 1080 档，不另取一档**（AC3 明文要求）。
    /// 另取一档等于让同一张图在两处各缓存一份，白白多下一次。
    test('缩略图宽度沿用 1080，源码里只出现这一档', () {
      final src = File('lib/shared/media/image_lightbox.dart').readAsStringSync();
      expect(src, contains('_thumbWidth = 1080'));
      final carousel =
          File('lib/features/content/presentation/content_detail_page.dart').readAsStringSync();
      expect(carousel, contains('thumbWidth: 1080'));
    });

    testWidgets('加载失败 → 出提示与重试按钮，点了不会把灯箱关掉', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: Locale('en'),
        home: ImageLightbox(
          // 不存在的 asset → Image.asset 触发 errorBuilder。
          urls: ['asset:assets/does_not_exist.jpg'],
          initialIndex: 0,
          heroTagPrefix: 'p',
          source: 'content_detail',
        ),
      ));
      await tester.pumpAndSettle();

      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.byKey(const ValueKey('lightboxRetry_0')), findsOneWidget);
      expect(find.text(l10n.lightboxImageFailed), findsOneWidget);

      // 🔴 点重试**不能穿到底下的"单击关闭"**：用户点重试反而把灯箱关了是最气人的一种。
      await tester.tap(find.byKey(const ValueKey('lightboxRetryButton_0')));
      await tester.pump(kLightboxSingleTapDelay + const Duration(milliseconds: 60));
      await tester.pumpAndSettle();

      expect(find.byType(ImageLightbox), findsOneWidget);
      // 重试会换掉 Image 的 key（不换的话失败态就此固化，按钮点了也没反应）。
      expect(find.byKey(const ValueKey('lightboxImage_0_1')), findsOneWidget);
    });
  });

  group('AC1 关闭时缩回的是「用户停留的那一张」', () {
    /// 用户滑到第 2 张再关闭，调用方要能知道停在第 2 张 ——
    /// 否则图会缩回**打开时**那一张缩略图的位置，看上去像飞错了地方。
    testWidgets('open() 带回关闭时的下标', (tester) async {
      int? landed = -1;
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              key: const ValueKey('openIt'),
              onPressed: () async {
                landed = await ImageLightbox.open(
                  context,
                  urls: const [night, balcony],
                  initialIndex: 0,
                  heroTagPrefix: 'p',
                  source: 'content_detail',
                );
              },
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.byKey(const ValueKey('openIt')));
      await tester.pumpAndSettle();

      // 翻到第 2 张再点 ✕。
      await tester.drag(find.byKey(const ValueKey('lightboxPager')), const Offset(-500, 0));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('lightboxClose')));
      await tester.pumpAndSettle();

      expect(landed, 1);
    });
  });
}
