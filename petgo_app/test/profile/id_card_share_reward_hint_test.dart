import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/id_card_repository.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card_detail_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 假 repo：详情页只用 getCard + 分享奖励展示口径。
class _FakeIdCardRepo implements IdCardRepository {
  @override
  Future<int> reportShareForReward(int cardId) async => 0; // Story 18.2：本类不验奖励
  @override
  Future<int> advertisableShareReward() async => advertise;
  _FakeIdCardRepo(this._card, {this.advertise = 0});
  final IdCard _card;
  final int advertise;

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

Future<void> _pump(WidgetTester tester, IdCard card, {int advertise = 0}) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      idCardRepositoryProvider.overrideWithValue(_FakeIdCardRepo(card, advertise: advertise)),
    ],
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
  /// 🔴 **奖励没配好，卡面页一个字都不提**（产品 2026-08-27）。
  ///
  /// 分享奖励三个数（总开关 / 每次发放 / 日上限）**默认全是 0** —— 功能随版本上线，
  /// 但默认一分不发，等运营在后台配上才开始发。所以「分享可得 PawCoin」这句话不能常驻：
  /// 没配就显示，正是 Story 18.2 AC6 反复要避免的 **「承诺了奖励却不发」**。
  ///
  /// ⚠️ 判据**由服务端给**（返回可承诺的枚数，0 = 不提）。客户端不复刻那套判定 ——
  /// 它涉及总开关、每次发放数、日上限、以及「这只宠物领过没」，复刻必然漂移，
  /// 而漂移的后果就是空承诺。
  group('bug 20260827 · 分享奖励文案按配置显示', () {
    testWidgets('服务端说 0（出厂状态）→ 卡面页不出现奖励文案', (tester) async {
      await _pump(tester, IdCard(id: 1, serialId: 12, name: 'Mochi'), advertise: 0);
      expect(find.byKey(const ValueKey('idCardShareRewardHint')), findsNothing,
          reason: '🔴 没配奖励却承诺「分享可得 PawCoin」= 承诺了却不发');
      expect(find.textContaining('PawCoin'), findsNothing,
          reason: '整屏都不该出现 PawCoin 字样');
    });

    testWidgets('服务端给出枚数 → 出现提示，且写的是「首次」不是「每次」', (tester) async {
      await _pump(tester, IdCard(id: 1, serialId: 12, name: 'Mochi'), advertise: 20);
      expect(find.byKey(const ValueKey('idCardShareRewardHint')), findsOneWidget);
      expect(find.textContaining('20 PawCoin'), findsOneWidget);
      // 🔴 这个奖励是**一只宠物档案只发一次**。写成「每次」在第二次分享时就是假话，
      //    而用户不会去读规则，只会觉得被骗了一次。
      expect(find.textContaining('First share'), findsOneWidget,
          reason: '文案必须是「首次分享」口径');
    });

    /// 🛡 Story 18.2 AC6 的原有约束**不因这次新增而松动**：
    /// 按钮文案永远固定「分享」，奖励信息只在独立的提示行里。
    /// 这样运营关掉总开关时，只是那一行消失，按钮一个字都不用改。
    testWidgets('即便配了奖励，分享按钮本身仍不含奖励信息（AC6 不松动）', (tester) async {
      await _pump(tester, IdCard(id: 1, serialId: 12, name: 'Mochi'), advertise: 20);
      final btn = find.byKey(const ValueKey('idCardFreeShare'));
      expect(btn, findsOneWidget);
      expect(
          find.descendant(of: btn, matching: find.textContaining('PawCoin')), findsNothing,
          reason: '🔴 奖励信息爬进按钮 ⇒ 关掉开关时按钮就在空承诺');
    });
  });
}
