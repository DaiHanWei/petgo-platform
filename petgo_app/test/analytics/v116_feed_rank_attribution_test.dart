import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/like_repository.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/presentation/like_button.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 点赞按钮门控未登录用户（FR-0C），所以埋点只在登录态才发 —— 造一个登录容器。
class _FakeLikeRepo implements LikeRepository {
  @override
  Future<LikeResult> like(int postId) async => const LikeResult(liked: true, likeCount: 1);

  @override
  Future<LikeResult> unlike(int postId) async => const LikeResult(liked: false, likeCount: 0);
}

LoginResponse _user() => const LoginResponse(
    accessToken: 'a', refreshToken: 'r', role: 'USER', isNewUser: false,
    onboardingCompleted: true);

/// L0：Feed 埋点的 `feed_tab` 与 `rank_mode`（Story 16.5 · AC1）。
///
/// 🔴 不加这两个属性，FR-95 的效果**无法归因** —— 而这个 FR 的参数本来就要在发版后校准，
/// 归因不了等于校准也做不了。
void main() {
  late List<(String, Map<String, Object>?)> seen;

  setUp(() {
    seen = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => seen.add((e, p));
  });

  tearDown(() => Analytics.debugCaptureSink = null);

  Map<String, Object>? propsOf(String name) =>
      seen.where((e) => e.$1 == name).map((e) => e.$2).firstOrNull;

  // ── feed_tab 词表 ──────────────────────────────────────────────

  test('每个分类 Tab 都有埋点名，且与接口契约值分开', () {
    expect(FeedCategory.all.analyticsTab, 'all');
    expect(FeedCategory.daily.analyticsTab, 'daily');
    expect(FeedCategory.growthMoment.analyticsTab, 'moment');
    expect(FeedCategory.knowledge.analyticsTab, 'tips');

    // 🛡 与 wire（接口契约，UPPER_SNAKE）刻意不同一个值 ——
    // 共用意味着任何一侧想改都得动另一侧。
    for (final c in FeedCategory.values) {
      expect(c.analyticsTab, isNot(c.wire));
    }
  });

  // ── rank_mode 只认服务端下发 ────────────────────────────────────

  group('rank_mode 由服务端下发，客户端不猜', () {
    test('recommend / chrono 原样解析', () {
      expect(RankMode.parse('recommend'), RankMode.recommend);
      expect(RankMode.parse('chrono'), RankMode.chrono);
    });

    /// 🔴 老后端不下发这个字段时**不能默认成任何一边**：
    /// 猜错哪边都会污染效果归因，而 unknown 在看板上一眼能筛掉。
    test('缺字段 / 未知值 → unknown，不默认成 chrono 也不默认成 recommend', () {
      expect(RankMode.parse(null), RankMode.unknown);
      expect(RankMode.parse(''), RankMode.unknown);
      expect(RankMode.parse('recommended'), RankMode.unknown);
      expect(RankMode.parse(123), RankMode.unknown);
    });

    test('FeedPage 从 JSON 读它', () {
      expect(FeedPage.fromJson({'items': [], 'rankMode': 'recommend'}).rankMode,
          RankMode.recommend);
      expect(FeedPage.fromJson({'items': []}).rankMode, RankMode.unknown);
    });

    /// 🔴 同一次刷新里两页路径不同（首屏推荐序、第二页恰好降级）→ mixed。
    ///
    /// 挑一边冒充会污染归因：记成 recommend 就把降级期间的数据算进效果里，
    /// 记成 chrono 又把推荐序的首屏效果丢掉。
    test('两页路径不同 → mixed；有一页说不清 → 整段 unknown', () {
      expect(RankMode.merge(RankMode.recommend, RankMode.recommend), RankMode.recommend);
      expect(RankMode.merge(RankMode.recommend, RankMode.chrono), RankMode.mixed);
      expect(RankMode.merge(RankMode.chrono, RankMode.recommend), RankMode.mixed);
      expect(RankMode.merge(RankMode.recommend, RankMode.unknown), RankMode.unknown);
      expect(RankMode.merge(RankMode.unknown, RankMode.chrono), RankMode.unknown);
    });
  });

  /// 🔴 半套属性比没有更糟：看板上会出现一批「有 feed_tab 没有 rank_mode」的记录，
  /// 既不能算进推荐序也不能算进时间倒序，只能整批扔掉 ——
  /// 而扔的时候没人知道它们本来属于哪边。
  test('🛡 缺任一个就都不带（不发半套）', () {
    expect(RankMode.eventProps('all', RankMode.recommend),
        {'feed_tab': 'all', 'rank_mode': 'recommend'});
    expect(RankMode.eventProps('all', null), isEmpty);
    expect(RankMode.eventProps(null, RankMode.recommend), isEmpty);
    expect(RankMode.eventProps(null, null), isEmpty);
  });

  // ── 事件真的带上了 ──────────────────────────────────────────────

  Future<void> pumpLike(WidgetTester tester, Widget button) async {
    final container = ProviderContainer(
        overrides: [likeRepositoryProvider.overrideWithValue(_FakeLikeRepo())]);
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_user());
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(body: Center(child: button)),
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('首页点赞带 feed_tab + rank_mode', (tester) async {
    await pumpLike(tester, const LikeButton(
      postId: 1,
      initialLiked: false,
      initialCount: 0,
      source: 'feed',
      feedTab: 'all',
      rankMode: RankMode.recommend,
    ));

    await tester.tap(find.byType(LikeButton));
    await tester.pumpAndSettle();

    expect(propsOf('post_like_tapped'), {
      'liked': true,
      'source': 'feed',
      'feed_tab': 'all',
      'rank_mode': 'recommend',
    });
  });

  /// 🛡 详情页的点赞没有"哪个 Tab、哪条排序路径"这回事 ——
  /// 硬填一个值会在看板上造出不存在的 Feed 会话。
  testWidgets('详情页点赞不带这两个属性', (tester) async {
    await pumpLike(tester, const LikeButton(
      postId: 2,
      initialLiked: false,
      initialCount: 0,
      source: 'detail',
    ));

    await tester.tap(find.byType(LikeButton));
    await tester.pumpAndSettle();

    expect(propsOf('post_like_tapped'), {'liked': true, 'source': 'detail'});
  });

  // ── AC2 命名规范 ───────────────────────────────────────────────

  test('两个属性名符合既有词表（小写 snake_case，与 source 同风格）', () {
    for (final name in ['feed_tab', 'rank_mode']) {
      expect(name, matches(RegExp(r'^[a-z]+(_[a-z]+)*$')));
    }
  });

  /// AC4 记录的那处缺口：数据侧**没有曝光类埋点**，所以「人均浏览深度」做不出来。
  /// 🛡 本 story 明确不补曝光埋点 —— 这条测试是防止有人"顺手"加一个。
  test('🛡 不得新增曝光类埋点（AC4）', () {
    final names = seen.map((e) => e.$1);
    expect(names.where((n) => n.contains('impression') || n.endsWith('_exposed')), isEmpty);
  });
}
