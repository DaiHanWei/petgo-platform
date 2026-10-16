import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/domain/shared_post.dart';
import 'package:tailtopia/features/content/video/video_card_impression.dart';
import 'package:tailtopia/features/content/video/video_info.dart';
import 'package:tailtopia/features/content/video/video_nav.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/masonry_card.dart';
import 'package:tailtopia/shared/widgets/post_grid_tile.dart';

/// L0：专题线 video Story 2.3 —— 模型解析、列表角标、曝光去重、跳转位置。
Map<String, dynamic> _json({String? mediaType, Map<String, dynamic>? video}) => {
      'id': 9,
      'authorId': 7,
      'type': 'KNOWLEDGE',
      'firstImageUrl': 'https://cdn.test/public/7/c.jpg',
      'imageUrls': ['https://cdn.test/public/7/c.jpg'],
      'imageSizes': [
        {'w': 720, 'h': 1280}
      ],
      'createdAt': '2026-10-16T00:00:00Z',
      'mediaType': ?mediaType,
      'video': ?video,
    };

const _video = {'url': 'https://cdn.test/video/7/k.mp4', 'durationMs': 75000, 'width': 720, 'height': 1280};

FeedItem _videoItem() => FeedItem.fromJson(_json(mediaType: 'VIDEO', video: _video));

