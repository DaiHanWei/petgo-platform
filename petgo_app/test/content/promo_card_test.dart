import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/content/domain/pinned_slot.dart';
import 'package:tailtopia/features/content/presentation/feed_view.dart';
import 'package:tailtopia/features/content/presentation/pinned_badge.dart';
import 'package:tailtopia/features/content/presentation/promo_pinned_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/feed_image.dart';

/// V1.1.6 Story 4.3：推广卡片。
///
/// <p>守三件事：**不加广告标识**、**不编造推广卡没有的数据**、
/// 以及**认不出的跳转目标什么都不做**（运营填错一个字符不该让首页出问题）。
PinnedSlot _promoSlot({String? link = 'https://example.com/promo'}) => PinnedSlot(
      pinConfigId: 77,
      pinType: 'PROMO',
      item: null,
      promo: PromoCard(
        imageUrl: 'https://cdn.example.com/banner.jpg',
        title: 'Ikut lomba foto anabul!',
        linkUrl: link,
      ),
    );

Future<void> _pump(WidgetTester tester,
    {required PinnedSlot pinned, ValueChanged<PromoCard>? onTapPromo}) async {
  await tester.pumpWidget(ProviderScope(
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: FeedMasonryView(
          items: const [],
          hasMore: false,
          loadingMore: false,
          deletedUserLabel: 'Deleted user',
          onLoadMore: () async {},
          onRefresh: () async {},
          pinned: pinned,
          onTapPromo: onTapPromo,
        ),
      ),
    ),
  ));
  await tester.pump();
}

