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

  @override
  Future<String> createShare(String code,
      {required String title,
      required String body,
      required String locale,
      required String collectionLevels}) async {
    lastCode = code;
    lastTitle = title;
    lastLocale = locale;
    lastCollectionLevels = collectionLevels;
    if (fail) throw Exception('backend down');
    return token!;
  }

  @override
  Future<MilestoneList> getMilestones() => throw UnimplementedError();
  @override
  Future<void> signalCardShared() => throw UnimplementedError();
  @override
  Future<List<MilestoneCheckinCandidate>> getCheckinCandidates() => throw UnimplementedError();
  @override
  Future<MilestoneItem> checkIn(String code, int contentId) => throw UnimplementedError();
}

const _item = MilestoneItem(
  code: 'C-S5',
  title: 'First post',
  level: MilestoneLevel.s,
  trigger: MilestoneTrigger.userCheckin,
  completed: true,
);

Future<String?> _runShare(WidgetTester tester, _FakeRepo repo) async {
  String? captured;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      milestoneRepositoryProvider.overrideWithValue(repo),
      shareServiceProvider.overrideWithValue((text) async => captured = text),
    ],
    child: MaterialApp(
      home: Consumer(builder: (context, ref, _) {
        return ElevatedButton(
          onPressed: () => shareMilestoneWithLink(ref,
              item: _item,
              locale: const Locale('id'),
              petName: 'Momo',
              shareText: 'SHARE_TEXT',
              collection: const [
                MilestoneItem(
                    code: 'C-L1',
                    title: 'x',
                    level: MilestoneLevel.l,
                    trigger: MilestoneTrigger.systemAuto,
                    completed: true),
                MilestoneItem(
                    code: 'C-S5',
                    title: 'x',
                    level: MilestoneLevel.s,
                    trigger: MilestoneTrigger.userCheckin,
                    completed: true),
                MilestoneItem(
                    code: 'C-S6',
                    title: 'x',
                    level: MilestoneLevel.s,
                    trigger: MilestoneTrigger.userCheckin,
                    completed: false), // 未完成 → 不计入合集串
              ]),
          child: const Text('go'),
        );
      }),
    ),
  ));
  await tester.tap(find.text('go'));
  await tester.pumpAndSettle();
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
    expect(shared, 'SHARE_TEXT\nhttps://s.tailtopia.id/m/Ab3xK9');
  });

  testWidgets('后端建分享失败 → 退化为纯文案分享（不阻断、不带链接）', (tester) async {
    final repo = _FakeRepo(fail: true);
    final shared = await _runShare(tester, repo);

    expect(shared, 'SHARE_TEXT');
  });
}
