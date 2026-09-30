import 'dart:ui' show ImageFilter;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_owner_type_repository.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_providers.dart';
import 'package:tailtopia/features/tailsonality/domain/tailsonality_result.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_result_page.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_generating_view.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_locked_analysis.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_match_teaser.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_result_card.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_rich_text.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_watermark.dart';

/// V1.3.2 Story 2.4（结果页免费态与重测）+ Story 2.3（生成中动效）· L0。
void main() {
  TailsonalityResult result({String letters = 'ISFP', String energy = 'L', bool unlocked = false}) =>
      TailsonalityResult(
        token: 'abc',
        typeCode: '$letters-$energy',
        letters: letters,
        energy: energy,
        questionSet: 'CAT',
        resultIndex: 1,
        unlocked: unlocked,
        contentVersion: 1,
        createdAt: DateTime.utc(2026, 9, 30),
      );

  Widget wrap(Widget child,
          {List overrides = const [],
          bool reduceMotion = false,
          bool scaffold = true,
          bool petFails = false,
          String? ownerType}) =>
      ProviderScope(
        retry: (_, _) => null,
        overrides: [
          petProfileProvider.overrideWith((ref) async {
            if (petFails) throw Exception('profile');
            return const PetProfile(id: 1, name: 'Momo', cardToken: 't', petType: 'CAT', breed: 'Anggora');
          }),
          tailsonalityOwnerTypeRepositoryProvider.overrideWithValue(_OwnerRepo(ownerType)),
          ...overrides,
        ],
        child: MaterialApp(
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: MediaQuery(
            data: MediaQueryData(disableAnimations: reduceMotion, size: const Size(420, 2400)),
            child: scaffold ? Scaffold(body: child) : child,
          ),
        ),
      );

  group('tsRichText（AC3）', () {
    String render(List<InlineSpan> spans) =>
        spans.map((s) => (s as TextSpan).style?.fontWeight == FontWeight.w700 ? '[${s.text}]' : s.text).join();

    test('无标记原样；成对加粗；不成对的 ** 原样不吞字', () {
      expect(render(tsRichSpans('halo')), 'halo');
      expect(render(tsRichSpans('a **b** c **d**')), 'a [b] c [d]');
      expect(render(tsRichSpans('a **b** c **d')), 'a [b] c **d');
      expect(render(tsRichSpans('**')), '**');
      expect(tsPlainText('x **y** z'), 'x y z');
    });
  });

  group('结果卡（AC2）', () {
    testWidgets('未解锁带水印；缺图回落占位；Semantics 含完整代号 · 名 · slogan', (tester) async {
      await tester.pumpWidget(wrap(SizedBox(width: 300, child: TsResultCard(result: result(), watermarked: true))));
      await tester.pumpAndSettle();
      expect(find.byType(CardWatermark), findsOneWidget);
      expect(find.byKey(const ValueKey('tsResultCardPlaceholder')), findsOneWidget);
      expect(find.bySemanticsLabel('ISFP-L · Sus Radar 24/7 · Sus. Everything sus.'), findsOneWidget);
      expect(tester.getSize(find.byType(TsResultCard)).aspectRatio, closeTo(3 / 4, 0.01));
    });

    testWidgets('showTextOverlay：true 叠三行文字，false（缺省 = 占位图已烤字）不叠', (tester) async {
      await tester.pumpWidget(wrap(SizedBox(width: 300, child: TsResultCard(result: result(), watermarked: false, showTextOverlay: true))));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('tsResultCardText')), findsOneWidget);
      expect(find.text('I S F P - L'), findsOneWidget);
      expect(find.byType(CardWatermark), findsNothing);

      await tester.pumpWidget(wrap(SizedBox(width: 300, child: TsResultCard(result: result(), watermarked: false))));
      await tester.pumpAndSettle();
      expect(kTsRoleArtHasBakedText, isTrue);
      expect(find.byKey(const ValueKey('tsResultCardText')), findsNothing);
    });
  });

  group('配型引流（AC4）', () {
    testWidgets('未配型：四字母 + ? 虚线框；onTap null 时无 InkWell', (tester) async {
      await tester.pumpWidget(wrap(const TsMatchTeaser(petLetters: 'ENTJ')));
      expect(find.text('ENTJ'), findsOneWidget);
      expect(find.text('ENTJ-H'), findsNothing);
      expect(find.byKey(const ValueKey('tsMatchTeaserUnknown')), findsOneWidget);
      expect(find.text('Seberapa mirip kalian?'), findsOneWidget);
      expect(find.descendant(of: find.byType(TsMatchTeaser), matching: find.byType(InkWell)), findsNothing);
    });
  });

  group('锁态区（AC5）', () {
    testWidgets('三段标题可见、正文在模糊 + ExcludeSemantics 下；无购买按钮、无 Rp', (tester) async {
      await tester.pumpWidget(wrap(const SingleChildScrollView(
          child: TsLockedAnalysis(typeCode: 'ENTJ-H', letters: 'ENTJ', energy: 'H', petName: 'Momo'))));
      expect(find.text('Analisis lengkap'), findsOneWidget);
      expect(find.text('Khusus buat ENTJ-H'), findsOneWidget);
      expect(find.text('Empat dimensi'), findsOneWidget);
      expect(find.text('Level energi · Energi tinggi'), findsOneWidget);
      for (final k in ['deepRead', 'dimensions', 'energy']) {
        final body = find.byKey(ValueKey('tsLockedBody_$k'));
        expect(find.descendant(of: body, matching: find.byType(ImageFiltered)), findsOneWidget, reason: k);
        expect(find.descendant(of: body, matching: find.byType(ExcludeSemantics)), findsOneWidget, reason: k);
        final f = tester.widget<ImageFiltered>(find.descendant(of: body, matching: find.byType(ImageFiltered)));
        expect(f.imageFilter, ImageFilter.blur(sigmaX: 6, sigmaY: 6));
      }
      expect(find.byKey(const ValueKey('tsUnlockCta')), findsNothing);
      expect(find.textContaining('Rp'), findsNothing);
    });
  });

  group('结果页（AC1 / AC6）', () {
    late List<(String, Map<String, Object>?)> events;
    setUp(() {
      events = [];
      Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    });
    tearDown(() => Analytics.debugCaptureSink = null);

    Future<void> pumpPage(WidgetTester tester,
        {Object? error, TailsonalityResult? r, TailsonalityResult? initial, bool petFails = false, String? ownerType}) async {
      tester.view.physicalSize = const Size(420, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(wrap(
        TailsonalityResultPage(token: 'abc', initial: initial),
        scaffold: false,
        petFails: petFails,
        ownerType: ownerType,
        overrides: [
          tailsonalityResultProvider('abc').overrideWith((ref) async {
            if (error != null) throw error;
            return r ?? result();
          }),
        ],
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('顺序：卡 → 摘要（{pet} 已替换）→ 引流 → 锁态区；底部无主 CTA、无次数文案', (tester) async {
      await pumpPage(tester);
      final card = tester.getTopLeft(find.byType(TsResultCard)).dy;
      final summary = tester.getTopLeft(find.byKey(const ValueKey('tsResultSummary'))).dy;
      final teaser = tester.getTopLeft(find.byType(TsMatchTeaser)).dy;
      final locked = tester.getTopLeft(find.byType(TsLockedAnalysis)).dy;
      expect(card < summary && summary < teaser && teaser < locked, isTrue);
      expect(find.textContaining('Sistem keamanan rumah'), findsOneWidget);
      expect(find.textContaining('{pet}'), findsNothing);
      expect(find.byType(FilledButton), findsNothing);
      // 重测免费、无次数提示：不得出现「N kali / sisa / gratis / kuota」或价格。
      for (final w in [RegExp(r'\d+\s*kali\b'), RegExp(r'\bsisa\b'), RegExp(r'\bgratis\b'), RegExp(r'\bkuota\b'), 'Rp']) {
        expect(find.textContaining(w), findsNothing, reason: '$w');
      }
      expect(find.byTooltip('Opsi lainnya'), findsOneWidget);
      expect(tester.getSize(find.byKey(const ValueKey('tsResultMore'))).height, greaterThanOrEqualTo(44));
    });

    testWidgets('404 → 空态（不给重试）', (tester) async {
      final notFound = DioException(
        requestOptions: RequestOptions(path: '/x'),
        response: Response(requestOptions: RequestOptions(path: '/x'), statusCode: 404),
      );
      await pumpPage(tester, error: notFound);
      expect(find.text('Hasil tes ini nggak ketemu'), findsOneWidget);
      expect(find.byKey(const ValueKey('tsResultRetry')), findsNothing);
    });

    testWidgets('其它失败 → 通用重试', (tester) async {
      await pumpPage(tester, error: Exception('boom'));
      expect(find.byKey(const ValueKey('tsResultRetry')), findsOneWidget);
    });

    testWidgets('带 extra 首帧渲染；后续取数失败仍显示它，不被重试页顶掉', (tester) async {
      await pumpPage(tester, error: Exception('blip'), initial: result(letters: 'ENTJ', energy: 'H'));
      expect(find.byType(TsResultCard), findsOneWidget);
      expect(find.byKey(const ValueKey('tsResultRetry')), findsNothing);
    });

    testWidgets('重测确认但档案取不到 → 提示、不开抽屉、不报埋点', (tester) async {
      await pumpPage(tester, petFails: true);
      await tester.tap(find.byKey(const ValueKey('tsResultMore')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('tsMenuRetake')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('confirmSheetConfirm')));
      await tester.pump();
      expect(find.byKey(const ValueKey('tsIntroSheet')), findsNothing);
      expect(events.where((e) => e.$1 == 'tailsonality_retake_confirmed'), isEmpty);
      await tester.pumpAndSettle(const Duration(seconds: 5));
    });

    testWidgets('Story 2.5 · AC7：主人类型已设 → 引流模块显示对照 + 档位标签，可点', (tester) async {
      await pumpPage(tester, r: result(letters: 'ENTJ', energy: 'H'), ownerType: 'ESTJ');
      expect(find.byKey(const ValueKey('tsMatchTeaserOwner')), findsOneWidget);
      expect(find.text('ESTJ'), findsOneWidget);
      expect(find.text('Twin Flames'), findsOneWidget);
      expect(find.byKey(const ValueKey('tsMatchTeaserUnknown')), findsNothing);
      expect(find.descendant(of: find.byType(TsMatchTeaser), matching: find.byType(InkWell)), findsOneWidget);
    });

    testWidgets('已解锁分支不崩、不渲染锁态区', (tester) async {
      await pumpPage(tester, r: result(unlocked: true));
      expect(find.byType(TsLockedAnalysis), findsNothing);
      expect(find.byType(CardWatermark), findsNothing);
    });

    testWidgets('⋯ 菜单仅一项；重测确认后发埋点并弹说明抽屉；取消无埋点', (tester) async {
      await pumpPage(tester);
      await tester.tap(find.byKey(const ValueKey('tsResultMore')));
      await tester.pumpAndSettle();
      expect(find.byType(ListTile), findsOneWidget);
      expect(find.byKey(const ValueKey('tsMenuRetake')), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('tsMenuRetake')));
      await tester.pumpAndSettle();
      expect(find.text('Tes ulang?'), findsOneWidget);
      expect(find.textContaining('hasil barunya perlu di-unlock lagi'), findsOneWidget);
      expect(find.textContaining('**'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('confirmSheetCancel')));
      await tester.pumpAndSettle();
      expect(events.where((e) => e.$1 == 'tailsonality_retake_confirmed'), isEmpty);
      expect(find.byKey(const ValueKey('tsIntroSheet')), findsNothing);

      await tester.tap(find.byKey(const ValueKey('tsResultMore')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('tsMenuRetake')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('confirmSheetConfirm')));
      await tester.pumpAndSettle();
      final e = events.where((e) => e.$1 == 'tailsonality_retake_confirmed').single;
      expect(e.$2, isNull);
      expect(find.byKey(const ValueKey('tsIntroSheet')), findsOneWidget);
      expect(find.text('Tes kepribadian Momo · Anggora'), findsOneWidget);
    });
  });

  group('生成中（Story 2.3 · AC5.2）', () {
    testWidgets('减少动态效果 → 首帧即五条全亮', (tester) async {
      await tester.pumpWidget(wrap(const TsGeneratingView(petName: 'Momo'), reduceMotion: true));
      await tester.pump();
      for (var i = 0; i < 5; i++) {
        final s = tester.getSemantics(find.byKey(ValueKey('tsGeneratingAxis_$i')));
        expect(s.value, '100%', reason: 'axis $i');
      }
      expect(find.text('E / I · Orientasi sosial'), findsOneWidget);
      expect(find.text('Tingkat energi'), findsOneWidget);
    });

    testWidgets('正常动效逐条点亮（约 450ms 一条）', (tester) async {
      await tester.pumpWidget(wrap(const TsGeneratingView(petName: 'Momo')));
      await tester.pump();
      String v(int i) => tester.getSemantics(find.byKey(ValueKey('tsGeneratingAxis_$i'))).value;
      expect(v(0), '0%');
      await tester.pump(const Duration(milliseconds: 500));
      expect(v(0), '100%');
      expect(v(1), '0%');
      await tester.pumpAndSettle();
      expect(v(4), '100%');
    });
  });
}

class _OwnerRepo implements TailsonalityOwnerTypeRepository {
  _OwnerRepo(this.value);

  final String? value;

  @override
  Future<String?> fetch() async => value;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
