import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/auth/domain/auth_state.dart';
import 'package:petgo/features/auth/domain/login_response.dart';
import 'package:petgo/features/content/data/like_repository.dart';
import 'package:petgo/features/content/presentation/like_button.dart';
import 'package:petgo/l10n/app_localizations.dart';
import 'package:petgo/shared/widgets/login_hard_dialog.dart';

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

Future<void> _pump(WidgetTester tester, ProviderContainer container) async {
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: LikeButton(postId: 5, initialLiked: false, initialCount: 3),
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('AC1: 登录用户点赞乐观更新 + 后端真值校正', (tester) async {
    final repo = _FakeLikeRepo();
    final container = ProviderContainer(overrides: [likeRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_user());

    await _pump(tester, container);
    expect(find.text('3'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('detailLikeButton')));
    await tester.pumpAndSettle();
    expect(find.text('4'), findsOneWidget); // 后端真值
    expect(repo.likeCalls, 1);
  });

  testWidgets('AC1: 点赞失败回滚计数', (tester) async {
    final repo = _FakeLikeRepo(fail: true);
    final container = ProviderContainer(overrides: [likeRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_user());

    await _pump(tester, container);
    await tester.tap(find.byKey(const ValueKey('detailLikeButton')));
    await tester.pumpAndSettle();
    expect(find.text('3'), findsOneWidget); // 回滚到原值
  });

  testWidgets('AC1: 游客点赞 → FR-0C 强登录弹窗，不发请求', (tester) async {
    final repo = _FakeLikeRepo();
    final container = ProviderContainer(overrides: [likeRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose); // 默认游客态

    await _pump(tester, container);
    await tester.tap(find.byKey(const ValueKey('detailLikeButton')));
    await tester.pumpAndSettle();
    expect(find.byType(LoginHardDialog), findsOneWidget);
    expect(repo.likeCalls, 0);
    expect(find.text('3'), findsOneWidget); // 未乐观翻转
  });
}
