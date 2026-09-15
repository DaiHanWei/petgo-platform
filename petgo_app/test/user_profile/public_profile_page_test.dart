import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/auth/domain/user_tag.dart';
import 'package:tailtopia/features/social/data/account_report_repository.dart';
import 'package:tailtopia/features/social/data/blocked_users_repository.dart';
import 'package:tailtopia/features/social/domain/account_action_entry.dart';
import 'package:tailtopia/features/social/domain/account_report_reason.dart';
import 'package:tailtopia/features/social/domain/blocked_user.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_pet_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_user_posts_repository.dart';
import 'package:tailtopia/features/user_profile/presentation/public_profile_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.0 batch-b1 Story 2.1（FR-118）：公开主页他人视角 + 「···」抽屉 + 收尾回调。
///
/// 覆盖 AC1（身份区字段）· AC3（抽屉三项）· AC4（收尾行为一字不改：拉黑有提示 / 举报静默）·
/// AC5（注销不下发身份 → 通用空态）。AC2 的「零引用」在 `mini_profile_retired_test.dart`。

const int _kViewerId = 5;
const int _kTargetId = 7;

class _FakeProfileRepo implements PublicProfileRepository {
  _FakeProfileRepo(this.profile);
  final PublicProfile profile;

  /// 取了几次 —— 「主页缓存不过夜」靠它证（见 autoDispose 那条用例）。
  int calls = 0;

  @override
  Future<PublicProfile> getPublicProfile(int userId) async {
    calls++;
    return profile;
  }
}

class _ThrowingProfileRepo implements PublicProfileRepository {
  _ThrowingProfileRepo(this.error);
  final Object error;

  /// 调了几次 —— 「重试」按钮是否真的重新取数，靠它证。
  int calls = 0;

  @override
  Future<PublicProfile> getPublicProfile(int userId) async {
    calls++;
    throw error;
  }
}

class _FakeBlockRepo implements BlockedUsersRepository {
  _FakeBlockRepo({this.fail = false});
  final bool fail;
  final List<int> blocked = <int>[];

  @override
  Future<List<BlockedUser>> list() async => const <BlockedUser>[];
  @override
  Future<void> unblock(int userId) async {}
  @override
  Future<void> block(int userId) async {
    if (fail) {
      throw DioException(requestOptions: RequestOptions(path: '/api/v1/me/blocked-users'));
    }
    blocked.add(userId);
  }
}

/// 内容区在本文件里不是被验对象（那是 `public_profile_posts_test.dart` 的事）——
/// 给个恒空页，免得走真网络。
class _FakePostsRepo implements PublicUserPostsRepository {
  @override
  Future<PublicUserPostPage> fetch(int userId, {String? cursor}) async =>
      PublicUserPostPage.empty;
}

