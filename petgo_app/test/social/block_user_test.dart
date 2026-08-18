import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/mini_profile_repository.dart';
import 'package:tailtopia/features/social/data/blocked_users_repository.dart';
import 'package:tailtopia/features/social/domain/blocked_user.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/mini_profile_sheet.dart';

/// V1.1.4 Story 1.2（FR-94）：迷你卡「⋯」→ 拉黑二次确认的前端全流程。
///
/// 覆盖 AC1（菜单向上弹 / 本人不渲染）· AC2（确认→提交中→成功收起 / 失败保持打开）·
/// AC3（三种不弹卡分支互相可区分）· AC5（`blocked-user` 独立分流，不外泄 detail 原文）。

const int _kViewerId = 5;
const int _kTargetId = 7;

class _FakeMiniRepo implements MiniProfileRepository {
  _FakeMiniRepo(this.profile);
  final MiniProfile profile;
  @override
  Future<MiniProfile> getMiniProfile(int userId) async => profile;
}

class _ThrowingMiniRepo implements MiniProfileRepository {
  _ThrowingMiniRepo(this.error);
  final Object error;
  @override
  Future<MiniProfile> getMiniProfile(int userId) async => throw error;
}

class _FakeBlockRepo implements BlockedUsersRepository {
  _FakeBlockRepo({this.fail = false, this.gate});

  /// 打开这个闸门前请求一直挂着——用来观察「提交中」那一帧。
  final Completer<void>? gate;
  final bool fail;
  final List<int> blocked = <int>[];

  @override
  Future<List<BlockedUser>> list() async => const <BlockedUser>[];

  @override
  Future<void> unblock(int userId) async {}

