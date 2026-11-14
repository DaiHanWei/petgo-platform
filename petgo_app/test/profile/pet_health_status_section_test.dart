import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/presentation/pet_profile_edit_page.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_health_status_section.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.5.0 健康记录提醒 Story 3.3：编辑页「Status Kesehatan」分组。
class _Repo implements ProfileRepository {
  _Repo(this.profile);
  final PetProfile profile;
  bool updateCalled = false;
  String? neuter;
  bool? cat;
  bool? pregnant;

  @override
  Future<void> deleteMyProfile({int? petId}) async {}

  @override
  Future<PetProfile> create({
    required String petType,
    required String name,
    required DateTime birthday,
    String? avatarUrl,
    String? breed,
    String? intro,
    double? weightKg,
    String? neuterStatus,
    String? sex,
    String? idempotencyKey,
  }) async =>
      profile;

  @override
  Future<PetProfile?> getMyProfile({int? petId}) async => profile;

  @override
  Future<PetProfile> update({
    String? name,
    String? avatarUrl,
    String? breed,
    DateTime? birthday,
    String? sex,
    String? intro,
    double? weightKg,
    String? neuterStatus,
    bool? catInHome,
    bool? pregnantNursing,
    int? petId,
  }) async {
    updateCalled = true;
    neuter = neuterStatus;
    cat = catInHome;
    pregnant = pregnantNursing;
    return profile;
  }
}

Future<_Repo> _pumpPage(WidgetTester tester, PetProfile p) async {
  await tester.binding.setSurfaceSize(const Size(440, 2200));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  final repo = _Repo(p);
  final router = GoRouter(initialLocation: '/profile/edit', routes: [
    GoRoute(path: '/profile/edit', builder: (_, _) => const PetProfileEditPage()),
    GoRoute(path: '/profile', builder: (_, _) => const Scaffold(body: Text('diary-tab'))),
  ]);
  addTearDown(router.dispose);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      profileRepositoryProvider.overrideWithValue(repo),
      petProfileProvider.overrideWith((ref) async => p),
    ],
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
    ),
  ));
  await tester.pumpAndSettle();
  return repo;
}

PetProfile _pet({String? type, String? sex, String? neuter, bool? cat, bool pregnant = false, DateTime? off}) =>
    PetProfile(
      id: 3,
      name: 'Mochi',
      cardToken: 'T',
      petType: type,
      sex: sex,
      neuterStatus: neuter,
      catInHome: cat,
      pregnantNursing: pregnant,
      pregnantAutoOffDate: off,
    );

final _neuter = find.byKey(const ValueKey('petHealthNeuter_NEUTERED'));
final _cat = find.byKey(const ValueKey('petHealthCatAtHome_true'));
final _pregnant = find.byKey(const ValueKey('petHealthPregnant_true'));

bool _selected(WidgetTester tester, String key) {
  final sem = tester.widget<Semantics>(
      find.ancestor(of: find.byKey(ValueKey(key)), matching: find.byType(Semantics)).first);
  return sem.properties.selected ?? false;
}

