import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/milestone_repository.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/milestone_share.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';

/// L0（P-35 分享链接）：建分享成功 → 文案后附 `/m/{token}` H5 URL；后端失败 → 退化为纯文案分享（不阻断）。
class _FakeRepo implements MilestoneRepository {
  _FakeRepo({this.token, this.fail = false});
  final String? token;
  final bool fail;
  String? lastCode;
  String? lastTitle;
  String? lastLocale;
  String? lastCollectionLevels;
  List<String>? lastCollectionCodes;
  int? lastPetId;

  @override
  Future<String> createShare(String code,
      {required String title,
      required String body,
      required String locale,
      required String collectionLevels,
      required List<String> collectionCodes, int? petId}) async {
    lastCode = code;
    lastPetId = petId;
    lastCollectionCodes = collectionCodes;
    lastTitle = title;
    lastLocale = locale;
    lastCollectionLevels = collectionLevels;
    if (fail) throw Exception('backend down');
    return token!;
  }

  /// V1.3.0 Story 1.5 新增的回报通道；本类不验它，记下调用即可。
  List<String>? lastReportedCelebrations;

  @override
  Future<void> reportCelebrations(List<String> codes, {int? petId}) async {
    lastReportedCelebrations = codes;
  }

  @override
  Future<MilestoneList> getMilestones({int? petId}) => throw UnimplementedError();
  @override
  Future<void> signalCardShared({int? petId}) => throw UnimplementedError();
  @override
  Future<List<MilestoneCheckinCandidate>> getCheckinCandidates({int? petId}) => throw UnimplementedError();
  @override
  Future<MilestoneItem> checkIn(String code, int contentId, {int? petId}) => throw UnimplementedError();
}

const _item = MilestoneItem(
  code: 'C-S5',
  title: 'First post',
  level: MilestoneLevel.s,
  trigger: MilestoneTrigger.userCheckin,
  completed: true,
);

const _collection = [
  MilestoneItem(code: 'C-L1', title: 'x', level: MilestoneLevel.l, trigger: MilestoneTrigger.systemAuto, completed: true),
  MilestoneItem(code: 'C-S5', title: 'x', level: MilestoneLevel.s, trigger: MilestoneTrigger.userCheckin, completed: true),
  MilestoneItem(
      code: 'C-S6',
      title: 'x',
      level: MilestoneLevel.s,
      trigger: MilestoneTrigger.userCheckin,
      completed: false), // 未完成 → 不计入合集串
];

/// V1.4.2 Story 3.2：改为经 [ProviderContainer] 调（不再要宿主 WidgetRef）。
Future<String?> _runShare(WidgetTester tester, _FakeRepo repo,
    {int? petId, Rect? origin, List<String>? stages, List<Rect?>? origins, bool shareThrows = false, List<bool>? results}) async {
  String? captured;
  final c = ProviderContainer(overrides: [
    milestoneRepositoryProvider.overrideWithValue(repo),
    shareServiceProvider.overrideWithValue((text, {sharePositionOrigin}) async {
      origins?.add(sharePositionOrigin);
      if (shareThrows) throw StateError('share sheet failed');
      captured = text;
    }),
  ]);
  addTearDown(c.dispose);
  final ok = await shareMilestoneWithLink(c,
      item: _item,
      locale: const Locale('id'),
      petName: 'Momo',
      shareText: 'SHARE_TEXT',
      petId: petId,
      sharePositionOrigin: origin,
      onFailed: stages?.add,
      collection: _collection);
  results?.add(ok);
  return captured;
}

void main() {
  testWidgets('建分享成功 → 系统分享文案后附 H5 链接（/m/{token}）', (tester) async {
    final repo = _FakeRepo(token: 'Ab3xK9');
    final shared = await _runShare(tester, repo);

    expect(repo.lastCode, 'C-S5');
    expect(repo.lastLocale, 'id'); // 按 locale 出文案
    expect(repo.lastTitle, isNotEmpty); // 客户端已本地化的庆祝标题
    expect(repo.lastCollectionLevels, 'LS'); // 仅已完成项、按合集顺序，每字符 S/M/L
    // V1.3.2 Story 5.2：code 列表与级别串同序同长（同一过滤）。
    expect(repo.lastCollectionCodes, ['C-L1', 'C-S5']);
    expect(repo.lastCollectionCodes, hasLength(repo.lastCollectionLevels!.length));
    expect(repo.lastCollectionCodes, isNot(contains('C-S6')), reason: '未完成项不计入');
    expect(shared, 'SHARE_TEXT\nhttps://s.tailtopia.id/m/Ab3xK9');
  });

  testWidgets('后端建分享失败 → 退化为纯文案分享（不阻断、不带链接）', (tester) async {
    final repo = _FakeRepo(fail: true);
    final shared = await _runShare(tester, repo);

    expect(shared, 'SHARE_TEXT');
  });

  testWidgets('OCR #5：传了 petId（多宠物逐只庆祝）→ 建分享按这只宠物，不取当前宠物', (tester) async {
    final repo = _FakeRepo(token: 'Ab3xK9');
    await _runShare(tester, repo, petId: 77);
    expect(repo.lastPetId, 77);
  });

  // ---- V1.4.2 social-topic Story 3.2 · AC5 / T6.4 ----

  testWidgets('建链失败 → 纯文案分享 + onFailed(create_link)，照常拉起面板', (tester) async {
    final stages = <String>[];
    final results = <bool>[];
    final shared = await _runShare(tester, _FakeRepo(fail: true), stages: stages, results: results);
    expect(shared, 'SHARE_TEXT');
    expect(stages, ['create_link']);
    expect(results, [true]);
  });

  testWidgets('分享服务抛错 → 不抛出、onFailed(open_sheet)、返回 false', (tester) async {
    final stages = <String>[];
    final results = <bool>[];
    await _runShare(tester, _FakeRepo(token: 'Ab3xK9'), stages: stages, shareThrows: true, results: results);
    expect(stages, ['open_sheet']);
    expect(results, [false]);
  });

  testWidgets('sharePositionOrigin 原样透传给分享服务（iOS 必传）', (tester) async {
    final origins = <Rect?>[];
    const rect = Rect.fromLTWH(28, 700, 334, 52);
    await _runShare(tester, _FakeRepo(token: 'Ab3xK9'), origin: rect, origins: origins);
    expect(origins, [rect]);
  });

  test('源码：shareMilestoneWithLink 不再有 WidgetRef 调用方（T4.2）', () {
    final hits = <String>[];
    for (final f in Directory('lib').listSync(recursive: true).whereType<File>()) {
      if (!f.path.endsWith('.dart')) continue;
      final src = f.readAsStringSync();
      if (RegExp(r'shareMilestoneWithLink\(\s*ref\b').hasMatch(src)) hits.add(f.path);
    }
    expect(hits, isEmpty);
  });
}
