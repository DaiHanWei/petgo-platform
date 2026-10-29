import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/app_theme.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/like_repository.dart';
import 'package:tailtopia/features/content/domain/share_card_data.dart';
import 'package:tailtopia/features/content/presentation/share_card/share_card_template.dart';
import 'package:tailtopia/features/content/video/video_info.dart';
import 'package:tailtopia/features/content/video/video_like_action.dart';
import 'package:tailtopia/features/content/video/video_nav.dart';
import 'package:tailtopia/features/content/video/video_post_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_canvas.dart';
import 'package:tailtopia/shared/card_render/card_frame.dart';

/// 专题线 video · 2026-10-17 L2 走查遗留项的修复（竖排点赞 / 加载期先铺封面 / 分享卡视频角标）。
class _FakeLikeRepo implements LikeRepository {
  _FakeLikeRepo({this.fail = false});
  final bool fail;
  int likeCalls = 0;

  @override
  Future<LikeResult> like(int postId) async {
    likeCalls++;
    if (fail) throw Exception('network');
    return const LikeResult(liked: true, likeCount: 4);
  }

  @override
  Future<LikeResult> unlike(int postId) async {
    if (fail) throw Exception('network');
    return const LikeResult(liked: false, likeCount: 3);
  }
}

LoginResponse _user() => const LoginResponse(
    accessToken: 'a', refreshToken: 'r', role: 'USER', isNewUser: false, onboardingCompleted: true);

Future<ProviderContainer> _pumpLike(WidgetTester tester, _FakeLikeRepo repo,
    {void Function(bool, int)? onSettled}) async {
  final container = ProviderContainer(overrides: [likeRepositoryProvider.overrideWithValue(repo)]);
  addTearDown(container.dispose);
  container.read(authControllerProvider.notifier).applyLogin(_user());
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        backgroundColor: Colors.black,
        body: Center(
          child: VideoLikeAction(postId: 5, initialLiked: false, initialCount: 3, onSettled: onSettled),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  return container;
}

void main() {
  group('播放页竖排点赞', () {
    testWidgets('爱心在上、数字在下（与评论按钮同一排法）', (tester) async {
      await _pumpLike(tester, _FakeLikeRepo());
      final heart = tester.getCenter(find.byKey(const ValueKey('likeButton_5')));
      final count = tester.getCenter(find.byKey(const ValueKey('likeCount_5')));
      expect(count.dy, greaterThan(heart.dy));
      expect((count.dx - heart.dx).abs(), lessThan(1));
    });

    testWidgets('点爱心：乐观更新 + 以后端真值落定 + 回写 Feed', (tester) async {
      final repo = _FakeLikeRepo();
      (bool, int)? settled;
      await _pumpLike(tester, repo, onSettled: (l, c) => settled = (l, c));
      expect(find.text('3'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('likeButton_5')));
      await tester.pumpAndSettle();
      expect(find.text('4'), findsOneWidget);
      expect(repo.likeCalls, 1);
      expect(settled, (true, 4));
      final icon = tester.widget<Icon>(find.byIcon(Icons.favorite_rounded));
      expect(icon.color, isNot(Colors.white));
    });

    testWidgets('失败回滚且不回写', (tester) async {
      var settledCalls = 0;
      await _pumpLike(tester, _FakeLikeRepo(fail: true), onSettled: (_, _) => settledCalls++);
      await tester.tap(find.byKey(const ValueKey('likeButton_5')));
      await tester.pumpAndSettle();
      expect(find.text('3'), findsOneWidget);
      expect(find.byIcon(Icons.favorite_border_rounded), findsOneWidget);
      expect(settledCalls, 0);
    });
  });

  group('进播放页不先黑屏', () {
    test('列表带进来的视频宽高 → 画面比例；缺宽高为 null', () {
      expect(videoAspectOf(const VideoInfo(url: 'u', width: 720, height: 1280)), closeTo(0.5625, 1e-9));
      expect(videoAspectOf(const VideoInfo(url: 'u')), isNull);
      expect(videoAspectOf(null), isNull);
    });

    testWidgets('有封面：加载期间先铺封面，按视频比例居中', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: VideoPostLoading(coverUrl: 'https://x/cover.jpg', aspectRatio: 16 / 9),
      ));
      expect(find.byKey(const ValueKey('videoPostLoadingCover')), findsOneWidget);
      final box = tester.widget<AspectRatio>(find.ancestor(
          of: find.byKey(const ValueKey('videoPostLoadingCover')), matching: find.byType(AspectRatio)));
      expect(box.aspectRatio, closeTo(16 / 9, 1e-9));
    });

    testWidgets('没封面（通知 / 深链进来）：与原来一样只有转圈', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: VideoPostLoading()));
      expect(find.byKey(const ValueKey('videoPostLoadingCover')), findsNothing);
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
    });
  });

  group('分享卡视频角标', () {
    Future<void> pumpCard(WidgetTester tester, {required bool isVideo}) async {
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.light,
        localizationsDelegates: const [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: Center(
          child: SizedBox(
            width: 216,
            child: CardFrame(
              boundaryKey: GlobalKey(),
              canvas: CardCanvas.story,
              child: ShareCardTemplate(
                data: ShareCardData(
                  authorName: 'Sari',
                  type: 'DAILY',
                  shareUrl: 'https://tailtopia.id/p/AbCdEf123456',
                  imageUrl: 'https://x/cover.jpg',
                  isVideo: isVideo,
                ),
                canvas: CardCanvas.story,
              ),
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('视频帖：封面正中有播放角标', (tester) async {
      await pumpCard(tester, isVideo: true);
      final badge = find.byKey(const ValueKey('shareCardVideoBadge'));
      expect(badge, findsOneWidget);
      final area = tester.getRect(find.byKey(const ValueKey(ShareCardTemplate.shareCardImageAreaKey)));
      expect((tester.getCenter(badge) - area.center).distance, lessThan(1));
    });

    testWidgets('图文帖：没有角标（卡面不变）', (tester) async {
      await pumpCard(tester, isVideo: false);
      expect(find.byKey(const ValueKey('shareCardVideoBadge')), findsNothing);
    });
  });
}
