import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/domain/pinned_slot.dart';
import 'package:tailtopia/features/content/presentation/feed_view.dart';
import 'package:tailtopia/features/content/presentation/pinned_badge.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/feed_image.dart';
import 'package:tailtopia/shared/widgets/masonry_card.dart';

/// V1.1.6 Story 4.2：首页顶置坑位的渲染与埋点。
///
/// <p>守三件事：**顶置卡与普通卡同构**（不是另写一套）、**没有顶置时不留占位**、
/// 以及那三个埋点 —— 其中"重复曝光"是下游版本判断要不要做去重的**唯一数据来源**。
FeedItem _item({int id = 1, String? image = 'https://cdn.example.com/a.jpg'}) => FeedItem(
      id: id,
      authorId: 7,
      authorDeleted: false,
      authorNickname: 'Alice',
      type: 'DAILY',
      body: 'body $id',
      firstImageUrl: image,
      imageUrls: image == null ? const [] : [image],
      createdAt: DateTime.utc(2026, 6, 2),
    );

PinnedSlot _pin({int configId = 55, int contentId = 1}) =>
    PinnedSlot(pinConfigId: configId, pinType: 'CONTENT', item: _item(id: contentId));

Future<void> _pump(
  WidgetTester tester, {
  PinnedSlot? pinned,
  List<FeedItem> items = const [],
  ValueChanged<FeedItem>? onTapPinned,
}) async {
  await tester.pumpWidget(ProviderScope(
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: FeedMasonryView(
          items: items,
          hasMore: false,
          loadingMore: false,
          deletedUserLabel: 'Deleted user',
          onLoadMore: () async {},
          onRefresh: () async {},
          pinned: pinned,
          onTapPinned: onTapPinned,
        ),
      ),
    ),
  ));
  await tester.pump();
}

Future<void> _pumpWithMore(WidgetTester tester, {required PinnedSlot pinned}) async {
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
          onMoreItem: (_) {},
          onLongPressItem: (_) {},
        ),
      ),
    ),
  ));
  await tester.pump();
}

