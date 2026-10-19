import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/router/route_intent.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/content/data/like_repository.dart';
import 'package:tailtopia/features/content/data/post_likers_repository.dart';
import 'package:tailtopia/features/content/presentation/like_button.dart';
import 'package:tailtopia/features/social/domain/follow_list_item.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.4 点赞人列表 · Task 3：点赞区拆成「爱心 = 点赞」「数字 = 点赞人列表」两个互不重叠的热区。

class _LikeRepo implements LikeRepository {
  int likeCalls = 0;
  int unlikeCalls = 0;

  @override
  Future<LikeResult> like(int postId) async {
    likeCalls++;
    return const LikeResult(liked: true, likeCount: 4);
  }

  @override
  Future<LikeResult> unlike(int postId) async {
    unlikeCalls++;
    return const LikeResult(liked: false, likeCount: 2);
  }
}

class _LikersRepo implements PostLikersRepository {
  int calls = 0;

  @override
  Future<FollowListPageData> getLikers(int postId, {String? cursor}) async {
    calls++;
    return const FollowListPageData(
        items: [FollowListItem(userId: 7, deleted: false, nickname: 'Budi', userToken: 'tok7')]);
  }
}

class _LoggedIn extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 1, nickname: 'Me', onboardingCompleted: true),
      );
}

class _Guest extends AuthController {
  @override
  AuthState build() => const AuthState.guest();
}

class _Guide extends LoginGuideController {
  _Guide() : super(() async => null);
  int hardDialogs = 0;
  String? lastEntrySource;

  @override
  Future<void> showHardDialog(BuildContext context,
      {RouteIntent? pendingAction, String entrySource = 'other'}) async {
    hardDialogs++;
    lastEntrySource = entrySource;
  }
}

class _Env {
  _Env(this.like, this.likers, this.guide);
  final _LikeRepo like;
  final _LikersRepo likers;
  final _Guide guide;
}

Future<_Env> _pump(WidgetTester tester,
    {int count = 3, bool loggedIn = true, bool expand = true}) async {
  final env = _Env(_LikeRepo(), _LikersRepo(), _Guide());
  final container = ProviderContainer(overrides: [
    likeRepositoryProvider.overrideWithValue(env.like),
    postLikersRepositoryProvider.overrideWithValue(env.likers),
    authControllerProvider.overrideWith(loggedIn ? _LoggedIn.new : _Guest.new),
    loginGuideControllerProvider.overrideWithValue(env.guide),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: Center(
          child: LikeButton(
            postId: 5,
            initialLiked: false,
            initialCount: count,
            source: 'feed',
            likersSource: 'feed_card',
            expandCountHitArea: expand,
          ),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  return env;
}

final _heart = find.byKey(const ValueKey('likeButton_5'));
final _count = find.byKey(const ValueKey('likeCount_5'));
final _sheet = find.byKey(const ValueKey('postLikersSheet'));

void main() {
  final events = <(String, Map<String, Object>?)>[];
  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  testWidgets('tapHeartTogglesLikeOnly', (tester) async {
    final env = await _pump(tester);
    await tester.tap(_heart);
    await tester.pumpAndSettle();
    expect(env.like.likeCalls, 1);
    expect(find.text('4'), findsOneWidget);
    expect(_sheet, findsNothing, reason: '点爱心不弹抽屉');
    expect(env.likers.calls, 0);
  });

  testWidgets('tapCountOpensSheet', (tester) async {
    final env = await _pump(tester);
    await tester.tap(_count);
    await tester.pumpAndSettle();
    expect(_sheet, findsOneWidget);
    expect(env.like.likeCalls + env.like.unlikeCalls, 0, reason: '点数字不切换点赞');
    expect(find.text('3'), findsOneWidget);
    expect(events.where((e) => e.$1 == 'post_likers_opened').map((e) => e.$2), [
      {'source': 'feed_card'},
    ]);
  });

  testWidgets('hitAreasDoNotOverlap', (tester) async {
    await _pump(tester);
    final heart = tester.getRect(_heart);
    final count = tester.getRect(_count);
    expect(heart.overlaps(count), isFalse);
    expect(count.left, greaterThanOrEqualTo(heart.right));
    final text = tester.getRect(find.text('3'));
    expect(count.width, greaterThan(text.width), reason: '数字热区比数字本身大');
  });

  testWidgets('zeroCountNoop', (tester) async {
    final env = await _pump(tester, count: 0);
    await tester.tap(_count);
    await tester.pumpAndSettle();
    expect(_sheet, findsNothing);
    expect(env.likers.calls, 0);
    expect(env.guide.hardDialogs, 0);
    expect(env.like.likeCalls, 0);
  });

  testWidgets('zeroThenLikedLocallyOpens（以本地乐观计数判定）', (tester) async {
    await _pump(tester, count: 0);
    await tester.tap(_heart);
    await tester.pumpAndSettle();
    await tester.tap(_count);
    await tester.pumpAndSettle();
    expect(_sheet, findsOneWidget);
  });

  testWidgets('guestTapCountShowsLogin', (tester) async {
    final env = await _pump(tester, loggedIn: false);
    await tester.tap(_count);
    await tester.pumpAndSettle();
    expect(env.guide.hardDialogs, 1);
    expect(env.guide.lastEntrySource, 'likers');
    expect(_sheet, findsNothing);
    expect(env.likers.calls, 0);
    expect(events.where((e) => e.$1 == 'post_likers_opened'), isEmpty);
  });

  testWidgets('countHitAreaTallerThanTextAndRowHeightUnchanged', (tester) async {
    await _pump(tester);
    final count = tester.getRect(_count);
    final text = tester.getRect(find.text('3'));
    expect(count.height, greaterThanOrEqualTo(31.9));
    // 整个点赞组高度 = 数字热区高度，(32dp) 由卡片上下内边距各减 6 抵消。
    expect(count.height - 20, closeTo(2 * LikeButton.countVerticalHitTrim, 0.5));
    // 文字上下 5dp 处仍能点开抽屉。
    await tester.tapAt(Offset(text.center.dx, text.top - 5));
    await tester.pumpAndSettle();
    expect(_sheet, findsOneWidget);
  });

  testWidgets('countHitBelowTextOpensSheetHeartUnaffected', (tester) async {
    final env = await _pump(tester);
    final text = tester.getRect(find.text('3'));
    await tester.tapAt(Offset(text.center.dx, text.bottom + 5));
    await tester.pumpAndSettle();
    expect(_sheet, findsOneWidget);
    expect(env.like.likeCalls + env.like.unlikeCalls, 0);
  });

  testWidgets('heartRegionAboveTextLevelStillToggles', (tester) async {
    final env = await _pump(tester);
    final heart = tester.getRect(_heart);
    await tester.tapAt(heart.center);
    await tester.pumpAndSettle();
    expect(env.like.likeCalls, 1);
    expect(_sheet, findsNothing);
  });

  testWidgets('detailVariantHasNoExtraTrailingOrVerticalHitPadding', (tester) async {
    await _pump(tester, expand: false);
    final count = tester.getRect(_count);
    final text = tester.getRect(find.text('3'));
    expect(count.right, closeTo(text.right, 0.01), reason: '数字右侧无额外透明热区');
    expect(count.height, closeTo(text.height, 0.01), reason: '纵向无额外热区');
    await tester.tap(_count);
    await tester.pumpAndSettle();
    expect(_sheet, findsOneWidget);
  });
}