void main() {
  group('AC1 显示条件（PRD AC15、UI F4 / F5）', () {
    testWidgets('母猫：绝育 + 怀孕，无「家里有猫」', (tester) async {
      await _pumpPage(tester, _pet(type: 'CAT', sex: 'FEMALE'));
      expect(find.text('Health status'), findsOneWidget);
      expect(find.text('Used for health record notifications'), findsOneWidget);
      expect(_neuter, findsOneWidget);
      expect(_pregnant, findsOneWidget);
      expect(_cat, findsNothing);
    });

    testWidgets('公狗：绝育 + 家里有猫，无怀孕', (tester) async {
      await _pumpPage(tester, _pet(type: 'DOG', sex: 'MALE'));
      expect(_neuter, findsOneWidget);
      expect(_cat, findsOneWidget);
      expect(_pregnant, findsNothing);
    });

    testWidgets('母狗：三项全有；顺序 Berat badan → 分组 → Bio', (tester) async {
      await _pumpPage(tester, _pet(type: 'DOG', sex: 'FEMALE'));
      expect(_neuter, findsOneWidget);
      expect(_cat, findsOneWidget);
      expect(_pregnant, findsOneWidget);
      final weight = tester.getTopLeft(find.byKey(const ValueKey('petProfileEditWeightField'))).dy;
      final section = tester.getTopLeft(find.byKey(const ValueKey('petHealthStatusSection'))).dy;
      final bio = tester.getTopLeft(find.byKey(const ValueKey('petProfileEditIntroField'))).dy;
      expect(weight < section && section < bio, isTrue);
    });

    testWidgets('F4 性别未填：显示怀孕项', (tester) async {
      await _pumpPage(tester, _pet(type: 'CAT'));
      expect(_pregnant, findsOneWidget);
    });

    testWidgets('F5 非猫狗（含未来新增物种）：整个分组不显示', (tester) async {
      await _pumpPage(tester, _pet(type: 'OTHER', sex: 'FEMALE'));
      expect(find.byKey(const ValueKey('petHealthStatusSection')), findsNothing);
    });

    testWidgets('F5 补：④ 线新物种（如 RABBIT）同样不显示', (tester) async {
      await _pumpPage(tester, _pet(type: 'RABBIT', sex: 'FEMALE'));
      expect(find.byKey(const ValueKey('petHealthStatusSection')), findsNothing);
    });

    testWidgets('neuterStatus / catInHome 为 null → 都不选中', (tester) async {
      await _pumpPage(tester, _pet(type: 'DOG', sex: 'FEMALE'));
      for (final k in ['NEUTERED', 'INTACT', 'UNKNOWN']) {
        expect(_selected(tester, 'petHealthNeuter_$k'), isFalse, reason: k);
      }
      expect(_selected(tester, 'petHealthCatAtHome_true'), isFalse);
      expect(_selected(tester, 'petHealthCatAtHome_false'), isFalse);
    });

    testWidgets('已填 → 选中对应项', (tester) async {
      await _pumpPage(tester, _pet(type: 'DOG', sex: 'FEMALE', neuter: 'INTACT', cat: false));
      expect(_selected(tester, 'petHealthNeuter_INTACT'), isTrue);
      expect(_selected(tester, 'petHealthCatAtHome_false'), isTrue);
    });

    testWidgets('怀孕 = Ya 且有自动关闭日 → 下方说明行', (tester) async {
      await _pumpPage(tester, _pet(type: 'CAT', sex: 'FEMALE', pregnant: true, off: DateTime(2027, 3, 14)));
      expect(find.text('Turns off automatically on 14 Mar 2027.'), findsOneWidget);
    });

    testWidgets('本页把性别改成 MALE → 怀孕项立即隐藏', (tester) async {
      await _pumpPage(tester, _pet(type: 'CAT'));
      expect(_pregnant, findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('petProfileEditSexTile')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Male').last);
      await tester.pumpAndSettle();
      expect(_pregnant, findsNothing);
    });

    test('showsGroup / showsPregnant 判定', () {
      expect(PetHealthStatusSection.showsGroup('CAT'), isTrue);
      expect(PetHealthStatusSection.showsGroup('DOG'), isTrue);
      expect(PetHealthStatusSection.showsGroup('OTHER'), isFalse);
      expect(PetHealthStatusSection.showsGroup(null), isFalse);
      expect(PetHealthStatusSection.showsPregnant(null), isTrue);
      expect(PetHealthStatusSection.showsPregnant('FEMALE'), isTrue);
      expect(PetHealthStatusSection.showsPregnant('MALE'), isFalse);
    });
  });

  group('AC2 保存', () {
    testWidgets('只发变化了的字段 + 每个变化字段报 health_profile_field_updated', (tester) async {
      final events = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);
      final repo = await _pumpPage(tester, _pet(type: 'DOG', sex: 'FEMALE', neuter: 'INTACT', cat: false));
      await tester.tap(find.byKey(const ValueKey('petHealthNeuter_NEUTERED')));
      await tester.tap(find.byKey(const ValueKey('petHealthPregnant_true')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('petProfileEditSubmit')));
      await tester.pumpAndSettle();
      expect(repo.updateCalled, isTrue);
      expect(repo.neuter, 'NEUTERED');
      expect(repo.pregnant, isTrue);
      expect(repo.cat, isNull, reason: '没改 → 不发');
      final updates = events.where((e) => e.$1 == 'health_profile_field_updated').map((e) => e.$2).toList();
      expect(updates, [
        {'pet_id': 3, 'field': 'neuter_status', 'value': 'neutered'},
        {'pet_id': 3, 'field': 'pregnant_nursing', 'value': true},
      ]);
    });

    testWidgets('什么都没改 → 三个字段都不发、不报埋点', (tester) async {
      final events = <String>[];
      Analytics.debugCaptureSink = (e, p) => events.add(e);
      addTearDown(() => Analytics.debugCaptureSink = null);
      final repo = await _pumpPage(tester, _pet(type: 'DOG', sex: 'FEMALE', neuter: 'INTACT'));
      await tester.tap(find.byKey(const ValueKey('petProfileEditSubmit')));
      await tester.pumpAndSettle();
      expect(repo.updateCalled, isTrue);
      expect([repo.neuter, repo.cat, repo.pregnant], [null, null, null]);
      expect(events, isNot(contains('health_profile_field_updated')));
    });

    testWidgets('选了怀孕后把性别改成 MALE → 提交不带怀孕项', (tester) async {
      final repo = await _pumpPage(tester, _pet(type: 'CAT'));
      await tester.tap(_pregnant);
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('petProfileEditSexTile')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Male').last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('petProfileEditSubmit')));
      await tester.pumpAndSettle();
      expect(repo.pregnant, isNull);
    });

    test('仓储：只把非 null 的健康字段放进 PATCH 体', () {
      // 由 DioProfileRepository.update 的 `if (x != null) data[...]` 保证；这里钉住草稿层的「只发变化」。
      final d = PetHealthStatusDraft()..load(_pet(type: 'DOG', neuter: 'UNKNOWN', cat: true));
      expect(d.changedNeuterStatus, isNull);
      expect(d.changedCatInHome('DOG'), isNull);
      d.setCatInHome(false);
      expect(d.changedCatInHome('DOG'), isFalse);
      expect(d.changedCatInHome('CAT'), isNull, reason: '只 DOG 发');
      d.dispose();
    });
  });
}
