import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/shop/address/data/address_repository.dart';
import 'package:tailtopia/features/shop/address/domain/shipping_address.dart';
import 'package:tailtopia/features/shop/data/shipping_estimate_repository.dart';
import 'package:tailtopia/features/shop/presentation/widgets/delivery_area_sheet.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'pdp_fakes.dart';

/// B6 送达地区抽屉（v1.4.2 shop Story 8.2 AC2.3）。
///
/// 选中写本地 + 埋点 `serviceable` 由详情页做（见 `product_detail_page_v3_test` 的「本页 B6 选的」用例）；
/// 本文件看抽屉自身：三级选择、面包屑回退、未开通仍列出且可选、当前所选打勾；另测本地存取的往返。
void main() {
  Future<List<ShopDeliveryArea?>> open(WidgetTester t, {int? selected, RegionTree tree = fakeRegionTree}) async {
    final results = <ShopDeliveryArea?>[];
    await t.pumpWidget(ProviderScope(
      overrides: [regionTreeProvider.overrideWith((ref) async => tree)],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        home: Builder(
          builder: (c) => Scaffold(
            body: TextButton(
              key: const ValueKey('openSheet'),
              onPressed: () async =>
                  results.add(await showDeliveryAreaSheet(c, selectedKecamatanId: selected)),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ));
    await t.tap(find.byKey(const ValueKey('openSheet')));
    await t.pumpAndSettle();
    return results;
  }

  testWidgets('标题 + 省 → 市 → 区三级；选中区即关并返回（含 id 与可配送）', (t) async {
    final results = await open(t);
    expect(find.text('Pilih wilayah pengiriman'), findsOneWidget);
    expect(find.byKey(const ValueKey('deliveryAreaProv_DKI Jakarta')), findsOneWidget);
    expect(find.byKey(const ValueKey('deliveryAreaProv_Jawa Barat')), findsOneWidget);

    await t.tap(find.byKey(const ValueKey('deliveryAreaProv_DKI Jakarta')));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const ValueKey('deliveryAreaKota_Jakarta Selatan')));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const ValueKey('deliveryAreaKec_Kebayoran')));
    await t.pumpAndSettle();

    expect(find.byKey(const ValueKey('deliveryAreaSheet')), findsNothing);
    final a = results.single!;
    expect(a.kecamatanId, 11);
    expect((a.kecamatan, a.kota, a.provinsi), ('Kebayoran', 'Jakarta Selatan', 'DKI Jakarta'));
    expect(a.serviceable, isTrue);
  });

  testWidgets('面包屑：点省一级回到省列表重选', (t) async {
    await open(t);
    await t.tap(find.byKey(const ValueKey('deliveryAreaProv_DKI Jakarta')));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const ValueKey('deliveryAreaKota_Jakarta Selatan')));
    await t.pumpAndSettle();
    expect(find.byKey(const ValueKey('deliveryAreaCrumb_1')), findsOneWidget);

    // 点「市」一级 → 回到市列表
    await t.tap(find.byKey(const ValueKey('deliveryAreaCrumb_1')));
    await t.pumpAndSettle();
    expect(find.byKey(const ValueKey('deliveryAreaKota_Jakarta Selatan')), findsOneWidget);

    // 点「省」一级 → 回到省列表
    await t.tap(find.byKey(const ValueKey('deliveryAreaCrumb_0')));
    await t.pumpAndSettle();
    expect(find.byKey(const ValueKey('deliveryAreaProv_Jawa Barat')), findsOneWidget);
    expect(find.byKey(const ValueKey('deliveryAreaCrumb_0')), findsNothing);
  });

  testWidgets('未开通的区仍列出、标「belum dilayani」且可选', (t) async {
    final results = await open(t);
    await t.tap(find.byKey(const ValueKey('deliveryAreaProv_DKI Jakarta')));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const ValueKey('deliveryAreaKota_Jakarta Selatan')));
    await t.pumpAndSettle();
    expect(find.byKey(const ValueKey('deliveryAreaKec_Pesanggrahan_notServiceable')), findsOneWidget);
    expect(find.byKey(const ValueKey('deliveryAreaKec_Kebayoran_notServiceable')), findsNothing);

    await t.tap(find.byKey(const ValueKey('deliveryAreaKec_Pesanggrahan')));
    await t.pumpAndSettle();
    expect(results.single!.serviceable, isFalse);
    expect(results.single!.kecamatanId, 12);
  });

  testWidgets('当前所选打勾', (t) async {
    await open(t, selected: 12);
    await t.tap(find.byKey(const ValueKey('deliveryAreaProv_DKI Jakarta')));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const ValueKey('deliveryAreaKota_Jakarta Selatan')));
    await t.pumpAndSettle();
    expect(
        find.descendant(
            of: find.byKey(const ValueKey('deliveryAreaKec_Pesanggrahan')), matching: find.byIcon(Icons.check)),
        findsOneWidget);
    expect(
        find.descendant(
            of: find.byKey(const ValueKey('deliveryAreaKec_Kebayoran')), matching: find.byIcon(Icons.check)),
        findsNothing);
  });

  testWidgets('关闭 → 返回 null', (t) async {
    final results = await open(t);
    await t.tap(find.byKey(const ValueKey('deliveryAreaClose')));
    await t.pumpAndSettle();
    expect(results.single, isNull);
  });

  group('本地记住的地区（AppPrefs.shopDeliveryArea，只记本地）', () {
    setUp(() => SharedPreferences.setMockInitialValues({}));

    test('写入后能读回；不持久化 serviceable', () async {
      final prefs = await AppPrefs.create();
      expect(prefs.shopDeliveryArea, isNull);
      await prefs.setShopDeliveryArea(const ShopDeliveryArea(
          kecamatanId: 11, kecamatan: 'Kebayoran', kota: 'Jakarta Selatan', provinsi: 'DKI Jakarta',
          serviceable: true));
      final back = (await AppPrefs.create()).shopDeliveryArea!;
      expect(back.kecamatanId, 11);
      expect(back.kota, 'Jakarta Selatan');
      expect(back.serviceable, isNull);
    });

    test('provider：select 写盘，重建后仍记得', () async {
      final c = ProviderContainer();
      addTearDown(c.dispose);
      expect(await c.read(shopDeliveryAreaProvider.future), isNull);
      await c.read(shopDeliveryAreaProvider.notifier).select(const ShopDeliveryArea(
          kecamatanId: 21, kecamatan: 'Coblong', kota: 'Bandung', provinsi: 'Jawa Barat'));
      final c2 = ProviderContainer();
      addTearDown(c2.dispose);
      expect((await c2.read(shopDeliveryAreaProvider.future))!.kecamatanId, 21);
    });

    test('复审 #4：clear 清内存与本地（退出登录 / 换账号时由 app.dart 调）', () async {
      final c = ProviderContainer();
      addTearDown(c.dispose);
      await c.read(shopDeliveryAreaProvider.future);
      await c.read(shopDeliveryAreaProvider.notifier).select(const ShopDeliveryArea(
          kecamatanId: 21, kecamatan: 'Coblong', kota: 'Bandung', provinsi: 'Jawa Barat'));
      await c.read(shopDeliveryAreaProvider.notifier).clear();
      expect(c.read(shopDeliveryAreaProvider).value, isNull);
      expect((await AppPrefs.create()).shopDeliveryArea, isNull);
    });

    test('复审 #4：leftUserAccount —— 退出 / 换号为 true，冷启动恢复与同号为 false', () {
      expect(leftUserAccount(7, null), isTrue, reason: '退出登录');
      expect(leftUserAccount(7, 8), isTrue, reason: '直接换账号');
      expect(leftUserAccount(null, 7), isFalse, reason: '冷启动恢复会话不清');
      expect(leftUserAccount(7, 7), isFalse);
      expect(leftUserAccount(null, null), isFalse);
    });

    test('脏数据按未选', () async {
      SharedPreferences.setMockInitialValues({AppPrefs.kShopDeliveryArea: '{oops'});
      expect((await AppPrefs.create()).shopDeliveryArea, isNull);
    });
  });
}
