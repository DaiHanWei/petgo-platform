import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/id_card_repository.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 假 repo：listCards 返当前态；其余方法 stub（Story 6-7 列表页只用 listCards）。
class _FakeIdCardRepo implements IdCardRepository {
  _FakeIdCardRepo(this._cards);
  final List<IdCard> _cards;

  @override
  Future<List<IdCard>> listCards() async => _cards;

  @override
  Future<IdCard> getCard(int cardId) async => _cards.firstWhere((c) => c.id == cardId);

  @override
  Future<IdCard> createCard(CreateIdCardRequest req) async =>
      IdCard(id: 999, serialId: 999, name: req.name);

  @override
  Future<HdPurchaseResult> purchaseHdForCard(int cardId, HdPayChannel channel) async =>
      const HdPurchaseResult(unlocked: true);

  // 旧单卡端点（列表页不用，stub 兼容接口）。
  @override
  Future<IdCardData?> getMyIdCard() async => null;
  @override
  Future<IdCardData> generate() async => const IdCardData(generated: true);
  @override
  Future<HdPurchaseResult> purchaseHd(HdPayChannel channel) async =>
      const HdPurchaseResult(unlocked: true);
}

Future<void> _pump(WidgetTester tester, List<IdCard> cards) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [idCardRepositoryProvider.overrideWithValue(_FakeIdCardRepo(cards))],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale('en'),
      home: IdCardPage(),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('空列表 → 空态标题 + 建卡入口', (tester) async {
    await _pump(tester, const []);
    expect(find.text('No ID cards yet'), findsOneWidget);
    expect(find.byKey(const ValueKey('idCardCreateEntry')), findsOneWidget);
  });

  testWidgets('有卡 → 渲染卡片(名字/编号) + 建卡入口 + 已解锁徽章', (tester) async {
    await _pump(tester, [
      IdCard(id: 1, serialId: 12, name: 'Mochi', hdUnlocked: true, createdAt: DateTime.utc(2026, 7, 22)),
      IdCard(id: 2, serialId: 7, name: 'Coco', createdAt: DateTime.utc(2026, 7, 20)),
    ]);
    expect(find.text('Mochi'), findsOneWidget);
    expect(find.text('Coco'), findsOneWidget);
    expect(find.text('No. 0012'), findsOneWidget); // 编号补零展示
    expect(find.byKey(const ValueKey('idCardTile_1')), findsOneWidget);
    expect(find.byKey(const ValueKey('idCardTile_2')), findsOneWidget);
    // 建卡入口（列表态用 InkWell 版，同 key）。
    expect(find.byKey(const ValueKey('idCardCreateEntry')), findsOneWidget);
  });
}
