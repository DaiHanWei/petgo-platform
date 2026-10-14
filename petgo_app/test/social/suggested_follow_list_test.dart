import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/domain/pet_age.dart';
import 'package:tailtopia/features/social/data/follow_repository.dart';
import 'package:tailtopia/features/social/data/follow_suggestion_repository.dart';
import 'package:tailtopia/features/social/domain/follow_list_item.dart';
import 'package:tailtopia/features/social/domain/follow_state.dart';
import 'package:tailtopia/features/social/presentation/widgets/suggested_follow_list.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 1, nickname: 'Me', onboardingCompleted: true),
      );
}

class _SuggestRepo implements FollowSuggestionRepository {
  _SuggestRepo(this.result);
  final Object result;

  @override
  Future<List<FollowListItem>> fetch() async {
    if (result is List<FollowListItem>) return result as List<FollowListItem>;
    throw result;
  }
}

class _FollowRepo implements FollowRepository {
  @override
  Future<FollowResult> follow(String userToken) async =>
      const FollowResult(state: FollowState(iFollow: true, followsMe: false), followerCount: 1);

  @override
  Future<FollowResult> unfollow(String userToken) async =>
      const FollowResult(state: FollowState.none, followerCount: 0);
}

FollowListItem _cand(int id, {String? petType, DateTime? birthday}) => FollowListItem(
      userId: id,
      deleted: false,
      userToken: 'tok$id',
      nickname: 'cand$id',
      petType: petType,
      petBirthday: birthday,
    );

Future<void> _pump(WidgetTester tester, Object result, {int? maxItems, bool emphasized = false}) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      followSuggestionRepositoryProvider.overrideWithValue(_SuggestRepo(result)),
      followRepositoryProvider.overrideWithValue(_FollowRepo()),
      authControllerProvider.overrideWith(_Auth.new),
    ],
    child: MaterialApp(
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(body: SingleChildScrollView(child: SuggestedFollowList(source: 'suggested', maxItems: maxItems, emphasized: emphasized))),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  late AppLocalizations l10n;
  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
  });

  testWidgets('rendersSpeciesAndAgeSubtitle', (tester) async {
    final birthday = DateTime.now().subtract(const Duration(days: 365 * 2 + 40));
    await _pump(tester, [_cand(7, petType: 'DOG', birthday: birthday)]);

    final expected = '${l10n.petTypeDog} · ${formatPetAge(l10n, birthday)}';
    expect(find.text(expected), findsOneWidget);
    expect(find.text('cand7'), findsOneWidget);
    expect(find.byKey(const ValueKey('followSuggestTitle')), findsOneWidget);
  });

  testWidgets('noPetNoSubtitle', (tester) async {
    await _pump(tester, [_cand(8)]);

    expect(find.text('cand8'), findsOneWidget);
    expect(find.byKey(const ValueKey('followRowSubtitle_8')), findsNothing);
  });

  testWidgets('hiddenOnError', (tester) async {
    await _pump(tester, Exception('boom'));

    expect(find.byKey(const ValueKey('followSuggestTitle')), findsNothing);
    expect(find.byType(SuggestedFollowList), findsOneWidget);
    expect(tester.getSize(find.byType(SuggestedFollowList)).height, 0);
  });

  Color? followBg(WidgetTester tester, int id) {
    final btn = find.descendant(
        of: find.byKey(ValueKey('followSuggestRow_$id')), matching: find.byType(FilledButton));
    return tester.widget<FilledButton>(btn).style?.backgroundColor?.resolve({});
  }

  testWidgets('maxItems 限制行数；缺省显示全部', (tester) async {
    final list = [for (var i = 1; i <= 8; i++) _cand(i)];
    await _pump(tester, list, maxItems: 5);
    expect(find.byKey(const ValueKey('followSuggestRow_5')), findsOneWidget);
    expect(find.byKey(const ValueKey('followSuggestRow_6')), findsNothing);

    await _pump(tester, list);
    expect(find.byKey(const ValueKey('followSuggestRow_8')), findsOneWidget);
  });

  testWidgets('emphasized 才用实心紫 Follow 按钮 + 句首大写标题 + 无分隔线；默认保持浅紫', (tester) async {
    await _pump(tester, [_cand(1), _cand(2)], emphasized: true);
    expect(followBg(tester, 1), AppColors.mint);
    expect(find.text('Suggested for you'), findsOneWidget);
    expect(find.byType(Divider), findsNothing);

    await _pump(tester, [_cand(1), _cand(2)]);
    expect(followBg(tester, 1), AppColors.violet100);
    expect(find.text('SUGGESTED FOR YOU'), findsOneWidget);
    expect(find.byType(Divider), findsOneWidget);
  });
}
