import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/like_repository.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/domain/pinned_slot.dart';
import 'package:tailtopia/features/content/domain/post_like_state.dart';
import 'package:tailtopia/features/content/presentation/promo_pinned_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/media/lightbox_gestures.dart';
import 'package:tailtopia/shared/widgets/feed_image.dart';
import 'package:tailtopia/shared/widgets/login_hard_dialog.dart';
import 'package:tailtopia/shared/widgets/masonry_card.dart';

/// V1.5.0 likes Story 4.1（FR-209，B-AC1 ~ AC10）：Feed 图片区双击点赞 —— 动作表、埋点、手势冲突。
class _Repo implements LikeRepository {
  _Repo({this.fail = false});
  final bool fail;
  int likeCalls = 0;
  Completer<void>? gate;

  @override
  Future<LikeResult> like(int postId) async {
    likeCalls++;
    if (gate != null) await gate!.future;
    if (fail) throw Exception('network');
    return const LikeResult(liked: true, likeCount: 4);
  }

  @override
  Future<LikeResult> unlike(int postId) async => const LikeResult(liked: false, likeCount: 3);
}

LoginResponse _user() => const LoginResponse(
    accessToken: 'a', refreshToken: 'r', role: 'USER', isNewUser: false, onboardingCompleted: true);

FeedItem _item({bool liked = false, List<String> urls = const ['https://cdn.example.com/a.jpg']}) => FeedItem(
      id: 5,
      authorId: 7,
      authorDeleted: false,
      authorNickname: 'Alice',
      type: 'DAILY',
      body: 'Hello pets',
      firstImageUrl: urls.first,
      imageUrls: urls,
      imageSizes: [for (final _ in urls) const ImageSize(1200, 1200)],
      liked: liked,
      likeCount: 3,
      createdAt: DateTime.utc(2026, 11, 1),
    );

