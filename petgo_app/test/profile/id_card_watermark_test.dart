import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/id_card_repository.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';
import 'package:tailtopia/features/profile/domain/id_card_style.dart';
import 'package:tailtopia/features/profile/presentation/id_card/id_card_watermark.dart';
import 'package:tailtopia/features/profile/presentation/id_card/kitas_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/ktp_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/sim_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/student_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card/student_variant_card.dart';
import 'package:tailtopia/features/profile/presentation/id_card_detail_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 假 repo：详情页只用 getCard。
class _FakeIdCardRepo implements IdCardRepository {
  @override
  Future<int> reportShareForReward(int cardId) async => 0; // Story 18.2：本类不验奖励
  @override
  Future<int> advertisableShareReward() async => 0; // 出厂三个 0：默认不承诺奖励
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
  // 每次换新 ProviderScope：否则同一测试内连续 pump 不同卡时详情 provider 仍缓存上一张卡。
  await tester.pumpWidget(ProviderScope(
    key: UniqueKey(),
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
  /// 🔴 水印浓度是 **HD 付费点唯一的护栏**（bug 20260826）。
  ///
  /// 免费分享出去的是**带水印那一版**（Story 18.2 AC2 截的是含水印的 boundary），
  /// 无水印仍需付费。实机反馈：0.25 太淡，「分享出去的看着和高清也没区别」——
  /// 分不出来，就没人有理由付费。
  ///
  /// ⚠️ 这个数很容易被人以「太丑了」为由调回去，而调低之后**不会有任何测试变红**、
  /// 也不会有报错，只有收入慢慢消失。故按名钉住下限。
  /// 上限同样要钉：堆到接近不透明会让卡面本身看不清，等于把免费分享做废。
  test('水印浓度不得调回「几乎看不见」', () {
    expect(IdCardWatermark.opacity, greaterThanOrEqualTo(0.4),
        reason: '🔴 淡到与 HD 无异 ⇒ 付费点失效');
    expect(IdCardWatermark.opacity, lessThanOrEqualTo(0.6),
        reason: '盖太狠会让卡面本身看不清，免费分享就没人愿意发了');
  });

  testWidgets('详情页卡面盖防截图水印（已解锁 HD 也不消失）', (tester) async {
    await _pump(tester, IdCard(id: 1, serialId: 12, name: 'Mochi', hdUnlocked: true));
    expect(find.byType(IdCardWatermark), findsOneWidget);
  });

  testWidgets('未解锁同样盖水印；8 款卡（含 SIM / 居住证占位与四款学生卡）每款恰好一个水印',
      (tester) async {
    // V1.3.4 ktp Story 1.2：按 IdCard(cardType, cardVariant) 逐款 pump 详情页。
    for (final style in IdCardStyle.values) {
      await _pump(
          tester,
          IdCard(
              id: 1,
              serialId: 12,
              name: 'Mochi',
              hdUnlocked: false,
              cardType: style.cardType,
              cardVariant: style.cardVariant));
      await tester.pumpAndSettle();
      expect(find.byType(IdCardWatermark), findsOneWidget, reason: '$style');
      expect(tester.takeException(), isNull, reason: '$style');
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

  /// 卡面最近的 RepaintBoundary 祖先 = 页面导出边界（GlobalKey 标记）；水印不得在其子树内。
  void expectWatermarkOutsideExportBoundary(WidgetTester tester, Finder cardFront) {
    RepaintBoundary? exportBoundary;
    tester.element(cardFront).visitAncestorElements((el) {
      if (el.widget is RepaintBoundary) {
        exportBoundary = el.widget as RepaintBoundary;
        return false;
      }
      return true;
    });
    expect(exportBoundary, isNotNull);
    expect(exportBoundary!.key, isA<GlobalKey>());
    expect(
      find.descendant(of: find.byWidget(exportBoundary!), matching: find.byType(IdCardWatermark)),
      findsNothing,
    );
  }

  testWidgets('四款学生卡：水印同样在 HD 导出 RepaintBoundary 子树之外', (tester) async {
    for (final style in [
      IdCardStyle.studentBintang,
      IdCardStyle.studentMalam,
      IdCardStyle.studentCeria,
      IdCardStyle.studentKlasik,
    ]) {
      await _pump(
          tester,
          IdCard(
              id: 1,
              serialId: 12,
              name: 'Mochi',
              hdUnlocked: true,
              cardType: 'STUDENT',
              cardVariant: style.cardVariant,
              cardNo: 'TT230326020110'));
      final front = style == IdCardStyle.studentKlasik
          ? find.byType(StudentCardFront)
          : find.byType(StudentVariantCardFront);
      expect(front, findsOneWidget, reason: '$style');
      expectWatermarkOutsideExportBoundary(tester, front);
    }
  });

  testWidgets('SIM / 居住证：水印同样在 HD 导出 RepaintBoundary 子树之外', (tester) async {
    for (final (type, front) in [('SIM', find.byType(SimCardFront)), ('KITAS', find.byType(KitasCardFront))]) {
      await _pump(tester,
          IdCard(id: 1, serialId: 12, name: 'Mochi', hdUnlocked: true, cardType: type, cardNo: 'TT730326020110'));
      expect(find.byType(IdCardWatermark), findsOneWidget, reason: type);
      expect(front, findsOneWidget, reason: type);
      expectWatermarkOutsideExportBoundary(tester, front);
    }
  });
}
