import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/id_card_repository.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/id_card_watermark.dart';
import 'package:tailtopia/features/profile/presentation/id_card/ktp_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card_detail_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 假 repo：详情页只用 getCard。
class _FakeIdCardRepo implements IdCardRepository {
  _FakeIdCardRepo(this._card);
  final IdCard _card;

  @override
  Future<IdCard> getCard(int cardId) async => _card;

  @override
  Future<List<IdCard>> listCards() async => [_card];

  @override
  Future<IdCard> createCard(CreateIdCardRequest req) async => _card;

  @override
  Future<HdPurchaseResult> purchaseHdForCard(int cardId, HdPayChannel channel) async =>
      const HdPurchaseResult(unlocked: true);

  @override
  Future<int> hdPrice() async => 5000; // 同步返回不碰 dio（避免 timer 残留）

  @override
  Future<IdCardData?> getMyIdCard() async => null;
  @override
  Future<IdCardData> generate() async => const IdCardData(generated: true);
  @override
  Future<HdPurchaseResult> purchaseHd(HdPayChannel channel) async =>
      const HdPurchaseResult(unlocked: true);
}

Future<void> _pump(WidgetTester tester, IdCard card) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [idCardRepositoryProvider.overrideWithValue(_FakeIdCardRepo(card))],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: Locale('en'),
      home: IdCardDetailPage(cardId: 1),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  // bug 20260728-383：预览恒盖水印（含已解锁 HD 的卡），防截图裁卡。
  testWidgets('详情页卡面盖防截图水印（已解锁 HD 也不消失）', (tester) async {
    await _pump(tester, IdCard(id: 1, serialId: 12, name: 'Mochi', hdUnlocked: true));
    expect(find.byType(IdCardWatermark), findsOneWidget);
  });

  testWidgets('未解锁同样盖水印；切护照/学生卡样式水印仍在', (tester) async {
    await _pump(tester, IdCard(id: 1, serialId: 12, name: 'Mochi', hdUnlocked: false));
    for (final tab in [1, 2, 0]) {
      await tester.tap(find.byKey(ValueKey('idCardStyleTab_$tab')));
      await tester.pumpAndSettle();
      expect(find.byType(IdCardWatermark), findsOneWidget, reason: 'styleTab=$tab');
    }
  });

  // 契约：水印绝不能进导出边界（GlobalKey 标记的 RepaintBoundary 子树），
  // 否则付费 HD 导出/分享的 PNG 会带水印——预览有、导出无是本需求的核心不变量。
  testWidgets('水印在 HD 导出 RepaintBoundary 子树之外', (tester) async {
    await _pump(tester, IdCard(id: 1, serialId: 12, name: 'Mochi', hdUnlocked: true));
    // 导出边界 = 卡面最近的 RepaintBoundary 祖先（页面用 GlobalKey 标记，_exportHd 用它 toImage）。
    RepaintBoundary? exportBoundary;
    tester.element(find.byType(KtpCardFront)).visitAncestorElements((el) {
      if (el.widget is RepaintBoundary) {
        exportBoundary = el.widget as RepaintBoundary;
        return false;
      }
      return true;
    });
    expect(exportBoundary, isNotNull);
    expect(exportBoundary!.key, isA<GlobalKey>()); // 确认找到的是页面导出边界，非框架边界
    expect(
      find.descendant(of: find.byWidget(exportBoundary!), matching: find.byType(IdCardWatermark)),
      findsNothing,
    );
  });
}