void main() {
  late _Repo repo;
  late ProviderContainer container;
  late int opened;
  late int longPressed;
  final events = <(String, Map<String, Object>?)>[];

  setUp(() {
    opened = 0;
    longPressed = 0;
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<void> pump(WidgetTester tester, FeedItem item,
      {bool login = true, bool fail = false, bool accessible = false}) async {
    // 游客用例要弹登录窗：测试字体比真机宽，390 宽下弹窗会溢出 2px —— 用默认测试视图（图片区仍在屏内）。
    if (login) {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
    }
    repo = _Repo(fail: fail);
    container = ProviderContainer(overrides: [likeRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose);
    if (login) container.read(authControllerProvider.notifier).applyLogin(_user());
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MediaQuery(
        data: MediaQueryData(size: const Size(390, 844), accessibleNavigation: accessible),
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: SingleChildScrollView(
              child: MasonryCard(
                item: item,
                deletedUserLabel: 'Deleted user',
                maxImageHeight: 900,
                feedTab: 'all',
                rankMode: 'recommend',
                onTap: () => opened++,
                onLongPress: () => longPressed++,
              ),
            ),
          ),
        ),
      ),
    ));
    await tester.pump();
  }

  Future<void> doubleTap(WidgetTester tester, Finder f) async {
    await tester.tap(f);
    await tester.pump(const Duration(milliseconds: 100));
    await tester.tap(f);
    await tester.pump();
  }

  /// 等过双击窗口 + 吞点期，让所有计时器走完。
  Future<void> settle(WidgetTester tester) async {
    await tester.pump(kLightboxSingleTapDelay * 2);
    await tester.pumpAndSettle();
  }

  List<Map<String, Object>?> likeEvents() =>
      events.where((e) => e.$1 == 'post_like_tapped').map((e) => e.$2).toList();

  int bounce() => container.read(postLikeStoreProvider)[5]?.bounceTick ?? 0;

  testWidgets('B-AC1：双击未赞帖 → 点赞一次、按钮变红 +1、不进详情；埋点 trigger=double_tap；按钮弹跳信号 +1',
      (tester) async {
    await pump(tester, _item());
    await doubleTap(tester, find.byType(FeedImage));
    await settle(tester);
    expect(repo.likeCalls, 1);
    expect(opened, 0);
    expect(find.text('4'), findsOneWidget);
    expect(tester.widget<Icon>(find.descendant(
            of: find.byKey(const ValueKey('likeButton_5')), matching: find.byType(Icon))).icon,
        Icons.favorite_rounded);
    expect(likeEvents(), [
      {'liked': true, 'source': 'feed', 'feed_tab': 'all', 'rank_mode': 'recommend', 'trigger': 'double_tap'},
    ]);
    expect(bounce(), 1);
  });

  testWidgets('B-AC2：已赞帖双击 → 不发请求、不报事件、按钮不弹（只播动效）', (tester) async {
    await pump(tester, _item(liked: true));
    await doubleTap(tester, find.byType(FeedImage));
    await settle(tester);
    expect(repo.likeCalls, 0);
    expect(likeEvents(), isEmpty);
    expect(bounce(), 0);
    expect(opened, 0);
  });

  testWidgets('飞行中再双击 → 不发第二次请求、不报', (tester) async {
    await pump(tester, _item());
    repo.gate = Completer<void>();
    await doubleTap(tester, find.byType(FeedImage));
    await tester.pump(kLightboxSingleTapDelay * 2);
    await doubleTap(tester, find.byType(FeedImage));
    repo.gate!.complete();
    await settle(tester);
    expect(repo.likeCalls, 1);
    expect(likeEvents(), hasLength(1));
  });

  testWidgets('B-AC3：单击 → 220ms 后才进详情', (tester) async {
    await pump(tester, _item());
    await tester.tap(find.byType(FeedImage));
    await tester.pump(const Duration(milliseconds: 200));
    expect(opened, 0);
    await tester.pump(const Duration(milliseconds: 20));
    expect(opened, 1);
    expect(repo.likeCalls, 0);
    await settle(tester);
  });

  testWidgets('B-AC5：三连击 → 只赞一次、不进详情', (tester) async {
    await pump(tester, _item());
    final f = find.byType(FeedImage);
    await tester.tap(f);
    await tester.pump(const Duration(milliseconds: 80));
    await tester.tap(f);
    await tester.pump(const Duration(milliseconds: 80));
    await tester.tap(f);
    await settle(tester);
    expect(repo.likeCalls, 1);
    expect(opened, 0);
  });

  testWidgets('B-AC6：游客双击 → 只弹登录窗；不发请求、不报点赞事件', (tester) async {
    await pump(tester, _item(), login: false);
    await doubleTap(tester, find.byType(FeedImage));
    await settle(tester);
    expect(find.byType(LoginHardDialog), findsOneWidget);
    expect(repo.likeCalls, 0);
    expect(likeEvents(), isEmpty);
    expect(opened, 0);
  });

  testWidgets('B-AC7：正文区双击不点赞，照常进详情', (tester) async {
    await pump(tester, _item());
    await doubleTap(tester, find.text('Hello pets'));
    await settle(tester);
    expect(repo.likeCalls, 0);
    expect(opened, greaterThanOrEqualTo(1));
  });

  testWidgets('B-AC8：请求失败 → 按钮与计数回滚、不提示', (tester) async {
    await pump(tester, _item(), fail: true);
    await doubleTap(tester, find.byType(FeedImage));
    await settle(tester);
    expect(repo.likeCalls, 1);
    expect(find.text('3'), findsOneWidget);
    expect(find.byType(SnackBar), findsNothing);
  });

  testWidgets('B-AC4：多图第 2 张上双击 → 点赞、不翻页', (tester) async {
    await pump(tester, _item(urls: const [
      'https://cdn.example.com/a.jpg',
      'https://cdn.example.com/b.jpg',
      'https://cdn.example.com/c.jpg',
    ]));
    await tester.drag(find.byType(PageView), const Offset(-400, 0));
    await tester.pumpAndSettle();
    final page = tester.widget<PageView>(find.byType(PageView)).controller!.page;
    await doubleTap(tester, find.byType(PageView));
    await settle(tester);
    expect(repo.likeCalls, 1);
    expect(tester.widget<PageView>(find.byType(PageView)).controller!.page, page);
    expect(opened, 0);
  });

  testWidgets('B-AC9：图片区纵向拖动 → 不进详情、不算双击', (tester) async {
    await pump(tester, _item());
    await tester.drag(find.byType(FeedImage), const Offset(0, -200));
    await settle(tester);
    expect(opened, 0);
    expect(repo.likeCalls, 0);
  });

  testWidgets('B-AC10：长按 → 举报菜单照旧，不进详情、不参与双击', (tester) async {
    await pump(tester, _item());
    await tester.longPress(find.byType(FeedImage));
    await settle(tester);
    expect(longPressed, 1);
    expect(opened, 0);
    expect(repo.likeCalls, 0);
  });

  testWidgets('单击后窗口内开始滚动（新一下拖动）→ 撤销待执行的单击', (tester) async {
    await pump(tester, _item());
    await tester.tap(find.byType(FeedImage));
    await tester.pump(const Duration(milliseconds: 50));
    await tester.drag(find.byType(FeedImage), const Offset(0, -200));
    await settle(tester);
    expect(opened, 0);
  });

  testWidgets('单击后窗口内已推了别的页 → 不再进详情', (tester) async {
    await pump(tester, _item());
    await tester.tap(find.byType(FeedImage));
    await tester.pump(const Duration(milliseconds: 50));
    final nav = tester.state<NavigatorState>(find.byType(Navigator));
    nav.push(MaterialPageRoute<void>(builder: (_) => const Scaffold(body: Text('other'))));
    await settle(tester);
    expect(find.text('other'), findsOneWidget);
    expect(opened, 0);
  });

  testWidgets('读屏：图片区退回原来的单击进详情（无延迟、无双击）', (tester) async {
    await pump(tester, _item(), accessible: true);
    await tester.tap(find.byType(FeedImage));
    await tester.pump();
    expect(opened, 1);
    await settle(tester);
  });

  testWidgets('推广卡不用 MasonryCard：不受双击点赞影响', (tester) async {
    var promoTaps = 0;
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    repo = _Repo();
    await tester.pumpWidget(ProviderScope(
      overrides: [likeRepositoryProvider.overrideWithValue(repo)],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: PromoPinnedCard(
            promo: const PromoCard(imageUrl: 'https://cdn.example.com/p.jpg', title: 'Promo'),
            maxImageHeight: 600,
            onTap: () => promoTaps++,
          ),
        ),
      ),
    ));
    await tester.pump();
    expect(find.byType(MasonryCard), findsNothing);
    await doubleTap(tester, find.byType(PromoPinnedCard));
    await settle(tester);
    expect(repo.likeCalls, 0);
  });
}
