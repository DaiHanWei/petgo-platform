import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/presentation/content_detail_page.dart';
import 'package:tailtopia/features/content/presentation/detail_providers.dart';
import 'package:tailtopia/features/content/video/video_info.dart';
import 'package:tailtopia/features/content/video/video_player_controller_provider.dart';
import 'package:tailtopia/features/content/video/video_player_view.dart';
import 'package:tailtopia/features/social/domain/follow_state.dart';
import 'package:tailtopia/features/social/presentation/widgets/follow_button.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0 widget：专题线 video Story 2.5 —— 播放页叠加层（作者区 / 正文 / 互动 / 评论抽屉 / 举报或删除）。
ContentDetail _video({bool isAuthor = false, bool authorDeleted = false}) => ContentDetail(
      id: 5,
      authorId: 7,
      authorDeleted: authorDeleted,
      authorNickname: 'Alice',
      type: 'DAILY',
      body: 'Kucing lucu',
      imageUrls: const [],
      likeCount: 3,
      commentCount: 2,
      liked: false,
      isAuthor: isAuthor,
      createdAt: DateTime.utc(2026, 10, 16),
      authorToken: authorDeleted ? null : 't' * 32,
      authorFollowState: FollowState.none,
      mediaType: kMediaTypeVideo,
      video: const VideoInfo(url: 'https://v.test/video/7/k.mp4', durationMs: 10000, width: 720, height: 1280),
    );

class _Repo implements DetailRepository {
  _Repo(this.detail);

  final ContentDetail detail;
  int deletes = 0;
  int gets = 0;
  bool failNext = false;

  @override
  Future<ContentDetail> getDetail(int id) async {
    gets++;
    if (failNext) throw const ContentLoadError(ContentLoadErrorKind.network);
    return detail;
  }
  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);
  @override
  Future<CommentPage> getReplies(int parentId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);
  @override
  Future<void> deleteContent(int postId) async => deletes++;
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Player implements VideoPlaybackController {
  final ValueNotifier<VideoPlaybackState> _s = ValueNotifier(const VideoPlaybackState());
  final List<String> calls = [];
  @override
  ValueListenable<VideoPlaybackState> get state => _s;
  @override
  Future<void> initialize() async {}
  @override
  Future<void> play() async {
    calls.add('play');
    _s.value = const VideoPlaybackState(initialized: true, playing: true, duration: Duration(seconds: 10));
  }

  @override
  Future<void> pause() async => calls.add('pause');
  @override
  Future<void> seekTo(Duration position) async {}
  @override
  Future<void> setVolume(double volume) async {}
  @override
  Future<void> setLooping(bool looping) async {}
  @override
  Widget buildView() => const SizedBox();
  @override
  Future<void> dispose() async {}
}

