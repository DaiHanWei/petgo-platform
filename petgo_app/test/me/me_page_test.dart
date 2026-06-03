import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/auth/domain/auth_state.dart';
import 'package:petgo/features/auth/domain/login_response.dart';
import 'package:petgo/features/me/data/my_posts_repository.dart';
import 'package:petgo/features/me/presentation/me_page.dart';
import 'package:petgo/l10n/app_localizations.dart';

/// Story 7.1 AC1：「我的」五大区块就位（用户信息/宠物状态/我的发布/账号设置含退出+注销/帮助）。
void main() {
  testWidgets('五区块入口齐全（含注销入口，PDP 权利可达）', (tester) async {
    // 高视口：使 ListView 一次性构建全部 5 区块（含底部帮助入口）。
    tester.view.physicalSize = const Size(1200, 3200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final container = ProviderContainer(overrides: [
      myPostsProvider.overrideWith((ref) async => const <MyPost>[]),
    ]);
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyProfile(
          const UserProfile(nickname: '小明', petStatus: 'A', hasPetProfile: true),
        );

    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: Locale('en'),
        home: MePage(),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.text('小明'), findsOneWidget); // 用户信息
    expect(find.byKey(const ValueKey('meEditNickname')), findsOneWidget);
    expect(find.byKey(const ValueKey('mePetStatus')), findsOneWidget); // 宠物状态
    expect(find.byKey(const ValueKey('meEditPetProfile')), findsOneWidget); // 状态A 档案入口
    expect(find.byKey(const ValueKey('meNoPosts')), findsOneWidget); // 我的发布空态
    expect(find.byKey(const ValueKey('meLogout')), findsOneWidget); // 退出登录入口（7.3）
    expect(find.byKey(const ValueKey('meDeleteAccount')), findsOneWidget); // 账号注销入口（7.3）
    expect(find.byKey(const ValueKey('meLanguage')), findsOneWidget); // 语言（7.2）
    expect(find.byKey(const ValueKey('meHelp')), findsOneWidget); // 帮助与反馈
  });
}
