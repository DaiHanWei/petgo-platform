import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/address/data/address_repository.dart';
import 'package:tailtopia/features/shop/address/domain/shipping_address.dart';
import 'package:tailtopia/features/shop/presentation/widgets/delivery_area_sheet.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// H4 地区选择抽屉（v1.4.2 shop Story 9.4 AC4）：8.2 B6 抽屉的**表单模式**。
///
/// 看：标题「Pilih wilayah」、逐级、未开通的区同样可选且行尾「Belum dilayani」（句首大写）、
/// 不要求区有 id（地址按名称存）、当前所选各级打勾、换上级后下级从新上级列出。
void main() {
  // 故意不给 id：表单模式不依赖 id（8.2 默认模式下无 id 的区不可选）。
  const tree = RegionTree([
    RegionProvinsi('DKI Jakarta', [
      RegionKota('Jakarta Selatan', [
        RegionKecamatan('Kebayoran', true),
        RegionKecamatan('Pesanggrahan', false),
      ]),
    ]),
    RegionProvinsi('Jawa Barat', [
      RegionKota('Bandung', [RegionKecamatan('Coblong', true)]),
    ]),
  ]);

  Future<List<RegionSelection?>> open(WidgetTester t,
      {RegionSelection? selected, Locale locale = const Locale('id')}) async {
    final results = <RegionSelection?>[];
    await t.pumpWidget(ProviderScope(
      overrides: [regionTreeProvider.overrideWith((ref) async => tree)],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: locale,
        home: Builder(
          builder: (c) => Scaffold(
            body: TextButton(
              key: const ValueKey('openSheet'),
              onPressed: () async =>
                  results.add(await showRegionPickerSheet(c, selected: selected)),
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

  testWidgets('标题「Pilih wilayah」+ 省 → 市 → 区逐级；选中即关并按名称返回', (t) async {
    final results = await open(t);
    expect(find.byKey(const ValueKey('regionPickerSheet')), findsOneWidget);
    expect(find.text('Pilih wilayah'), findsOneWidget);

    await t.tap(find.byKey(const ValueKey('deliveryAreaProv_DKI Jakarta')));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const ValueKey('deliveryAreaKota_Jakarta Selatan')));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const ValueKey('deliveryAreaKec_Kebayoran')));
    await t.pumpAndSettle();

    expect(find.byKey(const ValueKey('regionPickerSheet')), findsNothing);
    final r = results.single!;
    expect((r.provinsi, r.kota, r.kecamatan, r.serviceable),
        ('DKI Jakarta', 'Jakarta Selatan', 'Kebayoran', true));
  });

  testWidgets('🔴 未开通的区同样可选，行尾灰字「Belum dilayani」', (t) async {
    final results = await open(t);
    await t.tap(find.byKey(const ValueKey('deliveryAreaProv_DKI Jakarta')));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const ValueKey('deliveryAreaKota_Jakarta Selatan')));
    await t.pumpAndSettle();

    final tag = find.byKey(const ValueKey('deliveryAreaKec_Pesanggrahan_notServiceable'));
    expect(tag, findsOneWidget);
    expect(t.widget<Text>(tag).data, 'Belum dilayani');
    expect(find.byKey(const ValueKey('deliveryAreaKec_Kebayoran_notServiceable')), findsNothing);

    await t.tap(find.byKey(const ValueKey('deliveryAreaKec_Pesanggrahan')));
    await t.pumpAndSettle();
    expect(results.single?.kecamatan, 'Pesanggrahan');
    expect(results.single?.serviceable, isFalse);
  });

  testWidgets('已有值 → 直接停在该市的区列表，当前区打勾；关闭返回 null', (t) async {
    final results = await open(t,
        selected: const RegionSelection(
            provinsi: 'DKI Jakarta', kota: 'Jakarta Selatan', kecamatan: 'Kebayoran'));
    expect(find.byKey(const ValueKey('deliveryAreaKec_Kebayoran')), findsOneWidget);
    expect(
        find.descendant(
            of: find.byKey(const ValueKey('deliveryAreaKec_Kebayoran')),
            matching: find.byIcon(Icons.check)),
        findsOneWidget);
    expect(find.byIcon(Icons.check), findsOneWidget);

    await t.tap(find.byKey(const ValueKey('deliveryAreaClose')));
    await t.pumpAndSettle();
    expect(results.single, isNull);
  });

  testWidgets('🔴 换上级清空下级：回到省级换省，列出新省的市，旧路径不再打勾', (t) async {
    final results = await open(t,
        selected: const RegionSelection(
            provinsi: 'DKI Jakarta', kota: 'Jakarta Selatan', kecamatan: 'Kebayoran'));
    await t.tap(find.byKey(const ValueKey('deliveryAreaCrumb_0')));
    await t.pumpAndSettle();
    // 省级：当前省打勾
    expect(
        find.descendant(
            of: find.byKey(const ValueKey('deliveryAreaProv_DKI Jakarta')),
            matching: find.byIcon(Icons.check)),
        findsOneWidget);
    await t.tap(find.byKey(const ValueKey('deliveryAreaProv_Jawa Barat')));
    await t.pumpAndSettle();
    expect(find.byKey(const ValueKey('deliveryAreaKota_Jakarta Selatan')), findsNothing);
    expect(find.byIcon(Icons.check), findsNothing);
    await t.tap(find.byKey(const ValueKey('deliveryAreaKota_Bandung')));
    await t.pumpAndSettle();
    await t.tap(find.byKey(const ValueKey('deliveryAreaKec_Coblong')));
    await t.pumpAndSettle();
    final r = results.single!;
    expect((r.provinsi, r.kota, r.kecamatan), ('Jawa Barat', 'Bandung', 'Coblong'));
  });
}