class _FakeReportRepo implements AccountReportRepository {
  final List<int> reported = <int>[];
  @override
  Future<void> report(int targetUserId, AccountReportReason reason, {String? detail}) async {
    reported.add(targetUserId);
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

DioException _problem(int status, String? typeSlug) {
  final req = RequestOptions(path: '/api/v1/users/$_kTargetId/profile');
  return DioException(
    requestOptions: req,
    response: Response<Map<String, dynamic>>(
      requestOptions: req,
      statusCode: status,
      data: <String, dynamic>{
        if (typeSlug != null) 'type': 'https://petgo/errors/$typeSlug',
        'status': status,
        // ⚠️ 服务端原文，前端不得直接展示。
        'detail': '你已拉黑该用户',
      },
    ),
  );
}

PublicProfile _target({
  bool reported = false,
  bool self = false,
  bool isDeactivated = false,
  DateTime? joinedAt,
  String? signature,
  List<UserTag> tags = const [],
  int postCount = 18,
  int likeCount = 342,
}) =>
    PublicProfile(
      postCount: postCount,
      likeCount: likeCount,
      isDeactivated: isDeactivated,
      self: self,
      nickname: isDeactivated ? null : 'Rina',
      avatarUrl: null,
      signature: signature,
      joinedAt: joinedAt,
      reported: reported,
      tags: tags,
    );

/// 收尾回调的计数盒（闭包捕获变量本身，跨帧观察需要一个可变对象）。
class _Probe {
  int blocked = 0;
  int reported = 0;
}

/// 起一个**真的 GoRouter**：主页靠 `context.push` 进、靠 pop 的返回值把收尾信号交回调用方，
/// 用假 Navigator 测不出这条链路。
Future<_Probe> _pump(
  WidgetTester tester, {
  required PublicProfileRepository repo,
  BlockedUsersRepository? blockRepo,
  AccountReportRepository? reportRepo,
  PublicUserPostsRepository? postsRepo,
  AccountActionEntry entry = AccountActionEntry.miniProfile,
}) async {
  final probe = _Probe();
  final router = GoRouter(
    routes: [
      GoRoute(
        path: '/',
        builder: (_, _) => Consumer(
          builder: (context, ref, _) => Scaffold(
            body: Center(
              child: ElevatedButton(
                key: const ValueKey('openProfile'),
                onPressed: () => openUserProfile(
                  context,
                  ref,
                  _kTargetId,
                  entry: entry,
                  onBlocked: () => probe.blocked++,
                  onReported: () => probe.reported++,
                ),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
      GoRoute(
        path: PublicProfilePage.routePattern,
        builder: (_, state) => PublicProfilePage(
          userId: int.parse(state.pathParameters['userId']!),
          entry: accountActionEntryFromWire(state.uri.queryParameters['entry']),
        ),
      ),
    ],
  );
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    publicProfileRepositoryProvider.overrideWithValue(repo),
    blockedUsersRepositoryProvider.overrideWithValue(blockRepo ?? _FakeBlockRepo()),
    accountReportRepositoryProvider.overrideWithValue(reportRepo ?? _FakeReportRepo()),
    publicUserPostsRepositoryProvider.overrideWithValue(postsRepo ?? _FakePostsRepo()),
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
  await tester.tap(find.byKey(const ValueKey('openProfile')));
  await tester.pumpAndSettle();
  return probe;
}

Future<void> _openSheet(WidgetTester tester) async {
  await tester.tap(find.byKey(const ValueKey('profileMore')));
  await tester.pumpAndSettle();
}

void main() {
  late AppLocalizations l10n;

  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
  });

  group('AC1：身份区', () {
    testWidgets('昵称 / 加入时间 / 签名 / 发帖数都在，且「加入时间」只到月', (tester) async {
      await _pump(
        tester,
        repo: _FakeProfileRepo(_target(
          joinedAt: DateTime.utc(2026, 3, 4, 5, 6),
          signature: 'Pecinta kucing',
        )),
      );

      expect(find.text('Rina'), findsOneWidget);
      expect(find.byKey(const ValueKey('profileSignature')), findsOneWidget);
      expect(find.text(l10n.profileCounts(18, 342)), findsOneWidget);
      // 🔴 到月不到日：确切注册日期是没必要外泄的个人信息。
      final joined = tester.widget<Text>(find.byKey(const ValueKey('profileJoinedAt')));
      expect(joined.data, l10n.profileJoinedAt('Mar 2026'));
    });

    testWidgets('没设签名 → 不占位（迷你卡那句「主页筹备中」不跟过来）', (tester) async {
      await _pump(tester, repo: _FakeProfileRepo(_target(joinedAt: DateTime.utc(2026, 3, 4))));

      expect(find.byKey(const ValueKey('profileSignature')), findsNothing);
      expect(find.text(l10n.miniProfileComingSoon), findsNothing);
    });

    testWidgets('宠物卡还没有（Story 2.3）', (tester) async {
      await _pump(tester, repo: _FakeProfileRepo(_target(joinedAt: DateTime.utc(2026, 3, 4))));

      expect(find.byKey(const ValueKey('profilePetCard')), findsNothing);
    });

    testWidgets('自己视角 → 不渲染「···」（对自己举报 / 拉黑没有意义）', (tester) async {
      await _pump(tester, repo: _FakeProfileRepo(_target(self: true)));

      expect(find.byKey(const ValueKey('profileMore')), findsNothing);
    });
  });

  group('AC3：操作抽屉', () {
    testWidgets('举报 / 拉黑并列 + 显式「取消」', (tester) async {
      await _pump(tester, repo: _FakeProfileRepo(_target()));
      await _openSheet(tester);

      expect(find.text(l10n.accountReportAction), findsOneWidget);
      expect(find.text(l10n.blockUserAction), findsOneWidget);
      // UI 稿 C3 明确要一个显式的取消按钮（点遮罩也能收起，但显式更明确）。
      expect(find.byKey(const ValueKey('profileMenuCancel')), findsOneWidget);
    });

    testWidgets('⚠️ 已举报过 → 文案换成「已举报 / 点击可再次举报」，且拉黑项照常可点', (tester) async {
      await _pump(tester, repo: _FakeProfileRepo(_target(reported: true)));
      await _openSheet(tester);

      expect(find.text(l10n.accountReportedAction), findsOneWidget);
      expect(find.text(l10n.accountReportedActionSub), findsOneWidget);
      expect(find.text(l10n.accountReportAction), findsNothing);
      // 拉黑带来一个举报没有的效果（从此进不去对方主页），不得以「已举报」为由禁掉。
      final block = tester.widget<InkWell>(find.byKey(const ValueKey('profileMenuBlock')));
      expect(block.onTap, isNotNull);
    });

    testWidgets('「取消」只收抽屉，不动主页、不触发任何收尾', (tester) async {
      final probe = await _pump(tester, repo: _FakeProfileRepo(_target()));
      await _openSheet(tester);
      await tester.tap(find.byKey(const ValueKey('profileMenuCancel')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('profileMore')), findsOneWidget); // 还在主页上
      expect(probe.blocked, 0);
      expect(probe.reported, 0);
    });
  });

  group('AC4：收尾行为一字不改', () {
    testWidgets('🔴 拉黑成功 → 退出主页 + 成功提示 + onBlocked 触发', (tester) async {
      final blockRepo = _FakeBlockRepo();
      final probe = await _pump(
        tester,
        repo: _FakeProfileRepo(_target()),
        blockRepo: blockRepo,
      );
      await _openSheet(tester);
      await tester.tap(find.byKey(const ValueKey('profileMenuBlock')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('confirmBlockUser')));
      await tester.pumpAndSettle();

      expect(blockRepo.blocked, [_kTargetId]);
      expect(probe.blocked, 1);
      expect(probe.reported, 0);
      expect(find.byKey(const ValueKey('openProfile')), findsOneWidget); // 已退回调用方那一屏
      expect(find.text(l10n.blockUserSuccess), findsOneWidget);
      await tester.pump(const Duration(seconds: 3)); // toast 定时器跑完
    });

    testWidgets('拉黑失败 → **停在主页**、给失败提示、不触发收尾', (tester) async {
      final probe = await _pump(
        tester,
        repo: _FakeProfileRepo(_target()),
        blockRepo: _FakeBlockRepo(fail: true),
      );
      await _openSheet(tester);
      await tester.tap(find.byKey(const ValueKey('profileMenuBlock')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('confirmBlockUser')));
      await tester.pumpAndSettle();

      expect(find.text(l10n.blockUserFailed), findsOneWidget);
      expect(probe.blocked, 0);
      // 失败不该让用户重新走一遍入口 —— 与成功相反是刻意的。
      expect(find.byKey(const ValueKey('profileMore')), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('🔴 举报成功 → 退出主页 + onReported 触发，且**一个提示都不给**', (tester) async {
      final reportRepo = _FakeReportRepo();
      final probe = await _pump(
        tester,
        repo: _FakeProfileRepo(_target()),
        reportRepo: reportRepo,
      );
      await _openSheet(tester);
      await tester.tap(find.byKey(const ValueKey('profileMenuReport')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportReason_harassment')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportSubmit')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('accountReportDoneClose')));
      await tester.pumpAndSettle();

      expect(reportRepo.reported, [_kTargetId]);
      expect(probe.reported, 1);
      expect(probe.blocked, 0);
      expect(find.byKey(const ValueKey('openProfile')), findsOneWidget);
      // ⚠️ 静默：任何「已不再向你展示 TA 的内容」式提示都会泄露「举报会隐藏内容」。
      expect(find.byType(SnackBar), findsNothing);
      expect(find.text(l10n.blockUserSuccess), findsNothing);
      expect(find.text(l10n.reportHiddenToast), findsNothing);
    });

    testWidgets('只是看完返回 → 两个收尾回调都不触发', (tester) async {
      final probe = await _pump(tester, repo: _FakeProfileRepo(_target()));
      await tester.pageBack();
      await tester.pumpAndSettle();

      expect(probe.blocked, 0);
      expect(probe.reported, 0);
    });
  });

  group('AC5 / 异常态', () {
    testWidgets('🔴 已注销 → 通用空态，一个身份字段都不渲染，也没有「···」', (tester) async {
      await _pump(tester, repo: _FakeProfileRepo(_target(isDeactivated: true)));

      expect(find.byKey(const ValueKey('profileDeactivated')), findsOneWidget);
      expect(find.text(l10n.profileNotFound), findsOneWidget);
      expect(find.text('Rina'), findsNothing);
      // 连身份都不下发了，再挂一个举报入口是自相矛盾。
      expect(find.byKey(const ValueKey('profileMore')), findsNothing);
    });

    /// ⚠️ 服务端**不会**为「id 不存在」发 404（它与已注销合流成 200 + 空投影，刻意不可区分）。
    /// 这条守的是**防御性映射**：真收到 404 时给「用户不存在」，而不是「网络失败 + 重试」——
    /// 后者会让用户对着一个永远不会好的按钮点下去。文案与已注销**同一句**。
    testWidgets('真收到 404 → 与已注销同一句文案，不落到「网络失败」', (tester) async {
      await _pump(tester, repo: _ThrowingProfileRepo(_problem(404, 'not-found')));

      expect(find.byKey(const ValueKey('profileNotFound')), findsOneWidget);
      expect(find.text(l10n.profileNotFound), findsOneWidget);
      expect(find.text(l10n.profileLoadFailed), findsNothing);
    });

    testWidgets('🔴 我拉黑了对方（403 blocked-user）→ 专属空态、**没有重试按钮**、不外泄 detail 原文',
        (tester) async {
      await _pump(tester, repo: _ThrowingProfileRepo(_problem(403, 'blocked-user')));

      expect(find.text(l10n.profileBlockedEmpty), findsOneWidget);
      // 重试一个永远不会成功的动作，比不给按钮更糟。
      expect(find.byKey(const ValueKey('profileRetry')), findsNothing);
      expect(find.text(l10n.profileLoadFailed), findsNothing);
      expect(find.text('你已拉黑该用户'), findsNothing);
    });

    testWidgets('网络失败 → 失败文案 + 重试按钮，且重试真的重新取数', (tester) async {
      final repo = _ThrowingProfileRepo(
          DioException(requestOptions: RequestOptions(path: '/profile')));
      await _pump(tester, repo: repo);

      expect(find.text(l10n.profileLoadFailed), findsOneWidget);
      // 🔴 恰好 1 次 —— 大于 1 说明 Riverpod 3 的自动重试没关掉：那会让页面在
      // 「错误态 / 加载中」之间自己横跳，用户根本点不到重试按钮。
      expect(repo.calls, 1);
      await tester.tap(find.byKey(const ValueKey('profileRetry')));
      await tester.pumpAndSettle();
      expect(repo.calls, 2);
    });
  });

  group('缓存边界', () {
    /// 🔴 Riverpod 3 的 `FutureProvider.family` **默认 keep-alive** ——
    /// 忘了 `isAutoDispose: true` 的表现是：举报完退出、再点进同一个人，
    /// 拿到的是缓存里的 `reported: false`（「已举报」标记消失，用户重复举报）；
    /// 拉黑完再点进去看到的是缓存的 200 而不是「你已拉黑该用户」；
    /// 断网那次失败还会被永久缓存。同设备换账号时更直接：B 读到 A 缓存里的
    /// `self` 与 `reported`（与 bug 20260730-421 / 446 同型的隐私泄漏）。
    testWidgets('🔴 退出主页再进来 → 重新取数，不吃上一次的缓存', (tester) async {
      final repo = _FakeProfileRepo(_target());
      await _pump(tester, repo: repo);
      expect(repo.calls, 1);

      await tester.pageBack();
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('openProfile')));
      await tester.pumpAndSettle();

      expect(repo.calls, 2, reason: 'publicProfileProvider 必须 isAutoDispose: true');
    });
  });

  group('埋点：入口口径在整页化之后一字不变', () {
    final events = <MapEntry<String, Map<String, Object>?>>[];
    setUp(() {
      events.clear();
      Analytics.debugCaptureSink = (name, props) => events.add(MapEntry(name, props));
    });
    tearDown(() => Analytics.debugCaptureSink = null);

    /// 🔴 `entry` 经路由的 `?entry=` 往返一圈后必须还是原来那个值。
    ///
    /// 迷你卡时代它是个闭包参数，整页之后只能经查询串传 —— 这中间任何一处拼错，
    /// 看板上都只会表现为「评论区入口的量忽然归零」，不会报错。
    testWidgets('评论区进主页 → 拉黑事件 entry=comment', (tester) async {
      await _pump(
        tester,
        repo: _FakeProfileRepo(_target()),
        entry: AccountActionEntry.comment,
      );
      await _openSheet(tester);
      await tester.tap(find.byKey(const ValueKey('profileMenuBlock')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('confirmBlockUser')));
      await tester.pumpAndSettle();

      final hit = events.singleWhere((e) => e.key == 'social_user_hide_submitted');
      expect(hit.value!['origin'], 'BLOCK');
      expect(hit.value!['entry'], 'comment');
      await tester.pump(const Duration(seconds: 3));
    });

    /// ⚠️ Feed / 详情页那个入口的上报值**仍是 `mini_profile`**：改版前后在看板上是
    /// 同一条时间序列，换字面量会在改版当天把曲线断成两截。
    testWidgets('Feed / 详情页进主页 → 拉黑事件 entry 仍是 mini_profile', (tester) async {
      await _pump(tester, repo: _FakeProfileRepo(_target()));
      await _openSheet(tester);
      await tester.tap(find.byKey(const ValueKey('profileMenuBlock')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('confirmBlockUser')));
      await tester.pumpAndSettle();

      final hit = events.singleWhere((e) => e.key == 'social_user_hide_submitted');
      expect(hit.value!['entry'], 'mini_profile');
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('🛡 `?entry=` 写了个不认识的值 → 回落 mini_profile，页面照常，不崩', (tester) async {
      final router = GoRouter(routes: [
        GoRoute(
          path: PublicProfilePage.routePattern,
          builder: (_, state) => PublicProfilePage(
            userId: int.parse(state.pathParameters['userId']!),
            entry: accountActionEntryFromWire(state.uri.queryParameters['entry']),
          ),
        ),
      ], initialLocation: '/users/$_kTargetId?entry=totally-bogus');
      addTearDown(router.dispose);
      final container = ProviderContainer(overrides: [
        publicProfileRepositoryProvider.overrideWithValue(_FakeProfileRepo(_target())),
        blockedUsersRepositoryProvider.overrideWithValue(_FakeBlockRepo()),
        accountReportRepositoryProvider.overrideWithValue(_FakeReportRepo()),
        publicUserPostsRepositoryProvider.overrideWithValue(_FakePostsRepo()),
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

      expect(find.text('Rina'), findsOneWidget);
    });
  });
}