void main() {
  tearDown(() => Analytics.debugCaptureSink = null);

  group('AC1 与普通条目同构', () {
    /// 🛡 顶置卡用的是**同一个卡片组件**，只多挂一个角标 —— 新写一套是这条最容易走歪的地方。
    testWidgets('顶置卡就是普通卡 + 一个角标', (tester) async {
      await _pump(tester, pinned: _pin(), items: [_item(id: 2)]);

      expect(find.byKey(const ValueKey('feedPinnedCard')), findsOneWidget);
      expect(find.byType(MasonryCard), findsNWidgets(2)); // 顶置 + 普通，同一个组件
      expect(find.byType(PinnedBadge), findsOneWidget);
    });

    /// 🔴 举报入口也要在 —— AC 要求"常规互动入口位置不变"。
    ///
    /// 实机验收时才发现漏挂：只接了点击与评论，顶置卡就少一个「···」，
    /// 用户对顶置内容反而没法举报。这条把它钉住。
    testWidgets('顶置卡同样有「···」举报入口', (tester) async {
      await _pumpWithMore(tester, pinned: _pin(contentId: 9));
      expect(find.byKey(const ValueKey('feedCardMore_9')), findsOneWidget);
    });

    /// 🛡 角标挂在 Story 3.4 交付的**图片区右上角位**上，不是浮在卡片外面。
    testWidgets('角标落在图片区内的右上角', (tester) async {
      await _pump(tester, pinned: _pin());

      final area = tester.getRect(find.byType(FeedImage).first);
      final badge = tester.getRect(find.byType(PinnedBadge));

      expect(badge.top, greaterThanOrEqualTo(area.top));
      expect(badge.right, lessThanOrEqualTo(area.right));
      expect(area.right - badge.right, closeTo(8, 0.01));
      expect(badge.top - area.top, closeTo(8, 0.01));
    });
  });

  group('AC4 坑位为空', () {
    /// 🛡 无顶置 → **什么都不渲染、不留占位**，该位置由普通内容按正常排序填充。
    testWidgets('没有顶置时不渲染任何占位', (tester) async {
      await _pump(tester, pinned: null, items: [_item(id: 2)]);

      expect(find.byKey(const ValueKey('feedPinnedCard')), findsNothing);
      expect(find.byType(PinnedBadge), findsNothing);
      expect(find.byType(MasonryCard), findsOneWidget); // 只剩普通卡
    });
  });

  group('AC5 埋点', () {
    testWidgets('曝光带坑位标识、类型与内容编号', (tester) async {
      final seen = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => seen.add((e, p));

      await _pump(tester, pinned: _pin(configId: 55, contentId: 9));

      final viewed = seen.where((e) => e.$1 == 'social_pinned_slot_viewed').toList();
      expect(viewed, hasLength(1));
      expect(viewed.first.$2?['pin_config_id'], 55);
      expect(viewed.first.$2?['pin_type'], 'post');
      expect(viewed.first.$2?['content_id'], 9);
    });

    testWidgets('点击带跳转目标类型，且真的跳转', (tester) async {
      final seen = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => seen.add((e, p));
      FeedItem? tapped;

      await _pump(tester, pinned: _pin(contentId: 9), onTapPinned: (i) => tapped = i);
      await tester.tap(find.text('body 9'));
      await tester.pump();

      final tap = seen.where((e) => e.$1 == 'social_pinned_slot_tapped').toList();
      expect(tap, hasLength(1));
      expect(tap.first.$2?['jump_target'], 'post_detail');
      expect(tapped?.id, 9);
    });

    /// 🛡 这条是**下游版本判断"要不要为顶置做去重"的唯一数据来源**，必须带位次。
    ///
    /// ⚠️ 语义已随 AD-8 改写为"观测后续页的重复曝光" —— 第一页已经排除了，首屏不可能重复。
    testWidgets('被顶置的内容出现在列表里时上报重复曝光，带位次', (tester) async {
      final seen = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => seen.add((e, p));

      await _pump(tester,
          pinned: _pin(contentId: 9),
          items: [_item(id: 2), _item(id: 3), _item(id: 9)]);

      final dup = seen.where((e) => e.$1 == 'social_pinned_duplicate_viewed').toList();
      expect(dup, hasLength(1));
      expect(dup.first.$2?['content_id'], 9);
      expect(dup.first.$2?['serp_position'], 3);
    });

    testWidgets('列表里没有那条内容时不报重复曝光', (tester) async {
      final seen = <String>[];
      Analytics.debugCaptureSink = (e, p) => seen.add(e);

      await _pump(tester, pinned: _pin(contentId: 9), items: [_item(id: 2)]);

      expect(seen.where((e) => e.contains('duplicate')), isEmpty);
    });
  });

  group('线格式解析', () {
    test('无生效配置 → null；埋点类型做显式映射', () {
      expect(PinnedSlot.fromJson(const {}), isNull);
      expect(PinnedSlot.fromJson(const {'pin': null}), isNull);

      final slot = PinnedSlot.fromJson({
        'pin': {
          'pinConfigId': 7,
          'pinType': 'CONTENT',
          'item': {
            'id': 3,
            'authorId': 2,
            'type': 'DAILY',
            'createdAt': '2026-06-02T00:00:00Z',
          },
        },
      })!;
      expect(slot.pinConfigId, 7);
      expect(slot.item!.id, 3);
      // ⚠️ 线格式是 CONTENT，埋点口径是 post —— 两套词表刻意分开
      expect(slot.analyticsType, 'post');

      final promo = PinnedSlot.fromJson(const {
        'pin': {'pinConfigId': 8, 'pinType': 'PROMO'},
      })!;
      expect(promo.item, isNull);
      expect(promo.analyticsType, 'promo_card');
    });
  });
}
