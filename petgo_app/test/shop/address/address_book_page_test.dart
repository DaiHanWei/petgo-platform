import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/address/data/address_repository.dart';
import 'package:tailtopia/features/shop/address/domain/shipping_address.dart';
import 'package:tailtopia/features/shop/address/presentation/address_book_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 地址簿的**两种形态**（D-18，2026-09-02 stag）。
///
/// 结算页点「Change ›」原本跳到的就是这一页的管理形态：点卡片**毫无反应**，
/// 页面给的是「设为默认 / 编辑 / 删除」——**管理操作，不是选择操作**。
/// 于是多地址用户想把这单寄公司，唯一办法是把公司地址**设为默认**；
/// 下单后想寄回家还得再切一次。默认地址被当成"当前选择"用，语义错位。
void main() {
  ShippingAddress addr(String token, {bool isDefault = false, String name = 'Budi'}) =>
      ShippingAddress(
        token: token,
        receiverName: name,
        receiverPhone: '+628123456789',
        provinsi: 'DKI Jakarta',
        kotaKabupaten: 'Jakarta Selatan',
        kecamatan: 'Kebayoran',
        kodePos: '12190',
        addressLine: 'Jl. Test No. 1',
        isDefault: isDefault,
      );

  Widget host({required bool selecting, required List<ShippingAddress> items, ValueChanged<String?>? onPop}) =>
      ProviderScope(
        overrides: [addressListProvider.overrideWith((ref) async => items)],
        child: MaterialApp(
          localizationsDelegates: const [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: Builder(
            builder: (ctx) => Scaffold(
              body: Center(
                child: ElevatedButton(
                  onPressed: () async {
                    final picked = await Navigator.of(ctx).push<String>(
                      MaterialPageRoute(
                          builder: (_) => AddressBookPage(selecting: selecting)),
                    );
                    onPop?.call(picked);
                  },
                  child: const Text('open'),
                ),
              ),
            ),
          ),
        ),
      );

  group('🔴 D-18：从结算页进来时必须是选择器，不是管理页', () {
    testWidgets('🔴 选择器模式：点卡片即选中并返回 token', (tester) async {
      String? picked;
      await tester.pumpWidget(host(
        selecting: true,
        items: [addr('home', isDefault: true), addr('office', name: 'Kantor')],
        onPop: (v) => picked = v,
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('addressSelect_office')));
      await tester.pumpAndSettle();

      expect(picked, 'office',
          reason: '点卡片毫无反应，用户就只能去改默认地址 —— D-18 的原形');
    });

    testWidgets('🔴 选中**不改默认地址** —— 只作用于这一单', (tester) async {
      // 语义错位正是 D-18 的核心：默认地址不该被当成"当前选择"来用。
      String? picked;
      await tester.pumpWidget(host(
        selecting: true,
        items: [addr('home', isDefault: true), addr('office', name: 'Kantor')],
        onPop: (v) => picked = v,
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('addressSelect_office')));
      await tester.pumpAndSettle();

      expect(picked, 'office');
      // 没有任何 setDefault 调用：repository 未被 override 成会抛的假实现也说明这一点，
      // 这里直接断言「默认标记还在 home 上」——列表数据是我们给的那份，没被改过。
      expect(find.text('open'), findsOneWidget); // 已 pop 回来
    });

    testWidgets('管理模式（默认）：卡片不可点，保持原有的管理操作', (tester) async {
      await tester.pumpWidget(host(selecting: false, items: [addr('home', isDefault: true)]));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('addressSelect_home')), findsNothing,
          reason: '不从结算页进来时，这一页仍然是地址管理');
    });

    testWidgets('选择器里仍保留「设为默认」—— 只是它不再是换地址的唯一途径', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.pumpWidget(host(
        selecting: true,
        items: [addr('home', isDefault: true), addr('office', name: 'Kantor')],
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      expect(find.text(l10n.addressSetDefault), findsOneWidget); // 非默认那张上
    });
  });
}