  @override
  Future<void> block(int userId) async {
    if (gate != null) await gate!.future;
    if (fail) {
      throw DioException(requestOptions: RequestOptions(path: '/api/v1/me/blocked-users'));
    }
    blocked.add(userId);
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

DioException _blockedUserError() {
  final req = RequestOptions(path: '/api/v1/users/$_kTargetId/mini-profile');
  return DioException(
    requestOptions: req,
    response: Response<Map<String, dynamic>>(
      requestOptions: req,
      statusCode: 403,
      data: const <String, dynamic>{
        'type': 'https://petgo/errors/blocked-user',
        'title': 'Forbidden',
        'status': 403,
        'detail': '你已拉黑该用户', // ⚠️ 服务端原文，前端不得直接展示（AC5）
      },
    ),
  );
}

const MiniProfile _target = MiniProfile(
  postCount: 3,
  isDeactivated: false,
  nickname: 'Rina',
  avatarUrl: null,
);

/// 拉黑成功回调的计数盒（闭包捕获变量本身，跨帧观察需要一个可变对象）。
class _Counter {
  int value = 0;
}

/// 挂一个按钮，点它 → `showMiniProfile`。[targetUserId] 用来测「目标是本人」。
/// 返回 `onBlocked` 的调用计数盒。
Future<_Counter> _pump(
  WidgetTester tester, {
  required MiniProfileRepository miniRepo,
  BlockedUsersRepository? blockRepo,
  int targetUserId = _kTargetId,
}) async {
  final counter = _Counter();
  final container = ProviderContainer(overrides: [
    miniProfileRepositoryProvider.overrideWithValue(miniRepo),
    blockedUsersRepositoryProvider.overrideWithValue(blockRepo ?? _FakeBlockRepo()),
    authControllerProvider.overrideWith(_LoggedInAuth.new),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Consumer(
        builder: (context, ref, _) => Scaffold(
          body: Center(
            child: ElevatedButton(
              key: const ValueKey('openMini'),
              onPressed: () =>
                  showMiniProfile(context, ref, targetUserId, onBlocked: () => counter.value++),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const ValueKey('openMini')));
  await tester.pumpAndSettle();
  return counter;
}

void main() {
  late AppLocalizations l10n;

  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
  });

  group('AC3 / AC5：三种不弹卡分支必须互相可区分', () {
    testWidgets('网络失败 → 不弹卡 + 网络失败 Toast（原静默分支已改造）', (tester) async {
      await _pump(tester,
          miniRepo: _ThrowingMiniRepo(
              DioException(requestOptions: RequestOptions(path: '/mini'))));

      expect(find.byKey(const ValueKey('miniProfileClose')), findsNothing);
      expect(find.text(l10n.miniProfileLoadFailed), findsOneWidget);
      expect(find.text(l10n.miniProfileBlocked), findsNothing);
      // toast 是 2.6s 定时器，跑完再结束，否则测试框架报「Timer is still pending」。
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('已主动拉黑（403 blocked-user）→ 不弹卡 + 专属 Toast，且不外泄 detail 原文', (tester) async {
      await _pump(tester, miniRepo: _ThrowingMiniRepo(_blockedUserError()));

      expect(find.byKey(const ValueKey('miniProfileClose')), findsNothing);
      expect(find.text(l10n.miniProfileBlocked), findsOneWidget);
      // ⚠️ 与网络失败分开：混在一起用户会一直重试一个永远不会成功的动作。
      expect(find.text(l10n.miniProfileLoadFailed), findsNothing);
      // AC5：ProblemDetail 的 detail 原文不得直接展示。
      expect(find.text('你已拉黑该用户'), findsNothing);
      // toast 是 2.6s 定时器，跑完再结束，否则测试框架报「Timer is still pending」。
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('已注销 → 不弹卡，且**一个 Toast 都不给**（NFR-8，既有行为不变）', (tester) async {
      await _pump(tester,
          miniRepo: _FakeMiniRepo(const MiniProfile(postCount: 0, isDeactivated: true)));

      expect(find.byKey(const ValueKey('miniProfileClose')), findsNothing);
      expect(find.text(l10n.miniProfileLoadFailed), findsNothing);
      expect(find.text(l10n.miniProfileBlocked), findsNothing);
    });
  });

  group('AC1：「⋯」入口与菜单', () {
    testWidgets('目标是他人 → 渲染「⋯」', (tester) async {
      await _pump(tester, miniRepo: _FakeMiniRepo(_target));
      expect(find.byKey(const ValueKey('miniProfileMore')), findsOneWidget);
    });

    testWidgets('目标是本人 → 「⋯」整体不渲染（不是渲染后禁用）', (tester) async {
      await _pump(tester, miniRepo: _FakeMiniRepo(_target), targetUserId: _kViewerId);

      expect(find.byKey(const ValueKey('miniProfileClose')), findsOneWidget); // 卡片照常弹
      expect(find.byKey(const ValueKey('miniProfileMore')), findsNothing);
    });

    testWidgets('菜单向上弹（决策 C-76）——菜单整体位于「⋯」上方，不向下溢出', (tester) async {
      await _pump(tester, miniRepo: _FakeMiniRepo(_target));
      final moreRect = tester.getRect(find.byKey(const ValueKey('miniProfileMore')));

      await tester.tap(find.byKey(const ValueKey('miniProfileMore')));
      await tester.pumpAndSettle();

      final menu = find.byKey(const ValueKey('miniProfileMenuBlock'));
      expect(menu, findsOneWidget);
      final menuRect = tester.getRect(menu);
      // ⚠️ UI 稿 A2 的框外说明写「锚在「⋯」下方」是错的：向下弹会溢出屏底并盖住头像。
      expect(menuRect.bottom <= moreRect.top, isTrue,
          reason: '菜单底边 ${menuRect.bottom} 应不低于「⋯」上沿 ${moreRect.top}（C-76：向上弹）');
      // 菜单项含副标题；「举报」项本 story 不渲染（Story 2.2 接入）。
      expect(find.text(l10n.blockUserActionSub), findsOneWidget);
    });

    testWidgets('点菜单外部 → 关闭菜单，卡片仍在（菜单不是第三层抽屉）', (tester) async {
      await _pump(tester, miniRepo: _FakeMiniRepo(_target));
      await tester.tap(find.byKey(const ValueKey('miniProfileMore')));
      await tester.pumpAndSettle();

      await tester.tapAt(const Offset(10, 10)); // 点浮层遮罩空白处
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('miniProfileMenuBlock')), findsNothing);
      expect(find.byKey(const ValueKey('miniProfileClose')), findsOneWidget);
    });
  });

  group('AC2：拉黑二次确认全流程', () {
    Future<void> openConfirm(WidgetTester tester) async {
      await tester.tap(find.byKey(const ValueKey('miniProfileMore')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('miniProfileMenuBlock')));
      await tester.pumpAndSettle();
    }

    testWidgets('确认抽屉：标题带昵称 + 头像重复出现 + 主按钮为拉黑', (tester) async {
      await _pump(tester, miniRepo: _FakeMiniRepo(_target));
      await openConfirm(tester);

      expect(find.text(l10n.blockUserTitle('Rina')), findsOneWidget);
      expect(find.text(l10n.blockUserMessage), findsOneWidget);
      expect(find.byKey(const ValueKey('confirmBlockUser')), findsOneWidget);
      // C1：头像在确认层重复出现（确认拉黑谁比确认动作本身更重要）——迷你卡 + 确认抽屉各一个。
      expect(find.byType(CircleAvatar), findsNWidgets(2));
    });

    testWidgets('成功 → 两层一并收起 + 成功 Toast + onBlocked 回调触发', (tester) async {
      final repo = _FakeBlockRepo();
      final counter = await _pump(tester,
          miniRepo: _FakeMiniRepo(_target), blockRepo: repo);
      await openConfirm(tester);

      await tester.tap(find.byKey(const ValueKey('confirmBlockUser')));
      await tester.pumpAndSettle();

      expect(repo.blocked, <int>[_kTargetId]);
      expect(find.byKey(const ValueKey('confirmBlockUser')), findsNothing); // 确认层收起
      expect(find.byKey(const ValueKey('miniProfileClose')), findsNothing); // 迷你卡也收起
      expect(find.text(l10n.blockUserSuccess), findsOneWidget);
      expect(counter.value, 1);
      // toast 是 2.6s 定时器，跑完再结束，否则测试框架报「Timer is still pending」。
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('提交中 → 主按钮与取消一并禁用，抽屉不关闭', (tester) async {
      final gate = Completer<void>();
      await _pump(tester,
          miniRepo: _FakeMiniRepo(_target), blockRepo: _FakeBlockRepo(gate: gate));
      await openConfirm(tester);

      await tester.tap(find.byKey(const ValueKey('confirmBlockUser')));
      await tester.pump(); // 只推进一帧：请求仍挂在闸门上

      expect(find.byKey(const ValueKey('confirmBlockUser')), findsOneWidget); // 抽屉不关
      final confirm = tester.widget<FilledButton>(find.byKey(const ValueKey('confirmBlockUser')));
      final cancel = tester.widget<OutlinedButton>(find.byKey(const ValueKey('confirmSheetCancel')));
      expect(confirm.onPressed, isNull);
      expect(cancel.onPressed, isNull, reason: '取消必须与主按钮一并禁用');

      gate.complete();
      await tester.pumpAndSettle();
      // toast 是 2.6s 定时器，跑完再结束，否则测试框架报「Timer is still pending」。
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('失败 → 抽屉保持打开 + 失败 Toast，可直接再点', (tester) async {
      final counter = await _pump(tester,
          miniRepo: _FakeMiniRepo(_target), blockRepo: _FakeBlockRepo(fail: true));
      await openConfirm(tester);

      await tester.tap(find.byKey(const ValueKey('confirmBlockUser')));
      await tester.pumpAndSettle();

      // 成功收起、失败保持打开——两者行为相反是刻意的：失败不该让用户重走一遍入口。
      expect(find.byKey(const ValueKey('confirmBlockUser')), findsOneWidget);
      expect(find.text(l10n.blockUserFailed), findsOneWidget);
      expect(find.text(l10n.blockUserSuccess), findsNothing);
      expect(counter.value, 0, reason: '失败不得触发 onBlocked（否则卡片白白消失）');

      // 按钮已解禁，可直接再点。
      final confirm = tester.widget<FilledButton>(find.byKey(const ValueKey('confirmBlockUser')));
      expect(confirm.onPressed, isNotNull);
      // toast 是 2.6s 定时器，跑完再结束，否则测试框架报「Timer is still pending」。
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('取消 → 什么都不发生（不请求、不回调、迷你卡还在）', (tester) async {
      final repo = _FakeBlockRepo();
      final counter = await _pump(tester,
          miniRepo: _FakeMiniRepo(_target), blockRepo: repo);
      await openConfirm(tester);

      await tester.tap(find.byKey(const ValueKey('confirmSheetCancel')));
      await tester.pumpAndSettle();

      expect(repo.blocked, isEmpty);
      expect(counter.value, 0);
      expect(find.byKey(const ValueKey('miniProfileClose')), findsOneWidget);
    });
  });
}
