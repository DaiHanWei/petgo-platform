import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_pet_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_user_posts_repository.dart';
import 'package:tailtopia/features/user_profile/presentation/public_profile_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/post_grid_tile.dart';

/// V1.3.0 batch-b1 Story 2.2（FR-118.2）：主页内容区与两个聚合计数。
///
/// 覆盖 AC2（两个计数都在、同一行）· AC4（2 列网格 / 点进详情页 / 分页）·
/// AC5（FR-118.7 的「不放」清单，反向验收）。
/// AC1/AC3（可见范围与无 N+1）在**服务端**，由 `UserPublicPostsTest` 守。

const int _kViewerId = 5;
const int _kTargetId = 7;

class _FakeProfileRepo implements PublicProfileRepository {
  _FakeProfileRepo(this.profile);
  final PublicProfile profile;
  @override
  Future<PublicProfile> getPublicProfile(int userId) async => profile;
}

class _FakePostsRepo implements PublicUserPostsRepository {
  _FakePostsRepo(this.pages);

  /// 依次返回的各页；用完之后一律回空页。
  final List<PublicUserPostPage> pages;

  /// 每次调用带进来的 cursor —— 「加载更多」有没有真的往后翻，靠它证。
  final List<String?> cursors = <String?>[];

  @override
  Future<PublicUserPostPage> fetch(int userId, {String? cursor}) async {
    cursors.add(cursor);
    return cursors.length <= pages.length ? pages[cursors.length - 1] : PublicUserPostPage.empty;
  }
}

class _ThrowingPostsRepo implements PublicUserPostsRepository {
  int calls = 0;
  @override
  Future<PublicUserPostPage> fetch(int userId, {String? cursor}) async {
    calls++;
    throw DioException(requestOptions: RequestOptions(path: '/posts'));
  }
}

class _LoggedInAuth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: _kViewerId, nickname: 'Me', onboardingCompleted: true),
      );
}

PublicProfile _profile({int postCount = 18, int likeCount = 342}) => PublicProfile(
      postCount: postCount,
      likeCount: likeCount,
      isDeactivated: false,
      self: false,
      nickname: 'Rina',
      joinedAt: DateTime.utc(2026, 3, 4),
    );

PublicUserPost _post(int id, {String type = 'DAILY'}) => PublicUserPost(id: id, type: type);

/// 记录最后一次落到 `/content/:id` 的 id —— 用来验「点任一内容进既有内容详情页」。
class _Nav {
  int? lastContentId;
}

Future<_Nav> _pump(
  WidgetTester tester, {
  required PublicUserPostsRepository postsRepo,
  PublicProfile? profile,
}) async {
  final nav = _Nav();
  final router = GoRouter(
    initialLocation: '${PublicProfilePage.routeBase}/$_kTargetId',
    routes: [
      GoRoute(
        path: PublicProfilePage.routePattern,
        builder: (_, state) =>
            PublicProfilePage(userId: int.parse(state.pathParameters['userId']!)),
      ),
      GoRoute(
        path: '/content/:id',
        builder: (_, state) {
          nav.lastContentId = int.parse(state.pathParameters['id']!);
          return const Scaffold(body: Center(child: Text('detail')));
        },
      ),
    ],
  );
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    publicProfileRepositoryProvider.overrideWithValue(_FakeProfileRepo(profile ?? _profile())),
    publicUserPostsRepositoryProvider.overrideWithValue(postsRepo),
    // 宠物卡在本文件里不是被验对象 —— 给一个「没有宠物」，免得走真网络。
    publicProfilePetProvider(_kTargetId).overrideWith((ref) async => null),
    authControllerProvider.overrideWith(_LoggedInAuth.new),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
  return nav;
}

