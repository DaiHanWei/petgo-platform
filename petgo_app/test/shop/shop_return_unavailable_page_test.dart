import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/presentation/shop_return_unavailable_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 2-1（SD-5 / SHOP-FR-05）：App 侧隐藏退货入口与文案。
///
/// 🔴 两条退货路由的 **path 逐字保留**，builder 换成本页 —— 删路由会让老深链落到
/// go_router 的 `errorBuilder`（＝「链接坏了」），而我们要告诉用户的是
/// 「这条路现在走不通，找谁能解决」。
void main() {
  Widget host() => ProviderScope(
        child: MaterialApp(
          localizationsDelegates: const [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: const ShopReturnUnavailablePage(),
        ),
      );

  testWidgets('说明文案在、客服按钮在', (tester) async {
    await tester.pumpWidget(host());
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('shopReturnUnavailableV2')), findsOneWidget);
    expect(find.byKey(const ValueKey('shopReturnUnavailableBodyV2')), findsOneWidget);
    expect(find.byKey(const ValueKey('shopReturnUnavailableCsV2')), findsOneWidget);
  });

  testWidgets('🔴 本页不发任何网络请求 → 没有 loading 转圈、没有错误重试态', (tester) async {
    await tester.pumpWidget(host());
    await tester.pump(); // 刻意只 pump 一帧：真有 provider 在 load 的话这里就有转圈

    expect(find.byType(CircularProgressIndicator), findsNothing,
        reason: '一个必然给不出结果的请求只会让人多等几秒');
    await tester.pumpAndSettle();
    expect(find.byType(CircularProgressIndicator), findsNothing);
  });

  testWidgets('点客服按钮弹出共享客服抽屉', (tester) async {
    await tester.pumpWidget(host());
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('shopReturnUnavailableCsV2')));
    await tester.pumpAndSettle();

    expect(find.byType(BottomSheet), findsOneWidget);
  });

  // ---------------------------------------------------------------- 文案守门

  /// 🔴 防「下次有人把老文案抄回来」：两条仍在渲染的文案里不得再出现退货措辞。
  group('SD-5 文案守门（直接读 ARB）', () {
    Map<String, dynamic> loadArb(String path) =>
        jsonDecode(File(path).readAsStringSync()) as Map<String, dynamic>;

    final en = loadArb('lib/l10n/app_en.arb');
    final id = loadArb('lib/l10n/app_id.arb');

    // 「2x24 小时可退」与「7 天内仍可申请退货」这两句是 SHOP-FR-05 点名要消掉的。
    const banned = ['2x24', '2×24', 'return', 'retur', 'pengembalian'];

    // ⚠️ **本守门只覆盖下面这两个 key，不是「全仓无退货措辞」的保证。**
    //    商详页与结算页的 `tokoReturnable*` / `tokoNoReturnAfterOpen*` / `tokoNoReturn*`
    //    仍在渲染，且**刻意保留** —— 它们是商品「开封不退」属性的合规明示（电商一期 FR-104
    //    的三处明示），不是退货入口、也不是退货窗口时长文案。SHOP-FR-05 点名的范围是
    //    「订单详情 / 订单列表 / 帮助区」。要连那三处一并隐藏属范围变更，回决策日志确认。

    for (final key in ['shopOrderHelpBody', 'shopOrderConfirmReceiptBody']) {
      test('$key 不含任何退货措辞（en / id）', () {
        for (final arb in [en, id]) {
          final v = (arb[key] as String).toLowerCase();
          for (final w in banned) {
            expect(v.contains(w), isFalse,
                reason: '$key = "${arb[key]}" 仍含退货措辞「$w」—— '
                    '入口已隐藏，文案还在承诺退货就是在骗人');
          }
        }
      });
    }

    test('🔴 六个退货 key 一个都没删（下一版恢复要用，删了要连印尼语重翻）', () {
      for (final k in [
        'shopOrderRequestReturn',
        'shopOrderReturnInProgress',
        'shopOrderReturnWindow',
        'shopOrderAutoCompletedHint',
        'shopOrderHelpBody',
        'shopOrderConfirmReceiptBody',
      ]) {
        expect(en.containsKey(k), isTrue, reason: 'en 少了 $k');
      }
      // 值也没动的那四个：仍是退货措辞本身，证明只是停了渲染而不是改了值。
      expect((en['shopOrderRequestReturn'] as String).toLowerCase(), contains('return'));
      expect((id['shopOrderRequestReturn'] as String).toLowerCase(), contains('retur'));
    });
  });
}
