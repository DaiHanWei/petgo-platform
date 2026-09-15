import 'dart:io';

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

/// V1.3.0 batch-b1 Story 2.5（FR-118.5）：被拉黑者视角的主页。
///
/// ## 🔴 本文件守的是「客户端根本没有"被拉黑"这个概念」
/// 服务端对「这个人真的没发过公开内容」与「这个人拉黑了我」返回**逐字节一样**的响应
/// （身份区照常 + 两个计数归零 + 空 items），所以这一屏在两种情况下**必然**长得一样。
/// 本文件用的就是那一份响应，并钉住「客户端源码里没有任何被拉黑分支」「文案只有一个 key」。
///
/// AC1/AC3/AC5 的服务端侧由 `BlockedViewerProfileTest` 守；AC4（Feed 与评论不屏蔽）是 L2。

const int _kViewerId = 5;
const int _kOwnerId = 9;

class _FakeProfileRepo implements PublicProfileRepository {
  _FakeProfileRepo(this.profile);
  final PublicProfile profile;
  @override
  Future<PublicProfile> getPublicProfile(int userId) async => profile;
}

class _EmptyPostsRepo implements PublicUserPostsRepository {
  @override
  Future<PublicUserPostPage> fetch(int userId, {String? cursor}) async =>
      PublicUserPostPage.empty;
}

class _LoggedInAuth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: _kViewerId, nickname: 'Me', onboardingCompleted: true),
      );
}

/// 🔴 **这就是服务端在两种情况下都会返回的那一份**：
/// 身份区齐全、两个计数为 0。客户端拿不到任何能区分两者的信号。
PublicProfile _identityOnly() => PublicProfile(
      postCount: 0,
      likeCount: 0,
      isDeactivated: false,
      self: false,
      nickname: 'Rani',
      avatarUrl: null,
      signature: 'Pecinta kucing',
      joinedAt: DateTime.utc(2026, 3, 4),
    );

Future<void> _pump(WidgetTester tester) async {
  final router = GoRouter(
    initialLocation: '${PublicProfilePage.routeBase}/$_kOwnerId',
    routes: [
      GoRoute(
        path: PublicProfilePage.routePattern,
        builder: (_, state) =>
            PublicProfilePage(userId: int.parse(state.pathParameters['userId']!)),
      ),
    ],
  );
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    publicProfileRepositoryProvider.overrideWithValue(_FakeProfileRepo(_identityOnly())),
    publicUserPostsRepositoryProvider.overrideWithValue(_EmptyPostsRepo()),
    // 被拉黑时服务端回 204；没养宠物时也回 204 —— 客户端两种都是「没有宠物卡」。
    publicProfilePetProvider(_kOwnerId).overrideWith((ref) async => null),
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
}

void main() {
  late AppLocalizations l10n;

  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
  });

  group('AC1：模糊空态', () {
    /// 头像 / 昵称 / 加入时间 / 签名**正常展示**（不做模糊、不隐藏），
    /// 只有内容区是空的 —— 这正是一个"没发过公开内容的人"的样子。
    testWidgets('身份区照常渲染，只有内容区空', (tester) async {
      await _pump(tester);

      expect(find.byKey(const ValueKey('profileAvatar')), findsOneWidget);
      expect(find.text('Rani'), findsOneWidget);
      expect(find.byKey(const ValueKey('profileJoinedAt')), findsOneWidget);
      expect(find.byKey(const ValueKey('profileSignature')), findsOneWidget);
      expect(find.text(l10n.profileCounts(0, 0)), findsOneWidget);

      expect(find.byKey(const ValueKey('profilePostsEmpty')), findsOneWidget);
      expect(find.byType(PostGridTile), findsNothing);
      expect(find.byKey(const ValueKey('profilePetCard')), findsNothing);
    });

    /// 🔴 **不明示拉黑**：整屏不出现任何与「拉黑 / 屏蔽 / 限制」有关的字样。
    testWidgets('屏幕上没有任何「被拉黑」的措辞', (tester) async {
      await _pump(tester);

      for (final s in [l10n.profileBlockedEmpty, l10n.blockUserAction, l10n.blockUserMessage]) {
        expect(find.text(s), findsNothing, reason: '不得明示拉黑：$s');
      }
    });

    /// ⚠️「···」照常在：被拉黑者仍然可以举报 / 反手拉黑对方。
    /// 把它藏起来反而是一个可对比的信号。
    testWidgets('「···」照常渲染', (tester) async {
      await _pump(tester);

      expect(find.byKey(const ValueKey('profileMore')), findsOneWidget);
    });
  });

  group('AC2：两种空态必须是同一个', () {
    /// 🔴 空态渲染的是**那一句共用文案**，而不是某个"被拉黑专用"的分支。
    ///
    /// ⚠️ 这一屏用的数据就是服务端在**两种情况下都会返回**的那一份
    /// （身份区齐全 + 两个计数 0 + 空 items）—— 所以"被拉黑"与"真的没内容"
    /// 在客户端根本不是两条路径，而是同一条。
    testWidgets('空态用的就是「真的没内容」那一句共用文案', (tester) async {
      await _pump(tester);

      final empty = tester.widget<Text>(find.byKey(const ValueKey('profilePostsEmpty')));
      expect(empty.data, l10n.profilePostsEmpty);
    });

    /// 🔴 空态文案**只有一个 key**。
    ///
    /// 直觉会驱使人给"被拉黑"写一句更贴切的文案 —— 那正好毁掉整个设计：
    /// 两句文案一对比，用户立刻知道自己被拉黑了。
    test('l10n 里没有第二个"内容区空"文案', () {
      for (final arb in ['lib/l10n/app_en.arb', 'lib/l10n/app_id.arb']) {
        final src = File(arb).readAsStringSync();
        for (final forbidden in ['profilePostsBlocked', 'profileBlockedContent', 'profileHiddenByOwner']) {
          expect(src.contains(forbidden), isFalse,
              reason: 'AC2：被拉黑与真的没内容必须复用同一句文案，$arb 里不该有 $forbidden');
        }
      }
    });

    /// 客户端源码里**不存在**任何"被拉黑"的分支判断。
    ///
    /// ⚠️ `profileBlockedEmpty` 是**另一回事**：那是「**我**拉黑了对方」（403），
    /// 与本 story 说的「对方拉黑了我」方向相反，两者不可混。
    test('主页源码里没有"对方拉黑了我"这类分支', () {
      final src = File('lib/features/user_profile/presentation/public_profile_page.dart')
          .readAsLinesSync()
          .map((l) => l.trimLeft())
          .where((l) => !l.startsWith('//') && !l.startsWith('*') && !l.startsWith('import '))
          .join('\n');
      for (final forbidden in ['blockedByOwner', 'hiddenByOwner', 'isBlockedMe', 'blockedMe']) {
        expect(src.contains(forbidden), isFalse,
            reason: 'AC2：客户端压根不该有"被拉黑"这个概念（服务端两种情况给同一份响应）');
      }
    });
  });
}
