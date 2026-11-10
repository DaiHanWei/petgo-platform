import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/like_repository.dart';
import 'package:tailtopia/features/content/presentation/like_button.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.2 social-topic · Story 1.5 · AC5.5：关注流卡片的 `post_like_tapped` 带 `reason`（事件名不变）；其余位置不带。
class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 1, nickname: 'Me', onboardingCompleted: true),
      );
}

class _Likes implements LikeRepository {
  @override
  Future<LikeResult> like(int postId) async => const LikeResult(liked: true, likeCount: 1);

  @override
  Future<LikeResult> unlike(int postId) async => const LikeResult(liked: false, likeCount: 0);
}

void main() {
  final events = <(String, Map<String, Object>?)>[];
  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<void> pump(WidgetTester tester, {String? reason, String? feedTab}) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        authControllerProvider.overrideWith(_Auth.new),
        likeRepositoryProvider.overrideWithValue(_Likes()),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: LikeButton(
            postId: 5,
            initialLiked: false,
            initialCount: 0,
            source: 'feed',
            likersSource: 'feed_card',
            feedTab: feedTab,
            rankMode: 'chrono',
            reason: reason,
          ),
        ),
      ),
    ));
    await tester.tap(find.byKey(const ValueKey('likeButton_5')));
    await tester.pumpAndSettle();
  }

  testWidgets('关注流：带 reason', (tester) async {
    await pump(tester, reason: 'followed_pet', feedTab: 'following');
    final e = events.singleWhere((e) => e.$1 == 'post_like_tapped');
    expect(e.$2!['reason'], 'followed_pet');
    expect(e.$2!['feed_tab'], 'following');
  });

  testWidgets('其余位置：不带 reason 键', (tester) async {
    await pump(tester, feedTab: 'all');
    final e = events.singleWhere((e) => e.$1 == 'post_like_tapped');
    expect(e.$2!.containsKey('reason'), isFalse);
  });
}