void main() {
  tearDown(() => Analytics.debugCaptureSink = null);

  group('AC2 渲染与不加广告标识', () {
    testWidgets('推广卡出现在坑位里，标题就是正文位', (tester) async {
      await _pump(tester, pinned: _promoSlot());

      expect(find.byType(PromoPinnedCard), findsOneWidget);
      expect(find.text('Ikut lomba foto anabul!'), findsOneWidget);
    });

    /// 🛡 与顶置的已发布内容**共用同一个角标**，视觉不作区分。
    testWidgets('顶置角标仍在图片区右上角', (tester) async {
      await _pump(tester, pinned: _promoSlot());

      final area = tester.getRect(find.byType(FeedImage));
      final badge = tester.getRect(find.byType(PinnedBadge));
      expect(area.right - badge.right, closeTo(8, 0.01));
      expect(badge.top - area.top, closeTo(8, 0.01));
    });

    /// 🛡 **不加"广告 / 推广"字样**（FR-68，本版本按平台自有活动引导定位）。
    testWidgets('页面上不出现任何广告字样', (tester) async {
      await _pump(tester, pinned: _promoSlot());

      for (final word in ['广告', '推广', 'Iklan', 'Ad', 'Sponsored', 'Promosi']) {
        expect(find.text(word), findsNothing, reason: '不该出现「$word」');
      }
    });

    /// 🔴 推广卡**没有作者、没有点赞评论、没有时间** —— 那三块不渲染，而不是编造。
    ///
    /// UI 稿屏 02 画的其实是一张普通内容卡的数据（作者名 / 点赞 212 / 评论 27 / 时间），
    /// 照抄意味着给用户看编造出来的东西。这条把"不编造"钉住。
    testWidgets('不渲染作者行、操作行与时间行', (tester) async {
      await _pump(tester, pinned: _promoSlot());

      expect(find.byIcon(Icons.mode_comment_outlined), findsNothing);
      expect(find.byIcon(Icons.favorite_border), findsNothing);
      expect(find.byIcon(Icons.more_horiz_rounded), findsNothing);
      expect(find.textContaining('ago'), findsNothing);
      expect(find.textContaining('lalu'), findsNothing);
    });
  });

  group('AC1 跳转目标', () {
    test('三种目标的判定', () {
      expect(PromoJumpTarget.of('https://example.com'), PromoJumpTarget.externalUrl);
      expect(PromoJumpTarget.of('http://example.com'), PromoJumpTarget.externalUrl);
      expect(PromoJumpTarget.of('tailtopia://card/abc'), PromoJumpTarget.deeplink);
      // 🔴 认不出 / 空 → 什么都不做
      expect(PromoJumpTarget.of(null), PromoJumpTarget.unknown);
      expect(PromoJumpTarget.of(''), PromoJumpTarget.unknown);
      expect(PromoJumpTarget.of('ftp://x'), PromoJumpTarget.unknown);
      expect(PromoJumpTarget.of('随手打的字'), PromoJumpTarget.unknown);
    });

    testWidgets('外链点击上报 external_url 并触发回调', (tester) async {
      final seen = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => seen.add((e, p));
      PromoCard? tapped;

      await _pump(tester, pinned: _promoSlot(), onTapPromo: (p) => tapped = p);
      await tester.tap(find.byType(PromoPinnedCard));
      await tester.pump();

      final tap = seen.where((e) => e.$1 == 'social_pinned_slot_tapped').toList();
      expect(tap, hasLength(1));
      expect(tap.first.$2?['pin_type'], 'promo_card');
      expect(tap.first.$2?['jump_target'], 'external_url');
      // 推广卡片没有内容编号 → 不带该属性
      expect(tap.first.$2?.containsKey('content_id'), isFalse);
      expect(tapped, isNotNull);
    });

    testWidgets('深链点击上报 deeplink', (tester) async {
      final seen = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => seen.add((e, p));

      await _pump(tester,
          pinned: _promoSlot(link: 'tailtopia://card/abc'), onTapPromo: (_) {});
      await tester.tap(find.byType(PromoPinnedCard));
      await tester.pump();

      expect(seen.firstWhere((e) => e.$1 == 'social_pinned_slot_tapped').$2?['jump_target'],
          'deeplink');
    });

    /// 🔴 运营填错一个字符 → 卡片不可点，**不崩、不弹错、不上报点击**。
    testWidgets('认不出的目标 → 点了什么都不发生', (tester) async {
      final seen = <String>[];
      Analytics.debugCaptureSink = (e, p) => seen.add(e);
      var called = 0;

      await _pump(tester,
          pinned: _promoSlot(link: '运营手滑填的东西'), onTapPromo: (_) => called++);
      await tester.tap(find.byType(PromoPinnedCard));
      await tester.pump();

      expect(called, 0);
      expect(seen.where((e) => e.endsWith('_tapped')), isEmpty);
    });

    testWidgets('无跳转目标 = 纯展示卡，不可点', (tester) async {
      var called = 0;
      await _pump(tester, pinned: _promoSlot(link: null), onTapPromo: (_) => called++);
      await tester.tap(find.byType(PromoPinnedCard));
      await tester.pump();
      expect(called, 0);
    });
  });

  group('AC1 线格式解析', () {
    test('图片或标题缺失 → 当作没有这张卡', () {
      PromoCard? parse(Map<String, dynamic> m) => PromoCard.fromJson(m);
      expect(parse({'title': 't'}), isNull);
      expect(parse({'imageUrl': 'x'}), isNull);
      expect(parse({'imageUrl': '', 'title': 't'}), isNull);
      expect(parse({'imageUrl': 'x', 'title': ''}), isNull);

      final ok = parse({'imageUrl': 'x', 'title': 't', 'linkUrl': ''})!;
      expect(ok.linkUrl, isNull, reason: '空字符串 = 没配跳转目标');
      expect(ok.jumpTarget, PromoJumpTarget.unknown);
    });

    test('坑位下发推广卡片时 item 为空、埋点类型是 promo_card', () {
      final slot = PinnedSlot.fromJson({
        'pin': {
          'pinConfigId': 77,
          'pinType': 'PROMO',
          'promo': {'imageUrl': 'x', 'title': 't', 'linkUrl': 'https://e.com'},
        },
      })!;
      expect(slot.item, isNull);
      expect(slot.promo!.title, 't');
      expect(slot.analyticsType, 'promo_card');
    });
  });
}
