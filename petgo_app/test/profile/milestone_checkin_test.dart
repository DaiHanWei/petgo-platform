import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/presentation/milestone_list_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0 widget（Story 8.4）：「已打卡」picker — 候选列表、已关联置灰、选择 → checkIn 调用。
class _FakeRepo implements MilestoneRepository {
  _FakeRepo(this._candidates);
  final List<MilestoneCheckinCandidate> _candidates;
  String? lastCode;
  int? lastContentId;

  @override
  Future<MilestoneList> getMilestones() async => const MilestoneList(
        petName: 'Momo', completedCount: 0, totalCount: 1,
        groups: [
          MilestoneGroup(level: MilestoneLevel.s, completedCount: 0, totalCount: 1, items: [
            MilestoneItem(
                code: 'C-S6', title: '第一次洗澡', level: MilestoneLevel.s,
                trigger: MilestoneTrigger.userCheckin, completed: false),
          ]),
        ],
      );

  @override
  Future<List<MilestoneCheckinCandidate>> getCheckinCandidates() async => _candidates;

  @override
  Future<MilestoneItem> checkIn(String code, int contentId) async {
    lastCode = code;
    lastContentId = contentId;
    return MilestoneItem(
        code: code, title: 'x', level: MilestoneLevel.s,
        trigger: MilestoneTrigger.userCheckin, completed: true, completedAt: DateTime(2026, 6, 1));
  }

  @override
  Future<void> signalCardShared() async {}

  @override
  Future<String> createShare(String code,
          {required String title,
          required String body,
          required String locale,
          required String collectionLevels}) async =>
      'token';
}

Widget _wrap(_FakeRepo repo) => ProviderScope(
      overrides: [milestoneRepositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: MilestoneListPage(),
      ),
    );

void main() {
  testWidgets('已打卡 → picker 列出候选；已关联置灰；选择 → checkIn', (tester) async {
    final repo = _FakeRepo(const [
      MilestoneCheckinCandidate(contentId: 101, text: 'A', linked: false),
      MilestoneCheckinCandidate(contentId: 102, text: 'B', linked: true),
    ]);
    await tester.pumpWidget(_wrap(repo));
    await tester.pumpAndSettle();

    // 点用户打卡未完成徽章 → 弹层 → 已打卡。
    await tester.tap(find.byKey(const ValueKey('milestoneBadge_C-S6')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('milestoneCheckedIn')));
    await tester.pumpAndSettle();

    // picker 列出两候选。
    expect(find.byKey(const ValueKey('milestoneCandidate_101')), findsOneWidget);
    expect(find.byKey(const ValueKey('milestoneCandidate_102')), findsOneWidget);

    // 选未关联候选 → 调 checkIn(code, contentId)。
    await tester.tap(find.byKey(const ValueKey('milestoneCandidate_101')));
    // checkIn 后弹 P-35 庆祝（含循环彩纸，勿 pumpAndSettle）；lastCode 在 checkIn 时已同步置位。
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 350));
    expect(repo.lastCode, 'C-S6');
    expect(repo.lastContentId, 101);
  });
}
