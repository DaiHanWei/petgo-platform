import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_share_reward_repository.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/presentation/share_card/share_card_preview_page.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/card_link.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_owner_type_repository.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_providers.dart';
import 'package:tailtopia/features/tailsonality/domain/content/ts_match_copy.dart';
import 'package:tailtopia/features/tailsonality/domain/tailsonality_result.dart';
import 'package:tailtopia/features/tailsonality/domain/ts_match_share_data.dart';
import 'package:tailtopia/features/tailsonality/presentation/share/match_share_card.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_match_page.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_match_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_canvas.dart';
import 'package:tailtopia/shared/card_render/card_frame.dart';
import 'package:tailtopia/shared/card_render/card_qr.dart';
import 'package:tailtopia/shared/card_render/card_render_pipeline.dart';
import 'package:tailtopia/shared/card_render/card_watermark.dart';
import '../keepsake/fake_share_reward_repos.dart';

/// V1.3.2 Story 4.2：配型卡分享（L0 部分）。

// V1.3.2 Story 4.5：领奖上报替身（每个用例重置）。
late FakeTailsonalityShareReward shareReward;

void main() {
  setUp(() => shareReward = FakeTailsonalityShareReward());
  const en = Locale('en');

  group('AC1.2 信息段数据拼装（MatchShareCardData.from）', () {
    MatchShareCardData d(String owner, String petCode, {String? ownerName = 'Aurel'}) =>
        MatchShareCardData.from(petName: 'Momo', ownerName: ownerName, petCode: petCode, ownerType: owner, locale: en);

    test('五档：相同字母数 → 档号 / 档名；有无昵称', () {
      final cases = {
        'ENTJ': 4, // 4/4
        'INTJ': 3,
        'INFJ': 2,
        'ESFP': 1,
        'ISFP': 0,
      };
      cases.forEach((owner, same) {
        for (final name in ['Aurel', null]) {
          final x = d(owner, 'ENTJ-H', ownerName: name);
          expect(x.sameCount, same, reason: owner);
          expect(x.tier, 5 - same, reason: owner);
          expect(x.tierName, kTsMatchTiers[same]!.name.en);
          expect(x.ownerName, name);
          expect(x.petType, 'ENTJ', reason: '埋点 pet_type 不含能量后缀');
          expect(x.petCode, 'ENTJ-H');
        }
      });
    });

    test('4/4：无差异句，改放档位总评首句', () {
      final x = d('ENTJ', 'ENTJ-H');
      expect(x.lines, ['Same soul, two bodies.']);
      final xi = MatchShareCardData.from(
          petName: 'Momo', ownerName: null, petCode: 'ENTJ-H', ownerType: 'ENTJ', locale: const Locale('id'));
      expect(xi.lines, ['Satu jiwa, dua badan.']);
    });

    test('3/4 恰 1 条；0/4 恰 2 条且为 E/I、T/F（取句逻辑来自 computeTsMatch，不重写）', () {
      expect(d('INTJ', 'ENTJ-H').lines, [kTsAxisDiffLines['IE']!.en]);
      final zero = d('ISFP', 'ENTJ-H');
      expect(zero.lines, [kTsAxisDiffLines['IE']!.en, kTsAxisDiffLines['FT']!.en]);
      expect(d('ESFP', 'ENTJ-H').lines, hasLength(2));
    });

    test('空白昵称视同取不到', () {
      expect(d('ENTJ', 'ENTJ-H', ownerName: '  ').ownerName, isNull);
    });

    test('首句截取：遇首个句末标点即止，去掉 ** 加粗标记', () {
      expect(MatchShareCardData.firstSentence('A **b**. C.'), 'A b.');
      expect(MatchShareCardData.firstSentence('No stop'), 'No stop');
      expect(MatchShareCardData.firstSentence('Wow! Next'), 'Wow!');
    });
  });

  group('AC1 卡面', () {
    Future<void> pumpCard(WidgetTester tester, MatchShareCardData data) async {
      tester.view.physicalSize = const Size(1080, 1920);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: SizedBox(width: 1080, height: 1920, child: MatchShareCard(data: data, canvas: CardCanvas.story)),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('名字行 / 代号行（宠物在前）/ 档名 / 差异句；码指向 /get', (tester) async {
      await pumpCard(
          tester,
          MatchShareCardData.from(
              petName: 'Momo', ownerName: 'Aurel', petCode: 'ENTJ-H', ownerType: 'INFP', locale: const Locale('id')));
      expect(find.text('Momo × Aurel'), findsOneWidget);
      expect(find.text('ENTJ-H × INFP'), findsOneWidget);
      expect(find.text('Counterweight'), findsOneWidget);
      expect(find.byKey(const ValueKey('matchShareCardLines')), findsOneWidget);
      expect(find.byType(TsMatchCardFace), findsOneWidget, reason: '主体段复用 2.5 的卡面，不另写映射');
      expect(tester.widget<CardQr>(find.byType(CardQr)).data, petDownloadUrl());
      expect(find.textContaining('Kamu ×'), findsNothing);
    });

    testWidgets('昵称取不到：名字行只显示宠物名', (tester) async {
      await pumpCard(
          tester,
          MatchShareCardData.from(
              petName: 'Momo', ownerName: null, petCode: 'ENTJ-H', ownerType: 'INFP', locale: const Locale('id')));
      expect(find.text('Momo'), findsOneWidget);
      expect(find.textContaining('×'), findsOneWidget, reason: '只剩代号行的 ×');
    });

    testWidgets('宠物名为空（档案未加载）：不拼出「 × 昵称」，只显示昵称', (tester) async {
      await pumpCard(
          tester,
          MatchShareCardData.from(
              petName: '', ownerName: 'Aurel', petCode: 'ENTJ-H', ownerType: 'INFP', locale: const Locale('id')));
      expect(find.text('Aurel'), findsOneWidget);
      expect(find.textContaining(' × Aurel'), findsNothing);
    });

    /// AC1.4：最长档名 + 两条最长差异句 + 20 字宠物名 / 30 字昵称 —— 不溢出（溢出在 widget test 里直接报错）。
    testWidgets('长文案不溢出', (tester) async {
      String longest(Iterable<String> xs) => xs.reduce((a, b) => a.length >= b.length ? a : b);
      final lines = kTsAxisDiffLines.values.map((t) => t.id).toList()..sort((a, b) => b.length.compareTo(a.length));
      await pumpCard(
        tester,
        MatchShareCardData(
          petName: 'M' * 20,
          ownerName: 'W' * 30,
          petCode: 'ENTJ-H',
          ownerType: 'ISFP',
          sameCount: 0,
          tier: 5,
          tierName: longest(kTsMatchTiers.values.map((t) => t.name.id)),
          lines: lines.take(2).toList(),
        ),
      );
      expect(tester.takeException(), isNull);
      expect(find.byKey(const ValueKey('matchShareCardLines')), findsOneWidget);
    });
  });

  group('AC2 / AC3 / AC4 配型页入口', () {
    late List<(String, Map<String, Object>?)> events;
    setUp(() {
      events = [];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
      // Story 4.4 起点卡先截 3:4 再进预览：截不到（null）时预览不出主操作 —— 正是 4.2 的「无主操作」形态。
      TailsonalityMatchPage.captureForTest = () async => null;
    });
    tearDown(() {
      Analytics.debugCaptureSink = null;
      TailsonalityMatchPage.captureForTest = null;
    });

    Future<void> pumpMatch(WidgetTester tester, {required bool unlocked}) async {
      tester.view.physicalSize = const Size(420, 2600);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(
        retry: (_, _) => null,
        overrides: [
        tailsonalityShareRewardRepositoryProvider.overrideWithValue(shareReward),
          authControllerProvider.overrideWith(() => _FakeAuth(const UserProfile(nickname: 'Aurel'))),
          petProfileProvider.overrideWith((ref) async => const PetProfile(id: 1, name: 'Momo', cardToken: 't')),
          tailsonalityOwnerTypeRepositoryProvider.overrideWithValue(_OwnerRepo('INFP')),
          tailsonalityResultProvider('abc').overrideWith((ref) async => TailsonalityResult(
                token: 'abc',
                typeCode: 'ENTJ-H',
                letters: 'ENTJ',
                energy: 'H',
                questionSet: 'DOG',
                resultIndex: 1,
                unlocked: unlocked,
                contentVersion: 1,
                createdAt: DateTime.utc(2026, 9, 30),
              )),
        ],
        child: MaterialApp(
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const TailsonalityMatchPage(token: 'abc'),
        ),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('tsMatchCardTap')));
      await tester.pumpAndSettle();
    }

    testWidgets('结果未解锁时打开：无水印、无尺寸切换、单个主按钮（Bagikan ke Story）', (tester) async {
      await pumpMatch(tester, unlocked: false);
      expect(find.byType(MatchShareCard), findsOneWidget);
      expect(find.text('Pratinjau Kartu'), findsOneWidget);
      expect(tester.widget<ShareCardPreviewPage>(find.byType(ShareCardPreviewPage)).watermarked, isFalse);
      expect(find.byType(CardWatermark), findsNothing);
      expect(find.byKey(const ValueKey('shareCardRatioToggle')), findsNothing);
      expect(find.byType(FilledButton), findsOneWidget);
      expect(tester.widget<FilledButton>(find.byType(FilledButton)).key, const ValueKey('shareCardShareCta'));
      expect(find.byType(OutlinedButton), findsNothing);
    });

    /// AC2.1：同数据同画布，结果是否解锁不影响配型卡导出像素。
    testWidgets('导出 PNG：未解锁与已解锁逐字节一致', (tester) async {
      Future<Uint8List> export(bool unlocked) async {
        // 换一棵全新的树：同一个 MaterialApp 会保留上一轮 push 的预览页。
        await tester.pumpWidget(const SizedBox());
        await pumpMatch(tester, unlocked: unlocked);
        final boundary = find.descendant(of: find.byType(CardFrame), matching: find.byType(RepaintBoundary)).first;
        final key = tester.widget<RepaintBoundary>(boundary).key! as GlobalKey;
        Uint8List? out;
        await tester.runAsync(() async {
          final png = await CardRenderPipeline.capture(boundaryKey: key, canvas: CardCanvas.story);
          final img = (await (await ui.instantiateImageCodec(png!)).getNextFrame()).image;
          out = (await img.toByteData(format: ui.ImageByteFormat.rawRgba))!.buffer.asUint8List();
          img.dispose();
        });
        return out!;
      }

      final locked = await export(false);
      final open = await export(true);
      expect(locked, open);
    });

    testWidgets('tailsonality_match_card_shared 只在分享成功回调时报；属性 owner_type / pet_type / match_level', (tester) async {
      ShareCardPreviewPage.captureForTest = (_) async => Uint8List.fromList(const [1, 2, 3]);
      addTearDown(() => ShareCardPreviewPage.captureForTest = null);
      await pumpMatch(tester, unlocked: false);
      await tester.tap(find.byKey(const ValueKey('shareCardShareCta')));
      await tester.pumpAndSettle();
      expect(events.where((e) => e.$1 == 'tailsonality_match_card_shared'), isEmpty);

      // Story 4.5：面板未回调成功（如取消）之前不上报领奖。
    expect(shareReward.calls, isEmpty);
    tester.widget<ShareCardPreviewPage>(find.byType(ShareCardPreviewPage)).onShared!('instagram');
    await tester.pump();
    expect(shareReward.calls, ['MATCH'], reason: '分享成功回调后上报一次，卡类型正确');
      expect(events.where((e) => e.$1 == 'tailsonality_match_card_shared').single.$2,
          {'owner_type': 'INFP', 'pet_type': 'ENTJ', 'match_level': 1});
    });
  });

  /// AC2.2：配型卡入口代码不读结果解锁字段。
  test('源码扫描：配型卡组件与入口不出现解锁相关标识符', () {
    final card = File('lib/features/tailsonality/presentation/share/match_share_card.dart').readAsStringSync();
    expect(card.toLowerCase(), isNot(contains('unlock')));
    final page = File('lib/features/tailsonality/presentation/tailsonality_match_page.dart').readAsStringSync();
    expect(page.toLowerCase(), isNot(contains('unlock')));
  });
}

class _FakeAuth extends AuthController {
  _FakeAuth(this.profile);

  final UserProfile? profile;

  @override
  AuthState build() => AuthState(status: AuthStatus.authenticated, role: 'USER', profile: profile);

  @override
  Future<void> ensureRestored() => Future<void>.value();
}

class _OwnerRepo implements TailsonalityOwnerTypeRepository {
  _OwnerRepo(this.value);

  final String? value;

  @override
  Future<String?> fetch() async => value;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
