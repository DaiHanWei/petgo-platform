import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/like_repository.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/presentation/like_button.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/login_hard_dialog.dart';
import 'package:tailtopia/shared/widgets/masonry_card.dart';

/// V1.1.6 Story 3.2：Feed 通栏卡片的**操作行与点击分区**。
///
/// <p>这组测试守的是「点哪儿会发生什么」。改版后卡片从「整块一个手势」变成了三种区域：
/// 点赞就地切换、评论跳详情并定位评论区、其余区域整块进详情页顶部 ——
/// 这三者一旦串味，用户想点赞却被跳走，是最恼人的那类 bug。
class _FakeLikeRepo implements LikeRepository {
  int calls = 0;

  @override
  Future<LikeResult> like(int postId) async {
    calls++;
    return const LikeResult(liked: true, likeCount: 4);
  }

  @override
  Future<LikeResult> unlike(int postId) async {
    calls++;
    return const LikeResult(liked: false, likeCount: 3);
  }
}

FeedItem _item({
  int id = 1,
  String? body = 'Hello pets',
  String? image,
  int likeCount = 3,
  bool liked = false,
  int commentCount = 7,
}) =>
    FeedItem(
      id: id,
      authorId: 7,
      authorDeleted: false,
      authorNickname: 'Alice',
      type: 'DAILY',
      body: body,
      firstImageUrl: image,
      createdAt: DateTime.utc(2026, 6, 2),
      likeCount: likeCount,
      liked: liked,
      commentCount: commentCount,
    );

LoginResponse _user() => const LoginResponse(
    accessToken: 'a', refreshToken: 'r', role: 'USER', isNewUser: false, onboardingCompleted: true);

/// ⚠️ 点赞的埋点在**登录门控之后**上报 —— 不造登录态就走不到那一行。
ProviderContainer _container({LikeRepository? likeRepo, bool loggedIn = false}) {
  final c = ProviderContainer(overrides: [
    if (likeRepo != null) likeRepositoryProvider.overrideWithValue(likeRepo),
  ]);
  if (loggedIn) {
    c.read(authControllerProvider.notifier).applyLogin(_user());
  }
  return c;
}

