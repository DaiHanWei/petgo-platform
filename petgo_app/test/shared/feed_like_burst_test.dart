import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/like_repository.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/presentation/like_button.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/feed_image.dart';
import 'package:tailtopia/shared/widgets/feed_like_burst.dart';

/// V1.5.0 likes Story 4.2 · AC5（FR-211，F-7 / F-8）：双击点赞大红心的**结构断言**（不写 golden）。
const _heart = ValueKey('feedLikeBurstHeart');
const _small = ValueKey('feedLikeBurstSmallHeart');
const _dots = ValueKey('feedLikeBurstDots');
const _pin = ValueKey('pin');
const _deco = ValueKey('deco');

const _three = ['https://cdn.example.com/a.jpg', 'https://cdn.example.com/b.jpg', 'https://cdn.example.com/c.jpg'];

Future<void> _pumpImage(
  WidgetTester tester, {
  FeedLikeBurstController? burst,
  ImageSize? size = const ImageSize(1200, 1200),
  double maxH = 900,
  bool reduced = false,
  List<String> urls = _three,
}) async {
  tester.view.physicalSize = const Size(390, 844);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(MediaQuery(
    data: MediaQueryData(size: const Size(390, 844), disableAnimations: reduced),
    child: MaterialApp(
      home: Scaffold(
        body: Align(
          alignment: Alignment.topLeft,
          child: SizedBox(
            width: 390,
            child: FeedImage(
              urls: urls,
              type: 'DAILY',
              declaredSize: size,
              width: 390,
              maxImageHeight: maxH,
              sizes: [size],
              topRight: const SizedBox(key: _pin, width: 40, height: 20),
              bottomLeft: const SizedBox(key: _deco, width: 60, height: 20),
              likeBurst: burst,
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.pump();
}

Rect _visibleArea(WidgetTester tester) =>
    tester.getRect(find.descendant(of: find.byType(FeedImage), matching: find.byType(AspectRatio)));

void main() {
  test('8. 新增颜色常量存在，likeHeart 仍等于 coral', () {
    expect(AppColors.likeHeartLight, const Color(0xFFFF8FA0));
    expect(AppColors.likeHeartDeep, const Color(0xFFD42A55));
    expect(AppColors.likeHeart, AppColors.coral);
  });

  testWidgets('1. 有动效层时三个角位（圆点 / 右上 / 左下）位置与无动效层时完全相同', (tester) async {
    await _pumpImage(tester);
    final pin = tester.getRect(find.byKey(_pin));
    final deco = tester.getRect(find.byKey(_deco));
    final dots = tester.getRect(find.byWidgetPredicate((w) => w.runtimeType.toString() == '_CarouselDots'));

    final burst = FeedLikeBurstController();
    await _pumpImage(tester, burst: burst);
    burst.play();
    await tester.pump(); // 动画在 play 后的第一帧开始计时
    await tester.pump(const Duration(milliseconds: 200));
    expect(tester.getRect(find.byKey(_pin)), pin);
    expect(tester.getRect(find.byKey(_deco)), deco);
    expect(tester.getRect(find.byWidgetPredicate((w) => w.runtimeType.toString() == '_CarouselDots')), dots);
    await tester.pump(const Duration(seconds: 1));
  });

  testWidgets('2. 动效层是 IgnorePointer；未传 likeBurst 时不加这一层', (tester) async {
    await _pumpImage(tester);
    expect(find.byType(FeedLikeBurst), findsNothing);

    await _pumpImage(tester, burst: FeedLikeBurstController());
    final ignore = tester.widget<IgnorePointer>(
        find.descendant(of: find.byType(FeedLikeBurst), matching: find.byType(IgnorePointer)).first);
    expect(ignore.ignoring, isTrue);
  });

  testWidgets('2b. 播放期间点图片区，点击仍落到下层手势', (tester) async {
    final burst = FeedLikeBurstController();
    var taps = 0;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Align(
          alignment: Alignment.topLeft,
          child: GestureDetector(
            onTap: () => taps++,
            child: SizedBox(
              width: 390,
              child: FeedImage(
                urls: const ['https://cdn.example.com/a.jpg'],
                type: 'DAILY',
                declaredSize: const ImageSize(1200, 1200),
                width: 390,
                maxImageHeight: 900,
                likeBurst: burst,
              ),
            ),
          ),
        ),
      ),
    ));
    burst.play();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.byKey(_heart), findsOneWidget);
    await tester.tap(find.byType(FeedImage));
    await tester.pump();
    expect(taps, 1);
    await tester.pump(const Duration(seconds: 1));
  });

  testWidgets('stop：立即清空（卡片复用给另一条帖子时）', (tester) async {
    final burst = FeedLikeBurstController();
    await _pumpImage(tester, burst: burst);
    burst.play();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.byKey(_heart), findsOneWidget);
    burst.stop();
    await tester.pump();
    expect(find.byKey(_heart), findsNothing);
  });

  testWidgets('3. 播放：大心、小心（晚 70ms）、圆点出现；670ms 后全部移出树', (tester) async {
    final burst = FeedLikeBurstController();
    await _pumpImage(tester, burst: burst);
    expect(find.byKey(_heart), findsNothing);
    burst.play();
    await tester.pump(); // 动画在 play 后的第一帧开始计时
    await tester.pump(const Duration(milliseconds: 40));
    expect(find.byKey(_heart), findsOneWidget);
    expect(find.byKey(_small), findsNothing);
    await tester.pump(const Duration(milliseconds: 60)); // 100ms
    expect(find.byKey(_small), findsOneWidget);
    expect(find.byKey(_dots), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 580)); // 680ms
    expect(find.byKey(_heart), findsNothing);
    expect(find.byKey(_small), findsNothing);
    expect(find.byKey(_dots), findsNothing);
  });

  testWidgets('4. 减少动态效果：无缩放 / 小心 / 圆点，只有透明度，约 400ms 结束', (tester) async {
    final burst = FeedLikeBurstController();
    await _pumpImage(tester, burst: burst, reduced: true);
    burst.play();
    await tester.pump(); // 动画在 play 后的第一帧开始计时
    await tester.pump(const Duration(milliseconds: 150));
    expect(find.byKey(_heart), findsOneWidget);
    expect(find.byKey(_small), findsNothing);
    expect(find.byKey(_dots), findsNothing);
    expect(find.descendant(of: find.byType(FeedLikeBurst), matching: find.byType(Transform)), findsNothing);
    expect(find.descendant(of: find.byKey(_heart), matching: find.byType(Opacity)), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 260)); // 410ms
    expect(find.byKey(_heart), findsNothing);
  });

  testWidgets('5. 播放中再 play → 从 0.27 处重播，屏上只有一组心', (tester) async {
    final burst = FeedLikeBurstController();
    await _pumpImage(tester, burst: burst);
    burst.play();
    await tester.pump(); // 动画在 play 后的第一帧开始计时
    await tester.pump(const Duration(milliseconds: 300));
    burst.play();
    await tester.pump(); // 动画在 play 后的第一帧开始计时
    await tester.pump(const Duration(milliseconds: 1));
    expect(find.byKey(_heart), findsOneWidget);
    expect(find.byKey(_small), findsOneWidget); // 0.27 × 670 ≈ 181ms，小心已出现
    // 剩余 (1 - 0.27) × 670 ≈ 489ms：原定结束时刻（再过 370ms）仍在播
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.byKey(_heart), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 120));
    expect(find.byKey(_heart), findsNothing);
  });

  testWidgets('6. 播放中卡片移出树 → 无残留、无异常', (tester) async {
    final burst = FeedLikeBurstController();
    await _pumpImage(tester, burst: burst);
    burst.play();
    await tester.pump(); // 动画在 play 后的第一帧开始计时
    await tester.pump(const Duration(milliseconds: 100));
    await tester.pumpWidget(const MaterialApp(home: Scaffold(body: Text('gone'))));
    await tester.pump(const Duration(seconds: 1));
    expect(find.byType(FeedLikeBurst), findsNothing);
    expect(tester.takeException(), isNull);
    burst.dispose();
  });

  group('7. 心中心 = 可见图片区 (0.6w, 0.5h)（B-AC19）', () {
    Future<void> check(WidgetTester tester, {ImageSize? size, double maxH = 900}) async {
      final burst = FeedLikeBurstController();
      await _pumpImage(tester, burst: burst, size: size, maxH: maxH, urls: const ['https://cdn.example.com/a.jpg']);
      burst.play();
    await tester.pump(); // 动画在 play 后的第一帧开始计时
      await tester.pump(const Duration(milliseconds: 300)); // 停留段：无上飘
      final area = _visibleArea(tester);
      final c = tester.getCenter(find.byKey(_heart));
      expect(c.dx, moreOrLessEquals(area.left + area.width * 0.6, epsilon: 0.01));
      expect(c.dy, moreOrLessEquals(area.top + area.height * 0.5, epsilon: 0.01));
      await tester.pump(const Duration(seconds: 1));
    }

    testWidgets('横图（比例上限）', (t) => check(t, size: const ImageSize(3000, 1000)));
    testWidgets('方图（含无尺寸存量图占位）', (t) => check(t, size: null));
    testWidgets('竖图（比例下限）', (t) => check(t, size: const ImageSize(1000, 3000)));
    testWidgets('被 maxImageHeight 截高的长图', (t) => check(t, size: const ImageSize(1000, 1300), maxH: 300));
  });

  group('爱心按钮弹跳（AC4）', () {
    LoginResponse user() => const LoginResponse(
        accessToken: 'a', refreshToken: 'r', role: 'USER', isNewUser: false, onboardingCompleted: true);

    Future<ProviderContainer> pumpButton(WidgetTester tester, {bool liked = false, bool reduced = false}) async {
      final c = ProviderContainer(overrides: [likeRepositoryProvider.overrideWithValue(_Repo())]);
      addTearDown(c.dispose);
      c.read(authControllerProvider.notifier).applyLogin(user());
      await tester.pumpWidget(UncontrolledProviderScope(
        container: c,
        child: MediaQuery(
          data: MediaQueryData(disableAnimations: reduced),
          child: MaterialApp(
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            home: Scaffold(
              body: LikeButton(
                  postId: 5,
                  initialLiked: liked,
                  initialCount: 3,
                  source: 'feed',
                  likersSource: 'feed_card'),
            ),
          ),
        ),
      ));
      await tester.pump();
      return c;
    }

    double scale(WidgetTester tester) => tester
        .widget<ScaleTransition>(find.descendant(
            of: find.byKey(const ValueKey('likeButton_5')), matching: find.byType(ScaleTransition)))
        .scale
        .value;

    testWidgets('点爱心点赞 → 图标 1.0→1.2→1.0；热区（InkWell）尺寸不变', (tester) async {
      await pumpButton(tester);
      final hit = tester.getSize(find.byKey(const ValueKey('likeButton_5')));
      await tester.tap(find.byKey(const ValueKey('likeButton_5')));
      await tester.pump(); // 弹跳在点击后的第一帧开始计时
      await tester.pump(const Duration(milliseconds: 100));
      expect(scale(tester), greaterThan(1.1));
      expect(tester.getSize(find.byKey(const ValueKey('likeButton_5'))), hit);
      await tester.pumpAndSettle();
      expect(scale(tester), 1.0);
    });

    testWidgets('取消点赞 → 不弹', (tester) async {
      await pumpButton(tester, liked: true);
      await tester.tap(find.byKey(const ValueKey('likeButton_5')));
      await tester.pump(); // 弹跳在点击后的第一帧开始计时
      await tester.pump(const Duration(milliseconds: 100));
      expect(scale(tester), 1.0);
      await tester.pumpAndSettle();
    });

    testWidgets('减少动态效果 → 不弹，直接变红 +1', (tester) async {
      await pumpButton(tester, reduced: true);
      await tester.tap(find.byKey(const ValueKey('likeButton_5')));
      await tester.pump(); // 弹跳在点击后的第一帧开始计时
      await tester.pump(const Duration(milliseconds: 100));
      expect(scale(tester), 1.0);
      await tester.pumpAndSettle();
      expect(find.text('4'), findsOneWidget);
    });
  });
}

class _Repo implements LikeRepository {
  @override
  Future<LikeResult> like(int postId) async => const LikeResult(liked: true, likeCount: 4);

  @override
  Future<LikeResult> unlike(int postId) async => const LikeResult(liked: false, likeCount: 2);
}