void main() {
  group('模型解析（只看 mediaType）', () {
    test('视频帖：mediaType=VIDEO + video', () {
      final f = _videoItem();
      expect(f.isVideo, isTrue);
      expect(f.video!.durationMs, 75000);
      expect(f.video!.width, 720);
      expect(ContentDetail.fromJson({..._json(mediaType: 'VIDEO', video: _video), 'isAuthor': false}).isVideo,
          isTrue);
      expect(
          SharedPost.fromJson(_json(mediaType: 'VIDEO', video: _video), fallbackAuthorName: 'x').isVideo, isTrue);
    });

    test('老后端（无 mediaType）/ 未知 mediaType → 按图文', () {
      expect(FeedItem.fromJson(_json()).isVideo, isFalse);
      expect(FeedItem.fromJson(_json()).mediaType, kMediaTypeImage);
      expect(FeedItem.fromJson(_json(mediaType: 'LIVE', video: _video)).isVideo, isFalse);
    });

    test('有 video 但 mediaType=IMAGE → 不是视频帖（不以 video != null 判断）', () {
      expect(FeedItem.fromJson(_json(mediaType: 'IMAGE', video: _video)).isVideo, isFalse);
    });

    test('mediaType=VIDEO 但缺 video → 按图文降级，不崩', () {
      expect(FeedItem.fromJson(_json(mediaType: 'VIDEO')).isVideo, isFalse);
    });

    test('copyWith 回写计数时保留视频信息', () {
      final f = _videoItem().copyWith(likeCount: 3);
      expect(f.isVideo, isTrue);
      expect(f.likeCount, 3);
    });
  });

  group('列表角标', () {
    Future<void> pumpCard(WidgetTester tester, FeedItem item) async {
      await tester.pumpWidget(ProviderScope(
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: SingleChildScrollView(
              child: MasonryCard(item: item, deletedUserLabel: 'Deleted user', maxImageHeight: 900),
            ),
          ),
        ),
      ));
      await tester.pump();
    }

    testWidgets('视频帖卡片：播放角标 + 1:15', (tester) async {
      await pumpCard(tester, _videoItem());
      expect(find.byKey(const ValueKey('videoCardBadge')), findsOneWidget);
      expect(find.text('1:15'), findsOneWidget);
    });

    testWidgets('图文帖卡片：没有角标', (tester) async {
      await pumpCard(tester, FeedItem.fromJson(_json()));
      expect(find.byKey(const ValueKey('videoCardBadge')), findsNothing);
    });

    testWidgets('网格格子：视频帖 0:48', (tester) async {
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: SizedBox(
          width: 180,
          height: 180,
          child: PostGridTile(postId: 1, type: 'DAILY', onTap: () {}, videoDurationMs: 48000),
        ),
      ));
      expect(find.text('0:48'), findsOneWidget);
    });

    test('卡片比例用视频显示宽高（imageSizes），沿用首图规则', () {
      expect((_videoItem().firstImageSize!.w, _videoItem().firstImageSize!.h), (720, 1280));
    });
  });

  group('曝光', () {
    test('可见比例：交集高度 ÷ 自身高度', () {
      const viewport = Rect.fromLTWH(0, 0, 400, 800);
      expect(VideoCardImpression.visibleFraction(const Rect.fromLTWH(0, 0, 200, 300), viewport), 1.0);
      expect(VideoCardImpression.visibleFraction(const Rect.fromLTWH(0, 650, 200, 300), viewport), 0.5);
      expect(VideoCardImpression.visibleFraction(const Rect.fromLTWH(0, 700, 200, 300), viewport),
          closeTo(1 / 3, 1e-9));
      expect(VideoCardImpression.visibleFraction(const Rect.fromLTWH(0, 900, 200, 300), viewport), 0);
    });

    test('同一次刷新内只计一次；新的一次刷新重新计', () {
      final t = VideoImpressionTracker();
      expect(t.markShown(1), isTrue);
      expect(t.markShown(1), isFalse);
      expect(VideoImpressionTracker().markShown(1), isTrue);
    });

    testWidgets('露出一半以上上报 video_card_shown{post_id, source}，重建不重复', (tester) async {
      final events = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);
      final tracker = VideoImpressionTracker();
      Widget build() => MaterialApp(
            home: ListView(children: [
              VideoCardImpression(
                postId: 9,
                source: VideoSource.feed,
                tracker: tracker,
                child: const SizedBox(height: 200),
              ),
            ]),
          );
      await tester.pumpWidget(build());
      await tester.pump();
      await tester.pumpWidget(build());
      await tester.pump();
      final shown = events.where((e) => e.$1 == 'video_card_shown').toList();
      expect(shown, hasLength(1));
      expect(shown.single.$2, {'post_id': 9, 'source': 'feed'});
    });

    testWidgets('滚出屏幕下方（露出不足一半）不上报', (tester) async {
      final events = <String>[];
      Analytics.debugCaptureSink = (e, p) => events.add(e);
      addTearDown(() => Analytics.debugCaptureSink = null);
      await tester.pumpWidget(MaterialApp(
        home: ListView(children: [
          const SizedBox(height: 2000),
          VideoCardImpression(
            postId: 9,
            source: VideoSource.feed,
            tracker: VideoImpressionTracker(),
            child: const SizedBox(height: 200),
          ),
        ]),
      ));
      await tester.pump();
      expect(events, isNot(contains('video_card_shown')));
    });
  });

  group('曝光（code review 补测）', () {
    late List<(String, Map<String, Object>?)> events;
    setUp(() {
      events = [];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    });
    tearDown(() => Analytics.debugCaptureSink = null);
    int shownCount() => events.where((e) => e.$1 == 'video_card_shown').length;

    testWidgets('外层列表里的 shrinkWrap 网格：滚进视口后才上报（监听外层滚动）', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: ListView(children: [
          const SizedBox(height: 1500),
          VideoImpressionScope(
            child: GridView.count(
              crossAxisCount: 2,
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              children: [
                Builder(
                  builder: (c) => VideoCardImpression(
                    postId: 5,
                    source: VideoSource.myPosts,
                    tracker: VideoImpressionScope.of(c),
                    child: const SizedBox(height: 200),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 1500),
        ]),
      ));
      await tester.pump();
      expect(shownCount(), 0);
      await tester.drag(find.byType(ListView), const Offset(0, -1400));
      await tester.pumpAndSettle();
      expect(shownCount(), 1);
    });

    testWidgets('可视区扣掉 AppBar / 底部栏：卡片大半落在底部栏下方不上报', (tester) async {
      // 屏幕 800 高；列表视口 = 56..710。卡片 300 高，顶在 620 → 视口内只露 90（30%）。
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          appBar: AppBar(),
          bottomNavigationBar: const SizedBox(height: 90),
          body: ListView(children: [
            const SizedBox(height: 564),
            VideoCardImpression(
              postId: 6,
              source: VideoSource.feed,
              tracker: VideoImpressionTracker(),
              child: const SizedBox(height: 300),
            ),
          ]),
        ),
      ));
      await tester.pump();
      expect(shownCount(), 0);
    });

    testWidgets('切走的 Tab（TickerMode 关闭）不上报', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: TickerMode(
          enabled: false,
          child: ListView(children: [
            VideoCardImpression(
              postId: 7,
              source: VideoSource.feed,
              tracker: VideoImpressionTracker(),
              child: const SizedBox(height: 200),
            ),
          ]),
        ),
      ));
      await tester.pump();
      expect(shownCount(), 0);
    });
  });

  group('跳转', () {
    test('视频帖带 src，图文帖与改动前一致', () {
      expect(contentDetailLocation(9, isVideo: true, source: VideoSource.category), '/content/9?src=category');
      expect(contentDetailLocation(9, isVideo: false, source: VideoSource.category), '/content/9');
      expect(contentDetailLocation(9, isVideo: true, source: VideoSource.following, focusComments: true),
          '/content/9?focus=comments&src=following');
      expect(contentDetailLocation(9, focusComments: true), '/content/9?focus=comments');
    });

    test('src 解析：缺省 / 未知 → other', () {
      expect(VideoSource.parse('my_posts'), VideoSource.myPosts);
      expect(VideoSource.parse(null), VideoSource.other);
      expect(VideoSource.parse('zzz'), VideoSource.other);
    });
  });
}
