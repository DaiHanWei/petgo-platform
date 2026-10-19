import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/milestone_celebration_reporter.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/milestone_instant_codes.dart';
import 'package:tailtopia/features/profile/presentation/milestone_instant_celebration.dart';
import 'package:tailtopia/features/profile/presentation/widgets/milestone_badge.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'milestone_instant_fakes.dart';

/// V1.4.0 multi-pet Story 3.3 · code review #1：逐只庆祝（AC6.6）的本机已庆祝集合按 (petId, code) 记，
/// 回报落到被庆祝的那只宠物；不带 petId 的调用方（单宠物）行为不变。
class _PerPetRepo implements MilestoneRepository {
  final reported = <(List<String>, int?)>[];
  final celebrated = <int>{};
  final calls = <int?>[];

  @override
  Future<MilestoneList> getMilestones({int? petId}) async {
    calls.add(petId);
    return instantMilestoneList('C-S17',
        completed: true, celebrated: celebrated.contains(petId), petName: 'Pet$petId');
  }

  @override
  Future<void> reportCelebrations(List<String> codes, {int? petId}) async {
    reported.add((codes, petId));
    if (petId != null) celebrated.add(petId);
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

ProviderContainer _container(_PerPetRepo repo) {
  final c = ProviderContainer(overrides: [
    milestoneRepositoryProvider.overrideWithValue(repo),
    milestoneInstantPollDelaysProvider.overrideWithValue(const [Duration.zero]),
    effectivePetIdProvider.overrideWithValue(const AsyncData(1)), // 当前宠物 = 1
  ]);
  addTearDown(c.dispose);
  c.read(effectivePetIdProvider);
  return c;
}

class _Host extends ConsumerWidget {
  const _Host(this.petIds);
  final List<int> petIds;

  @override
  Widget build(BuildContext context, WidgetRef ref) => Scaffold(
        body: Center(
          child: TextButton(
            key: const ValueKey('go'),
            onPressed: () => celebrateAutoMilestonesForPets(context, ref, kPlaceCheckinMilestoneCodes, petIds),
            child: const Text('host'),
          ),
        ),
      );
}

void main() {
  setUp(() => MilestoneBadgeAssets.debugOverride = const {});
  tearDown(() => MilestoneBadgeAssets.debugOverride = null);

  group('LocallyCelebratedMilestones 按 (petId, code)', () {
    test('非当前宠物的庆祝不进 state、不挡别的宠物', () {
      final c = _container(_PerPetRepo());
      final n = c.read(locallyCelebratedMilestonesProvider.notifier);
      n.add(['C-S17'], petId: 2);
      expect(c.read(locallyCelebratedMilestonesProvider), isEmpty);
      expect(n.codesFor(2), {'C-S17'});
      expect(n.codesFor(3), isEmpty);
      expect(n.codesFor(1), isEmpty);
    });

    test('当前宠物的逐只庆祝同时记进 state（列表页照旧挡得住）', () {
      final c = _container(_PerPetRepo());
      final n = c.read(locallyCelebratedMilestonesProvider.notifier);
      n.add(['C-S17'], petId: 1);
      expect(c.read(locallyCelebratedMilestonesProvider), {'C-S17'});
      expect(n.codesFor(1), {'C-S17'});
      expect(n.codesFor(2), isEmpty);
    });

    test('report 带 petId → 回报落到那只；不带 → 当前宠物（原口径）', () async {
      final repo = _PerPetRepo();
      final c = _container(repo);
      final n = c.read(locallyCelebratedMilestonesProvider.notifier);
      n.report(['C-S17'], petId: 3);
      n.report(['C-M8']);
      await Future<void>.delayed(Duration.zero);
      expect(repo.reported.map((r) => r.$2), [3, 1]);
    });
  });

  testWidgets('两只宠物同一 code：各弹一次、各自回报到自己的 petId', (tester) async {
    final repo = _PerPetRepo();
    final c = _container(repo);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: c,
      child: const MaterialApp(
        locale: Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: _Host([2, 3]),
      ),
    ));
    final celebration = find.byKey(const ValueKey('milestoneCelebration'));
    Future<void> settleSome() async {
      for (var i = 0; i < 10; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
    }

    await tester.tap(find.byKey(const ValueKey('go')));
    await settleSome();
    expect(celebration, findsOneWidget);
    expect(find.textContaining('Pet2'), findsWidgets);
    tester.widget<IconButton>(find.byKey(const ValueKey('milestoneCelebrationClose'))).onPressed!();
    await settleSome();

    // A（2）的 C-S17 不挡 B（3）。
    expect(celebration, findsOneWidget);
    expect(find.textContaining('Pet3'), findsWidgets);
    tester.widget<IconButton>(find.byKey(const ValueKey('milestoneCelebrationClose'))).onPressed!();
    await tester.pumpAndSettle();
    expect(celebration, findsNothing);

    expect(repo.reported.map((r) => '${r.$1.join(',')}@${r.$2}'), ['C-S17@2', 'C-S17@3']);
    // 当前宠物（1）的 state 未被别的宠物污染。
    expect(c.read(locallyCelebratedMilestonesProvider), isEmpty);
  });
}