void main() {
  late _Player player;
  late int playersCreated;
  late ProviderContainer container;

  Future<_Repo> pump(WidgetTester tester, ContentDetail d, {bool focusComments = false}) async {
    tester.view.physicalSize = const Size(1080, 2340);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    player = _Player();
    playersCreated = 0;
    final repo = _Repo(d);
    container = ProviderContainer(overrides: [
      detailRepositoryProvider.overrideWithValue(repo),
      videoPlaybackControllerFactoryProvider.overrideWithValue((_) {
        playersCreated++;
        return player;
      }),
      videoWatchStoreLoaderProvider.overrideWithValue(() async => null),
    ]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
        home: ContentDetailPage(postId: 5, focusComments: focusComments),
      ),
    ));
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    return repo;
  }

  Future<AppLocalizations> l10n() => AppLocalizations.delegate.load(const Locale('en'));

  testWidgets('叠加层：作者、正文、点赞 / 评论 / 分享 / 更多', (tester) async {
    await pump(tester, _video());
    expect(find.text('Alice'), findsOneWidget);
    expect(find.text('Kucing lucu'), findsOneWidget);
    expect(find.byKey(const ValueKey('videoActions')), findsOneWidget);
    expect(find.byKey(const ValueKey('videoCommentButton')), findsOneWidget);
    expect(find.byType(FollowButton), findsOneWidget);
  });

  testWidgets('他人看到「举报」，作者本人看到「删除」（与图文帖一致）', (tester) async {
    await pump(tester, _video());
    await tester.tap(find.byKey(const ValueKey('videoMoreButton')));
    await tester.pumpAndSettle();
    final t = await l10n();
    expect(find.text(t.detailMoreReportContent), findsOneWidget);
    expect(find.text(t.detailMoreDeleteContent), findsNothing);
  });

  testWidgets('作者本人：「删除」→ 二次确认 → 调删除；不显示关注按钮', (tester) async {
    final repo = await pump(tester, _video(isAuthor: true));
    expect(find.byType(FollowButton), findsNothing);
    await tester.tap(find.byKey(const ValueKey('videoMoreButton')));
    await tester.pumpAndSettle();
    final t = await l10n();
    await tester.tap(find.text(t.detailMoreDeleteContent));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('confirmDeleteContent')));
    await tester.pumpAndSettle();
    expect(repo.deletes, 1);
  });

  testWidgets('点评论：弹出评论抽屉，视频继续播放', (tester) async {
    await pump(tester, _video());
    await tester.tap(find.byKey(const ValueKey('videoCommentButton')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('videoCommentsSheet')), findsOneWidget);
    expect(player.calls, isNot(contains('pause')));
  });

  testWidgets('评论抽屉打开时画面收到上方（不被抽屉挡住），关上后恢复', (tester) async {
    await pump(tester, _video());
    final frame = find.byKey(const ValueKey('videoCompactFrame'));
    EdgeInsetsGeometry padding() => tester.widget<AnimatedPadding>(frame).padding;
    expect(padding(), EdgeInsets.zero);
    await tester.tap(find.byKey(const ValueKey('videoCommentButton')));
    await tester.pumpAndSettle();
    final open = padding() as EdgeInsets;
    // 底部让出抽屉高度（屏高 780 × 0.6）。
    expect(open.bottom, closeTo(780 * (1 - kVideoCompactTopFraction), 0.1));
    Navigator.of(tester.element(find.byKey(const ValueKey('videoCommentsSheet')))).pop();
    await tester.pumpAndSettle();
    expect(padding(), EdgeInsets.zero);
  });

  testWidgets('focus=comments 进来：自动打开一次评论抽屉', (tester) async {
    await pump(tester, _video(), focusComments: true);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('videoCommentsSheet')), findsOneWidget);
  });

  testWidgets('注销作者：作者区不可点、无关注按钮', (tester) async {
    await pump(tester, _video(authorDeleted: true));
    expect(find.byKey(const ValueKey('detailAuthorRow')), findsNothing);
    expect(find.byType(FollowButton), findsNothing);
  });

  testWidgets('作者区复用 ContentAuthorRow（与图文详情同一组件）', (tester) async {
    await pump(tester, _video());
    expect(find.byType(ContentAuthorRow), findsOneWidget);
  });

  testWidgets('发评论后详情重拉失败：保留播放页，不重建播放器（code review）', (tester) async {
    final repo = await pump(tester, _video());
    repo.failNext = true;
    container.read(commentsRefreshProvider.notifier).bump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(repo.gets, 2);
    expect(find.byKey(const ValueKey('videoPostPage')), findsOneWidget);
    expect(playersCreated, 1);
    // Riverpod 3 对失败的 FutureProvider 自动重试（带退避计时器）：放行让它成功收尾。
    repo.failNext = false;
    await tester.pump(const Duration(seconds: 10));
    expect(playersCreated, 1);
  });

  testWidgets('发评论后详情重拉成功：播放器不重建', (tester) async {
    final repo = await pump(tester, _video());
    container.read(commentsRefreshProvider.notifier).bump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(repo.gets, 2);
    expect(playersCreated, 1);
  });
}
