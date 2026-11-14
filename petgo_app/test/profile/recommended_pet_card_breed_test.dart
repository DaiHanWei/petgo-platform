import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/content/presentation/like_button.dart';
import 'package:tailtopia/features/profile/data/pet_recommendation_repository.dart';
import 'package:tailtopia/features/social/presentation/widgets/pet_follow_button.dart';
import 'package:tailtopia/features/profile/presentation/widgets/recommended_pet_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.5.0 onboarding-recall Story 1.2 · AC8：推荐卡第二行「品种 · Bersama N hari」，无品种回落物种；
/// 新增可选参数默认值保持现有行为（无胶囊、无附加区）。
void main() {
  RecommendedPet pet({String? breed, String petType = 'DOG'}) => RecommendedPet(
        petId: 7,
        name: 'Kopi',
        avatarUrl: '',
        petType: petType,
        companionDays: 300,
        breed: breed,
      );

  Future<AppLocalizations> pump(WidgetTester tester, RecommendedPetCard card) async {
    await tester.pumpWidget(ProviderScope(
      child: MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(body: SizedBox(width: 180, height: 320, child: card)),
      ),
    ));
    await tester.pump();
    return AppLocalizations.delegate.load(const Locale('id'));
  }

  String meta(WidgetTester tester) =>
      tester.widget<Text>(find.byKey(const ValueKey('recommendedPetDays_7'))).data!;

  testWidgets('有品种：「品种 · Bersama N hari」', (tester) async {
    final l10n = await pump(tester, RecommendedPetCard(pet: pet(breed: 'Corgi'), from: 'x'));
    expect(meta(tester), 'Corgi · ${l10n.petRecommendCompanionDays(300)}');
    expect(meta(tester), 'Corgi · Bersama 300 hari');
  });

  testWidgets('无品种回落物种名', (tester) async {
    final l10n = await pump(tester, RecommendedPetCard(pet: pet(), from: 'x'));
    expect(meta(tester), '${l10n.petTypeDog} · Bersama 300 hari');
  });

  test('fromJson：品种空白视为无品种，新字段缺失容错', () {
    final p = RecommendedPet.fromJson({'petId': 1, 'breed': '  ', 'name': 'A'});
    expect(p.breed, isNull);
    expect(p.coverPostId, isNull);
    expect(p.coverLikeCount, 0);
    expect(p.coverLikedByViewer, isFalse);
    expect(p.match, isNull);
    final q = RecommendedPet.fromJson({
      'petId': 1, 'breed': ' Corgi ', 'coverPostId': 99, 'coverLikeCount': 3,
      'coverLikedByViewer': true, 'match': 'SAME_BREED',
    });
    expect(q.breed, 'Corgi');
    expect(q.coverPostId, 99);
    expect(q.coverLikeCount, 3);
    expect(q.coverLikedByViewer, isTrue);
    expect(q.match, 'SAME_BREED');
  });

  testWidgets('新参数默认值保持现状：没有胶囊、没有附加区', (tester) async {
    await pump(tester, RecommendedPetCard(pet: pet(breed: 'Corgi'), from: 'x'));
    expect(find.byType(LikeButton), findsNothing);
    expect(find.byType(PetFollowButton), findsNothing);
  });
}
