import 'package:flutter/material.dart';
import 'dart:ui' show Tristate;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_owner_type_repository.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_providers.dart';
import 'package:tailtopia/features/tailsonality/domain/tailsonality_result.dart';
import 'package:tailtopia/features/tailsonality/presentation/tailsonality_match_page.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_match_card.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_type_selector.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_watermark.dart';

/// V1.3.2 Story 2.5 · L0：配型页（选择器 / 确认才提交 / 预选 / 结果视图 / 无水印 / 无分享 / 埋点）。
void main() {
  late _FakeOwnerRepo repo;
  late List<(String, Map<String, Object>?)> events;

  setUp(() {
    repo = _FakeOwnerRepo();
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Future<void> pump(WidgetTester tester, {String? owner}) async {
    repo.stored = owner;
    tester.view.physicalSize = const Size(420, 2600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ProviderScope(
      retry: (_, _) => null,
      overrides: [
        petProfileProvider.overrideWith((ref) async => const PetProfile(id: 1, name: 'Momo', cardToken: 't')),
        tailsonalityOwnerTypeRepositoryProvider.overrideWithValue(repo),
        tailsonalityResultProvider('abc').overrideWith((ref) async => TailsonalityResult(
              token: 'abc',
              typeCode: 'ENTJ-H',
              letters: 'ENTJ',
              energy: 'H',
              questionSet: 'DOG',
              resultIndex: 1,
              unlocked: false,
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
  }

  FilledButton confirm(WidgetTester tester) => tester.widget<FilledButton>(find.byKey(const ValueKey('tsOwnerTypeConfirm')));

  testWidgets('未设类型：直接显示选择器；网格 16 格恰为 16 个四字母、无别名 / 跳过', (tester) async {
    await pump(tester);
    expect(find.text('Tailsonality × Kamu'), findsOneWidget);
    expect(find.text('Tipe 4 huruf kamu?'), findsOneWidget);
    final codes = kTsTypeGrid.expand((r) => r).toList();
    expect(codes, hasLength(16));
    expect(codes.toSet(), hasLength(16));
    for (final c in codes) {
      final cell = find.byKey(ValueKey('tsTypeCell_$c'));
      expect(cell, findsOneWidget);
      final texts = tester.widgetList<Text>(find.descendant(of: cell, matching: find.byType(Text))).map((t) => t.data);
      expect(texts.join(), c, reason: '格内只显四字母');
    }
    expect(find.text('Lewati'), findsNothing);
    expect(find.textContaining('Nggak tahu'), findsNothing);
    final first = tester.getTopLeft(find.byKey(const ValueKey('tsTypeCell_ENTJ')));
    final last = tester.getTopLeft(find.byKey(const ValueKey('tsTypeCell_ISFP')));
    expect(first.dy < last.dy && first.dx < last.dx, isTrue, reason: '行序 ENTJ 左上 → ISFP 右下');
    expect(events.where((e) => e.$1 == 'tailsonality_match_entered').single.$2, {'pet_type': 'ENTJ'});
  });

  testWidgets('未选禁用 → 选中即可点；选中不跳、点确认才 PUT；成功切结果视图并报埋点', (tester) async {
    await pump(tester);
    expect(confirm(tester).onPressed, isNull);
    await tester.tap(find.byKey(const ValueKey('tsTypeCell_INFP')));
    await tester.pump();
    expect(confirm(tester).onPressed, isNotNull, reason: '同一帧由禁用转可点');
    expect(repo.saved, isEmpty, reason: '选中不提交');
    expect(find.byKey(const ValueKey('tsMatchResultView')), findsNothing);

    await tester.tap(find.byKey(const ValueKey('tsTypeCell_ESTJ')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('tsOwnerTypeConfirm')));
    await tester.pumpAndSettle();
    expect(repo.saved, ['ESTJ']);
    expect(find.byKey(const ValueKey('tsMatchResultView')), findsOneWidget);
    final set = events.where((e) => e.$1 == 'tailsonality_owner_type_set').single.$2;
    expect(set, {'owner_type': 'ESTJ', 'pet_type': 'ENTJ', 'match_level': 3});
  });

  testWidgets('保存失败：保持选择器与当前选中、提示', (tester) async {
    await pump(tester);
    repo.fail = true;
    await tester.tap(find.byKey(const ValueKey('tsTypeCell_ISFP')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('tsOwnerTypeConfirm')));
    await tester.pump();
    await tester.pump();
    expect(find.text('Tipe kamu belum kesimpan, coba lagi ya.'), findsOneWidget);
    expect(find.byKey(const ValueKey('tsTypeSelector')), findsOneWidget);
    expect(confirm(tester).onPressed, isNotNull);
    expect(events.where((e) => e.$1 == 'tailsonality_owner_type_set'), isEmpty);
    await tester.pumpAndSettle(const Duration(seconds: 5));
  });

  testWidgets('已设类型：结果视图顺序、无水印、无分享 / 发帖；逐轴四段；Ganti tipe 预选当前', (tester) async {
    await pump(tester, owner: 'INFP');
    expect(find.byKey(const ValueKey('tsMatchResultView')), findsOneWidget);
    expect(find.descendant(of: find.byType(TsMatchCard), matching: find.byType(CardWatermark)), findsNothing);
    expect(find.byKey(const ValueKey('tsMatchCardPlaceholder')), findsOneWidget, reason: '素材未入库 → 占位');
    expect(find.text('Counterweight'), findsOneWidget, reason: 'ENTJ vs INFP = 1/4');
    expect(find.text('Hampir kebalikan total — dan itu justru cocok.'), findsOneWidget);
    final card = tester.getTopLeft(find.byType(TsMatchCard)).dy;
    final compare = tester.getTopLeft(find.byKey(const ValueKey('tsLetterCompare'))).dy;
    final tier = tester.getTopLeft(find.byKey(const ValueKey('tsMatchTierName'))).dy;
    expect(card < compare && compare < tier, isTrue);
    for (final k in ['IE', 'NN', 'FT', 'PJ']) {
      expect(find.byKey(ValueKey('tsMatchAxisDetail_$k')), findsOneWidget, reason: k);
    }
    expect(find.text('E / I · Orientasi sosial'), findsOneWidget);
    expect(find.textContaining('{pet}'), findsNothing);
    expect(find.textContaining('Pamer'), findsNothing);
    expect(find.textContaining('Bagikan'), findsNothing);
    expect(find.byType(FilledButton), findsNothing, reason: '结果视图无吸底按钮');
    expect(find.textContaining('Rp'), findsNothing);
    // 对照：第 2 位 N 相同 → ✓，其余 ✗。
    expect(tester.widget<Icon>(find.byKey(const ValueKey('tsCompareMark_1'))).icon, Icons.check_rounded);
    expect(tester.widget<Icon>(find.byKey(const ValueKey('tsCompareMark_0'))).icon, Icons.close_rounded);

    await tester.tap(find.byKey(const ValueKey('tsMatchChangeType')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('tsTypeSelector')), findsOneWidget);
    expect(confirm(tester).onPressed, isNotNull, reason: '预选当前类型');
    final sel = tester.getSemantics(find.byKey(const ValueKey('tsTypeCell_INFP')));
    expect(sel.flagsCollection.isSelected, Tristate.isTrue);
  });
}

class _FakeOwnerRepo implements TailsonalityOwnerTypeRepository {
  String? stored;
  bool fail = false;
  final List<String> saved = [];

  @override
  Future<String?> fetch() async => stored;

  @override
  Future<String> save(String typeCode) async {
    if (fail) throw Exception('network');
    saved.add(typeCode);
    stored = typeCode;
    return typeCode;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
