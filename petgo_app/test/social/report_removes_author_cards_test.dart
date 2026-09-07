import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/data/mini_profile_repository.dart';
import 'package:tailtopia/features/content/domain/feed_item.dart';
import 'package:tailtopia/features/content/domain/pinned_slot.dart';
import 'package:tailtopia/features/content/presentation/feed_controller.dart';
import 'package:tailtopia/features/social/data/account_report_repository.dart';
import 'package:tailtopia/features/social/data/blocked_users_repository.dart';
import 'package:tailtopia/features/social/domain/account_report_reason.dart';
import 'package:tailtopia/features/social/domain/blocked_user.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/mini_profile_sheet.dart';

/// V1.1.4 Story 2.3：举报成功收尾时，当前这一屏**立刻**不再有那个人的内容。
///
/// 服务端过滤保证的是「下次拉取不再出现」；这里补的是「现在这一屏」——
/// 否则用户举报完抬头就看见「我明明报了，他的帖子还挂在那儿」，会怀疑自己没提交成功。

const int _kViewerId = 5;
const int _kTargetId = 7;

FeedItem _item(int id, int authorId) => FeedItem(
      id: id,
      authorId: authorId,
      authorDeleted: false,
      authorNickname: 'A$authorId',
      authorAvatarUrl: null,
      type: 'DAILY',
      body: 'post $id',
      firstImageUrl: null,
      createdAt: DateTime.utc(2026, 6, 2),
    );

class _FakeFeedRepo implements FeedRepository {
  _FakeFeedRepo(this.items);
  final List<FeedItem> items;
  @override
  Future<FeedPage> getFeed({
    FeedCategory category = FeedCategory.all,
    String? cursor,
    int limit = 20,
  }) async =>
      FeedPage(items: items, nextCursor: null, hasMore: false);

  @override
  Future<PinnedSlot?> getPinnedSlot() async => null;
}

class _FakeMiniRepo implements MiniProfileRepository {
  @override
  Future<MiniProfile> getMiniProfile(int userId) async => const MiniProfile(
        postCount: 3,
        isDeactivated: false,
        nickname: 'Rina',
        avatarUrl: null,
      );
}

class _FakeReportRepo implements AccountReportRepository {
  final List<int> reported = [];
  @override
  Future<void> report(int targetUserId, AccountReportReason reason, {String? detail}) async =>
      reported.add(targetUserId);
}

class _NoopBlockRepo implements BlockedUsersRepository {
  @override
  Future<void> block(int userId) async {}
  @override
  Future<List<BlockedUser>> list() async => const <BlockedUser>[];
  @override
  Future<void> unblock(int userId) async {}
}

class _LoggedInAuth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: _kViewerId, nickname: 'Me', onboardingCompleted: true),
      );
}

void main() {
  late AppLocalizations l10n;

  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
  });

  /// 一屏 Feed：被举报者 7 有 3 条，另一个人 9 有 1 条。
  /// 页面上挂一个按钮直接打开迷你卡，回调用的是生产代码里那一套（`onAuthorHidden` 的等价接线）。
  Future<(ProviderContainer, _FakeReportRepo)> pump(WidgetTester tester) async {
    final reportRepo = _FakeReportRepo();
    final container = ProviderContainer(overrides: [
      feedRepositoryProvider.overrideWithValue(_FakeFeedRepo([
        _item(1, _kTargetId),
        _item(2, 9),
        _item(3, _kTargetId),
        _item(4, _kTargetId),
      ])),
      miniProfileRepositoryProvider.overrideWithValue(_FakeMiniRepo()),
      accountReportRepositoryProvider.overrideWithValue(reportRepo),
      blockedUsersRepositoryProvider.overrideWithValue(_NoopBlockRepo()),
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
                onPressed: () => showMiniProfile(
                  context,
                  ref,
                  _kTargetId,
                  // 与 home_page 的接线一致：举报成功 → 按作者移除，且**不给任何提示**。
                  onReported: () => ref.read(feedProvider.notifier).removeByAuthor(_kTargetId),
                ),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    ));
    await container.read(feedProvider.future); // 等首屏加载完
    await tester.pumpAndSettle();
    return (container, reportRepo);
  }

  Future<void> reportThroughSheet(WidgetTester tester) async {
    await tester.tap(find.byKey(const ValueKey('openMini')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('miniProfileMore')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('miniProfileMenuReport')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('accountReportReason_harassment')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('accountReportSubmit')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('accountReportDoneClose')));
    await tester.pumpAndSettle();
  }

  testWidgets('AC1：举报成功 → 该作者的**全部**卡片从当前列表消失（不是只掉点开的那一条）', (tester) async {
    final (container, repo) = await pump(tester);
    expect(container.read(feedProvider).value!.items, hasLength(4));

    await reportThroughSheet(tester);

    expect(repo.reported, <int>[_kTargetId]);
    final left = container.read(feedProvider).value!.items;
    expect(left.map((i) => i.id), <int>[2]); // 只剩别人那一条
    expect(left.every((i) => i.authorId != _kTargetId), isTrue);
  });

  testWidgets('⚠️ AC3：移除必须静默 —— 一个提示都不弹', (tester) async {
    await pump(tester);
    await reportThroughSheet(tester);

    // 既有的**帖子级**举报会弹「你将不再看到这条内容」；账号举报刻意不弹 ——
    // 任何这类提示都会泄露「举报会隐藏内容」，与成功态刻意不提隐藏的取舍冲突。
    expect(find.text(l10n.reportHiddenToast), findsNothing);
    expect(find.byType(SnackBar), findsNothing);
  });

  testWidgets('两层弹层都收起（回到点开迷你卡之前的页面）', (tester) async {
    await pump(tester);
    await reportThroughSheet(tester);

    expect(find.byKey(const ValueKey('accountReportSubmit')), findsNothing);
    expect(find.byKey(const ValueKey('miniProfileClose')), findsNothing);
    expect(find.byKey(const ValueKey('openMini')), findsOneWidget);
  });

  testWidgets('中途取消（没提交）→ 列表一条都不动', (tester) async {
    final (container, repo) = await pump(tester);

    await tester.tap(find.byKey(const ValueKey('openMini')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('miniProfileMore')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('miniProfileMenuReport')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('accountReportCancel')));
    await tester.pumpAndSettle();

    expect(repo.reported, isEmpty);
    expect(container.read(feedProvider).value!.items, hasLength(4));
    // 只收起了抽屉那一层，迷你卡还在。
    expect(find.byKey(const ValueKey('miniProfileClose')), findsOneWidget);
  });

  testWidgets('removeByAuthor 只动这个作者，别人的卡片一条不碰', (tester) async {
    final (container, _) = await pump(tester);

    container.read(feedProvider.notifier).removeByAuthor(9);

    expect(container.read(feedProvider).value!.items.map((i) => i.id), <int>[1, 3, 4]);
  });
}