Widget _wrapCard(Widget card, {ProviderContainer? container}) => UncontrolledProviderScope(
      container: container ?? _container(),
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(body: SingleChildScrollView(child: card)),
      ),
    );

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  tearDown(() => Analytics.debugCaptureSink = null);

  /// 🔴 **卡片自上而下的顺序**（产品 2026-08-28 指定）：
  /// 头像行（名字 / 其下发布时间）→ 图片（如有）→ 正文 → 操作行。
  ///
  /// 修复前操作行夹在**图片与正文之间**（照 Instagram 的「图 → 操作 → 文案」惯例）。
  /// 那套惯例的前提是**有图**：图片本身就是内容，操作行贴着它才成立。
  /// 而纯文字帖没有图 ⇒ 变成「头像 → 点赞评论分享 → 正文」——
  /// 读者还没看到内容就先看到了对它的操作。实机反馈正是拿一条纯文字帖截的图。
  ///
  /// ⚠️ 老用例（点赞数在不在、评论点得到不）**改前改后都绿** ——
  /// 它们一条都不断言顺序，所以这个问题从交付起就没有任何东西挡着。
  /// 这里按**纵坐标**断言，两种卡各一条（有图的也一起钉，避免有人只修纯文字那半）。
  group('bug 20260828 · 卡片元素顺序', () {
    Future<double> topOf(WidgetTester tester, Finder f) async =>
        tester.getRect(f).top;

    testWidgets('纯文字帖：正文在操作行**上面**', (tester) async {
      await tester.pumpWidget(_wrapCard(
          MasonryCard(item: _item(body: 'Mimaw got her first clothes'), deletedUserLabel: 'x')));
      await tester.pump();

      final body = await topOf(tester, find.text('Mimaw got her first clothes'));
      final actions = await topOf(tester, find.byType(LikeButton));
      expect(body, lessThan(actions),
          reason: '🔴 点赞评论分享跑到正文上面了 —— 读者还没看到内容就先看到对它的操作');
    });

    testWidgets('有图帖：顺序同样是 正文 → 操作行（不分两套）', (tester) async {
      await tester.pumpWidget(_wrapCard(MasonryCard(
          item: _item(body: 'ada foto', image: 'https://img.example/1.jpg'),
          deletedUserLabel: 'x')));
      await tester.pump();

      final body = await topOf(tester, find.text('ada foto'));
      final actions = await topOf(tester, find.byType(LikeButton));
      expect(body, lessThan(actions),
          reason: '🛡 两种卡的操作行位置忽上忽下，比统一放底部更难用（手指要在两个高度之间找）');
    });

    /// 🔴 **发布时间属于署名，不属于正文的尾巴**（产品 2026-08-28 二次指定）。
    ///
    /// 它此前跟在正文后面：读者要先读完内容才知道这是什么时候发的 ——
    /// 而「多久以前」恰恰是决定要不要往下读的信息。
    ///
    /// ⚠️ 断言用「时间在正文**之上**、且与名字在同一竖直区间」两条一起钉：
    /// 只断前一条的话，把时间挪到图片上方的角标里也能过，那不是产品要的位置。
    testWidgets('发布时间挂在名字下面（不在正文后面）', (tester) async {
      await tester.pumpWidget(_wrapCard(MasonryCard(
          item: _item(body: 'ada foto', image: 'https://img.example/1.jpg'),
          deletedUserLabel: 'x')));
      await tester.pump();

      final timeFinder = find.textContaining(RegExp(r'\d+\s*[a-z]+\s*ago|yang lalu|baru saja'));
      expect(timeFinder, findsOneWidget,
          reason: '没找到发布时间那行小字 —— 断言无从谈起');

      final timeRect = tester.getRect(timeFinder);
      final nameRect = tester.getRect(find.text('Alice'));
      final bodyTop = await topOf(tester, find.text('ada foto'));

      expect(timeRect.top, greaterThan(nameRect.top),
          reason: '🔴 时间跑到名字上面了');
      expect(timeRect.top, lessThan(bodyTop),
          reason: '🔴 发布时间还在正文后面 —— 读者要读完内容才知道这是什么时候发的');
      expect(timeRect.left, closeTo(nameRect.left, 2),
          reason: '🔴 时间没和名字左对齐 ⇒ 它不在署名那一块里，只是恰好排在了上面');
    });
  });

  group('AC1/AC2 通栏卡片渲染', () {
    /// 🔴 改版前卡片**没有**点赞与评论数（旧口径 FR-17「不在卡片展示」）。
    /// FR-93 正是要打破它 —— 这条钉住"它们真的出现了"。
    testWidgets('操作行有点赞数与评论数', (tester) async {
      await tester.pumpWidget(
          _wrapCard(MasonryCard(item: _item(), deletedUserLabel: 'Deleted user')));
      await tester.pump();

      expect(find.byType(LikeButton), findsOneWidget);
      expect(find.text('3'), findsOneWidget); // 点赞数
      expect(find.text('7'), findsOneWidget); // 评论数
    });

    /// 🛡 一屏多张卡时 key 必须唯一。
    ///
    /// 点赞组件原先的 key 写死成详情页专用的名字 —— 进列表后每张卡都是同一个 key，
    /// 既定位不到、也可能错配状态。
    testWidgets('多张卡的点赞按钮 key 互不重复', (tester) async {
      await tester.pumpWidget(_wrapCard(Column(children: [
        MasonryCard(item: _item(id: 11), deletedUserLabel: 'x'),
        MasonryCard(item: _item(id: 22), deletedUserLabel: 'x'),
      ])));
      await tester.pump();

      expect(find.byKey(const ValueKey('likeButton_11')), findsOneWidget);
      expect(find.byKey(const ValueKey('likeButton_22')), findsOneWidget);
      expect(find.byKey(const ValueKey('feedCardComment_11')), findsOneWidget);
      expect(find.byKey(const ValueKey('feedCardComment_22')), findsOneWidget);
    });
  });

  group('AC5 点击分区', () {
    /// 🛡 点赞**就地切换、不跳转** —— 想点个赞却被跳走是最恼人的那类 bug。
    testWidgets('点赞不触发整块跳转', (tester) async {
      var tapped = 0;
      final repo = _FakeLikeRepo();
      final c = _container(likeRepo: repo, loggedIn: true);
      addTearDown(c.dispose);
      await tester.pumpWidget(_wrapCard(
        MasonryCard(
          item: _item(),
          deletedUserLabel: 'x',
          onTap: () => tapped++,
        ),
        container: c,
      ));
      await tester.pump();

      await tester.tap(find.byKey(const ValueKey('likeButton_1')));
      await tester.pump();

      expect(tapped, 0, reason: '点赞必须就地切换，不能触发进详情页');
    });

    /// 评论按钮走自己的回调，同样不触发整块跳转。
    testWidgets('评论按钮走自己的回调，不触发整块跳转', (tester) async {
      var tapped = 0;
      var commented = 0;
      await tester.pumpWidget(_wrapCard(MasonryCard(
        item: _item(),
        deletedUserLabel: 'x',
        onTap: () => tapped++,
        onComment: () => commented++,
      )));
      await tester.pump();

      await tester.tap(find.byKey(const ValueKey('feedCardComment_1')));
      await tester.pump();

      expect(commented, 1);
      expect(tapped, 0);
    });

    /// 其余区域（正文）仍整块进详情页。
    testWidgets('点正文仍触发整块跳转', (tester) async {
      var tapped = 0;
      await tester.pumpWidget(_wrapCard(MasonryCard(
        item: _item(body: 'doggo day'),
        deletedUserLabel: 'x',
        onTap: () => tapped++,
      )));
      await tester.pump();

      await tester.tap(find.text('doggo day'));
      await tester.pump();

      expect(tapped, 1);
    });
  });

  group('AC6 埋点来源属性', () {
    /// 🛡 **两侧都必须传**，否则「开放首页点赞是净增长还是把详情页的点赞前移了」
    /// 这个对比彻底失效 —— 而那正是本版本要回答的问题。
    ///
    /// 🛡 同时钉住**事件名没变**：改名会切断已有的点赞历史序列。
    testWidgets('首页点赞上报 source=feed，且事件名不变', (tester) async {
      final seen = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => seen.add((e, p));

      final c = _container(likeRepo: _FakeLikeRepo(), loggedIn: true);
      addTearDown(c.dispose);
      await tester.pumpWidget(_wrapCard(
        MasonryCard(item: _item(), deletedUserLabel: 'x'),
        container: c,
      ));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('likeButton_1')));
      await tester.pumpAndSettle();

      final like = seen.where((e) => e.$1 == 'post_like_tapped').toList();
      expect(like, hasLength(1), reason: '事件名必须仍是 post_like_tapped');
      expect(like.first.$2?['source'], 'feed');
    });
  });

  group('AC3 未登录门控（既有能力，接进首页后仍须生效）', () {
    /// 🛡 未登录点赞 → 走既有登录引导、**不发请求**。
    /// 这是点赞组件自带的能力，本 story 只是把它接进列表 —— 这条确认接进来之后没走样。
    testWidgets('游客在首页点赞 → 登录引导且不发请求', (tester) async {
      final repo = _FakeLikeRepo();
      final c = _container(likeRepo: repo); // 未登录
      addTearDown(c.dispose);
      await tester.pumpWidget(_wrapCard(
        MasonryCard(item: _item(), deletedUserLabel: 'x'),
        container: c,
      ));
      await tester.pump();

      await tester.tap(find.byKey(const ValueKey('likeButton_1')));
      await tester.pumpAndSettle();

      expect(find.byType(LoginHardDialog), findsOneWidget);
      expect(repo.calls, 0, reason: '未登录不得发点赞请求');
      expect(find.text('3'), findsOneWidget, reason: '计数不应乐观翻转');
    });
  });

  group('AC4 评论定位参数', () {
    /// ⚠️ 这个参数名**不是新造的**：通知深链一直在产出 `?focus=comments`，
    /// 只是详情页从没消费过。两侧同名是硬要求。
    testWidgets('路由能把 ?focus=comments 解析进详情页', (tester) async {
      final router = GoRouter(
        initialLocation: '/content/5?focus=comments',
        routes: [
          GoRoute(
            path: '/content/:id',
            builder: (c, s) => Text(
              'focus=${s.uri.queryParameters['focus']}',
              textDirection: TextDirection.ltr,
            ),
          ),
        ],
      );
      await tester.pumpWidget(MaterialApp.router(routerConfig: router));
      await tester.pump();

      expect(find.text('focus=comments'), findsOneWidget);
    });
  });
}