void main() {
  late AppLocalizations l10n;

  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
  });

  group('AC2：两个聚合计数', () {
    testWidgets('发帖总数与获赞总数同在一行', (tester) async {
      await _pump(tester, postsRepo: _FakePostsRepo(const []));

      expect(find.text(l10n.profileCounts(18, 342)), findsOneWidget);
    });

    /// ⚠️ 零赞不是「没有这个数」—— 新用户主页上写「0 suka」是对的，
    /// 把它藏起来反而会让人以为这一版又没做。
    testWidgets('零发帖零获赞照常显示 0', (tester) async {
      await _pump(tester,
          postsRepo: _FakePostsRepo(const []), profile: _profile(postCount: 0, likeCount: 0));

      expect(find.text(l10n.profileCounts(0, 0)), findsOneWidget);
    });
  });

  group('AC4：2 列网格', () {
    testWidgets('三类混排一格不漏，且复用「我的」页那一格的公共组件', (tester) async {
      await _pump(
        tester,
        postsRepo: _FakePostsRepo([
          PublicUserPostPage(items: [
            _post(1),
            _post(2, type: 'GROWTH_MOMENT'),
            _post(3, type: 'KNOWLEDGE'),
          ], hasMore: false),
        ]),
      );

      expect(find.byType(PostGridTile), findsNWidgets(3));
      final grid = tester.widget<GridView>(find.byType(GridView));
      expect((grid.gridDelegate as SliverGridDelegateWithFixedCrossAxisCount).crossAxisCount, 2);
    });

    testWidgets('点任一内容 → 既有内容详情页', (tester) async {
      final nav = await _pump(
        tester,
        postsRepo: _FakePostsRepo([
          PublicUserPostPage(items: [_post(1), _post(42)], hasMore: false),
        ]),
      );

      await tester.tap(find.byKey(const ValueKey('postGridTile_42')));
      await tester.pumpAndSettle();

      expect(nav.lastContentId, 42);
    });

    /// 🔴 他人主页上**永远不该出现「仅自己可见」角标** ——
    /// 服务端只给 PUBLIC，出现了就说明过滤漏了（或者前端自己编了一个 visibility）。
    testWidgets('网格里没有「仅自己可见」角标', (tester) async {
      await _pump(
        tester,
        postsRepo: _FakePostsRepo([
          PublicUserPostPage(items: [_post(1)], hasMore: false),
        ]),
      );

      expect(find.byKey(const ValueKey('postGridTilePrivate_1')), findsNothing);
      final tile = tester.widget<PostGridTile>(find.byType(PostGridTile));
      expect(tile.isPrivate, isFalse);
    });

    /// ⚠️ 这一句文案**同时服务于**「被拉黑者看拉黑者的主页」（Story 2.5 · AC2）——
    /// 两种情况在客户端根本区分不出来，见 `public_profile_blocked_view_test.dart`。
    testWidgets('一个公开内容都没有 → 空态，且文案不提「拉黑」', (tester) async {
      await _pump(tester, postsRepo: _FakePostsRepo(const []));

      expect(find.byKey(const ValueKey('profilePostsEmpty')), findsOneWidget);
      // 🔴 这一句同时服务于「被拉黑者」—— 出现任何与拉黑有关的字样，
      // 被拉黑的人一对比就确认了自己被拉黑（Story 2.5 · AC2）。
      final copy = l10n.profilePostsEmpty.toLowerCase();
      for (final leak in ['block', 'blokir', 'hidden', 'disembunyikan']) {
        expect(copy.contains(leak), isFalse, reason: '空态文案不得暗示拉黑：$leak');
      }
    });
  });

  group('分页', () {
    testWidgets('hasMore=false → 没有「加载更多」', (tester) async {
      await _pump(
        tester,
        postsRepo: _FakePostsRepo([
          PublicUserPostPage(items: [_post(1)], hasMore: false),
        ]),
      );

      expect(find.byKey(const ValueKey('profilePostsLoadMore')), findsNothing);
    });

    /// 🔴 「加载更多」必须**往后追加**，不是重拉第一页 ——
    /// 重拉会把用户弹回网格顶部（而他刚刚才滑到底）。
    testWidgets('点「加载更多」→ 带上游标追加，已加载的格子一个不少', (tester) async {
      final repo = _FakePostsRepo([
        PublicUserPostPage(items: [_post(1), _post(2)], hasMore: true, nextCursor: 'c2'),
        PublicUserPostPage(items: [_post(3)], hasMore: false),
      ]);
      await _pump(tester, postsRepo: repo);
      expect(find.byType(PostGridTile), findsNWidgets(2));

      await tester.tap(find.byKey(const ValueKey('profilePostsLoadMore')));
      await tester.pumpAndSettle();

      expect(repo.cursors, [null, 'c2']);
      expect(find.byType(PostGridTile), findsNWidgets(3));
      expect(find.byKey(const ValueKey('postGridTile_1')), findsOneWidget);
      expect(find.byKey(const ValueKey('profilePostsLoadMore')), findsNothing);
    });
  });

  group('内容区失败', () {
    /// 内容区取数失败**不接管整页**：身份区已经渲染出来了，
    /// 把它换成一屏错误没有道理（用户至少还看得到这人是谁）。
    testWidgets('列表失败 → 身份区照常在，内容区就地给重试', (tester) async {
      final repo = _ThrowingPostsRepo();
      await _pump(tester, postsRepo: repo);

      expect(find.byKey(const ValueKey('profileAvatar')), findsOneWidget);
      expect(find.text(l10n.profileCounts(18, 342)), findsOneWidget);
      expect(repo.calls, 1, reason: 'Riverpod 3 的自动重试会让它自己横跳');

      await tester.tap(find.byKey(const ValueKey('profilePostsRetry')));
      await tester.pumpAndSettle();
      expect(repo.calls, 2);
    });
  });

  group('AC5：FR-118.7 的「不放」清单（反向验收）', () {
    /// 🔴 这一条是**明确不做**，不是"还没做"。
    ///
    /// 扫的是主页自己那份源码：里程碑 / 护照 / 打卡 / 关注粉丝 / 访客记录 / 主页级分享
    /// 任何一个悄悄长回来，这条就红。⚠️ 别通过从下面删词来"修"它。
    test('主页源码里不出现里程碑 / 护照 / 打卡 / 关注 / 访客 / 分享入口', () {
      // ⚠️ 先剥掉 import 与整行注释再扫，否则这条测试从第一天起就是红的
      // （`shared/widgets/...` 的路径里带着 "share"；而类注释里正写着这份禁用清单本身）——
      // 一条一直红的守门测试，很快会被人直接删掉。
      final src = File('lib/features/user_profile/presentation/public_profile_page.dart')
          .readAsLinesSync()
          .map((l) => l.trimLeft())
          .where((l) => !l.startsWith('import ') && !l.startsWith('//') && !l.startsWith('*'))
          .join('\n')
          // ⚠️ `VisitorArchiveView` 是**宠物访客视图**（Story 2.3 交付的入口，该有），
          // 与 FR-118.7 说的「访客记录」（谁看过我的主页）是两回事 —— 先剔掉再扫，
          // 否则这条会把一个正确的实现判成违规。
          .replaceAll('VisitorArchiveView', '');
      const forbidden = <String, String>{
        'milestone': '里程碑徽章墙',
        'passport': '护照集章数',
        'checkin': '打卡足迹',
        'Share': '主页级 H5 分享入口',
        'follow': '关注 / 粉丝',
        'visitor': '访客记录',
        'bio': '独立 bio 字段（签名复用既有的 signature，不另起一个）',
      };
      forbidden.forEach((needle, why) {
        expect(src.toLowerCase().contains(needle.toLowerCase()), isFalse,
            reason: 'FR-118.7 明确不放：$why');
      });
    });
  });
}
