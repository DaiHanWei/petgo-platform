import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/auth/domain/user_tag.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_pet_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_user_posts_repository.dart';
import 'package:tailtopia/features/user_profile/presentation/public_profile_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/user_tag_row.dart';

/// V1.3.0 batch-b1 Story 2.4（FR-118）：自己视角的主页。
///
/// 覆盖 AC1（同页两视角，由服务端的 `self` 切换）· AC2（自己视角无「···」）·
/// AC3（「编辑资料」在身份行内、跳既有抽屉）· AC4（运营标签照常显示）·
/// AC5（「我的」Tab 不动 —— 抽屉是**抽出去**的，不是复制的）。

const int _kMeId = 5;

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
        profile: UserProfile(
          id: _kMeId,
          nickname: 'Rina',
          email: 'rina@example.com',
          onboardingCompleted: true,
        ),
      );
}

PublicProfile _profile({required bool self, List<UserTag> tags = const []}) => PublicProfile(
      postCount: 18,
      likeCount: 342,
      isDeactivated: false,
      self: self,
      nickname: 'Rina',
      signature: 'Pecinta kucing',
      joinedAt: DateTime.utc(2026, 3, 4),
      tags: tags,
    );

const UserTag _kolTag = UserTag(
  code: 'KOL',
  name: 'Kreator',
  icon: '⭐',
  description: 'Kreator pilihan',
);

Future<void> _pump(WidgetTester tester, {required bool self, List<UserTag> tags = const []}) async {
  final router = GoRouter(
    initialLocation: '${PublicProfilePage.routeBase}/$_kMeId',
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
    publicProfileRepositoryProvider
        .overrideWithValue(_FakeProfileRepo(_profile(self: self, tags: tags))),
    publicUserPostsRepositoryProvider.overrideWithValue(_EmptyPostsRepo()),
    publicProfilePetProvider(_kMeId).overrideWith((ref) async => null),
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

  group('AC1 / AC2：同页两视角', () {
    /// 🔴 两种视角的差别**只有两处**：「···」与「编辑资料」。
    /// 其余（头像 / 昵称 / 加入时间 / 签名 / 计数 / 内容区）一字不差。
    testWidgets('自己视角：有「编辑资料」、没有「···」', (tester) async {
      await _pump(tester, self: true);

      expect(find.byKey(const ValueKey('profileEditButton')), findsOneWidget);
      expect(find.byKey(const ValueKey('profileMore')), findsNothing);
      // 其余照旧。
      expect(find.text('Rina'), findsOneWidget);
      expect(find.byKey(const ValueKey('profileJoinedAt')), findsOneWidget);
      expect(find.text(l10n.profileCounts(18, 342)), findsOneWidget);
    });

    testWidgets('他人视角：有「···」、没有「编辑资料」', (tester) async {
      await _pump(tester, self: false);

      expect(find.byKey(const ValueKey('profileMore')), findsOneWidget);
      expect(find.byKey(const ValueKey('profileEditButton')), findsNothing);
    });

    /// 🔴 `self` 是**服务端算的**，不是客户端拿本地 id 比出来的。
    /// 这里用一个「本地登录 id 与所看主页 id 相同、但服务端说 self=false」的组合钉住：
    /// 页面必须信服务端（客户端各算一次，两边口径迟早会漂）。
    testWidgets('服务端说 self=false 就按他人视角渲染，哪怕 id 看着是自己', (tester) async {
      await _pump(tester, self: false);

      expect(find.byKey(const ValueKey('profileEditButton')), findsNothing);
    });
  });

  group('AC3：编辑资料入口', () {
    /// 🔴 位置在**身份行内、与头像同一行**，**不在顶部 AppBar**。
    /// AppBar 那个槽位在他人视角上是「···」—— 两种视角共用一个槽位会让人
    /// 第一眼分不清自己在看谁的主页。
    testWidgets('按钮在身份行内，不在 AppBar 里', (tester) async {
      await _pump(tester, self: true);

      final button = find.byKey(const ValueKey('profileEditButton'));
      expect(button, findsOneWidget);
      expect(
        find.ancestor(of: button, matching: find.byType(AppBar)),
        findsNothing,
        reason: 'UI 稿 C2 明确标了位置：身份行内，不塞 AppBar',
      );
      // 与头像同一行。
      expect(
        find.ancestor(of: button, matching: find.byType(Row)),
        findsWidgets,
      );
    });

    testWidgets('点它弹出的是既有的资料编辑抽屉（昵称 / 签名 / 只读邮箱）', (tester) async {
      await _pump(tester, self: true);

      await tester.tap(find.byKey(const ValueKey('profileEditButton')));
      await tester.pumpAndSettle();

      // 三个字段的 key 都是「我的」Tab 那个抽屉原本就有的 —— 证明是同一份实现。
      expect(find.text(l10n.meEditProfileTitle), findsOneWidget);
      expect(find.byKey(const ValueKey('nicknameField')), findsOneWidget);
      expect(find.byKey(const ValueKey('signatureField')), findsOneWidget);
      expect(find.byKey(const ValueKey('meEditSaveButton')), findsOneWidget);
      // 邮箱只读（抽屉里直接渲染文本，没有输入框）。
      expect(find.text('rina@example.com'), findsOneWidget);
    });
  });

  group('AC4：运营标签', () {
    /// 自己也是被打标签的用户 —— 不该因为是自己就消失。
    testWidgets('自己视角照常显示运营标签', (tester) async {
      await _pump(tester, self: true, tags: const [_kolTag]);

      final row = tester.widget<UserTagRow>(find.byType(UserTagRow));
      expect(row.tags.single.code, 'KOL');
    });
  });

  group('AC5：「我的」Tab 不动，抽屉是抽出去的不是复制的', () {
    /// 🔴 AC3 原文是「跳**既有**的资料编辑抽屉，**不重画**」。
    ///
    /// 复制一份的表现是：昵称长度、签名上限、保存失败提示这些细节两处各改各的，
    /// 而且**没有任何测试会红**。所以这里直接钉住「两处调同一个函数」。
    test('me_page 与公开主页调的是同一个 openProfileEditSheet', () {
      const mePage = 'lib/features/me/presentation/me_page.dart';
      const profilePage = 'lib/features/user_profile/presentation/public_profile_page.dart';
      for (final f in [mePage, profilePage]) {
        expect(File(f).readAsStringSync(), contains('openProfileEditSheet('),
            reason: '$f 没有调用抽出去的那个抽屉');
      }
    });

    /// 「我的」Tab 里**不该再有**抽屉本体：留着一份就等于又有两份实现。
    test('me_page 里不再有抽屉本体（字段与保存逻辑都搬走了）', () {
      final src = File('lib/features/me/presentation/me_page.dart').readAsStringSync();
      for (final gone in ['nicknameField', 'signatureField', 'meEditSaveButton', 'updateProfile(']) {
        expect(src.contains(gone), isFalse, reason: '"$gone" 还留在 me_page 里 —— 抽屉应当只有一份');
      }
    });
  });
}
