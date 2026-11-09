import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/address/domain/shipping_address.dart';

/// 测试辅助（非测试文件）：把 [AddressFormBody] 填成一份合法地址并点保存。
///
/// 区域树须含 `DKI Jakarta / Jakarta Selatan / Kebayoran`（见 [kTestRegions]）。
Future<void> fillAndSaveAddressForm(WidgetTester tester) async {
  Future<void> field(String id, String text) async {
    final f = find.byKey(ValueKey('addressField_$id'));
    await tester.ensureVisible(f);
    await tester.enterText(f, text);
    await tester.pumpAndSettle();
  }

  await field('receiver', 'Siti Aminah');
  await field('phone', '081234567890');
  // v1.4.2 shop Story 9.4（H3 / H4）：省市区合并为一行「Wilayah」，点开地区抽屉逐级选。
  final row = find.byKey(const ValueKey('addressRegionRow'));
  await tester.ensureVisible(row);
  await tester.tap(row);
  await tester.pumpAndSettle();
  await pickRegionInSheet(tester, 'DKI Jakarta', 'Jakarta Selatan', 'Kebayoran');
  await field('kodePos', '12160');
  await field('line', 'Jl. Melati No. 5, dekat masjid');
  // 结算页 D5 抽屉形态（compact）不出标签块，只有地址簿页才选标签。
  final label = find.byKey(const ValueKey('addressLabel_Rumah'));
  if (label.evaluate().isNotEmpty) {
    await tester.ensureVisible(label);
    await tester.tap(label);
    await tester.pumpAndSettle();
  }
  await tester.tap(find.byKey(const ValueKey('addressSaveV2')));
  await tester.pumpAndSettle();
}

const kTestRegions = RegionTree([
  RegionProvinsi('DKI Jakarta', [
    RegionKota('Jakarta Selatan', [
      RegionKecamatan('Kebayoran', true),
      RegionKecamatan('Pesanggrahan', false),
    ]),
    RegionKota('Jakarta Pusat', []),
  ]),
  RegionProvinsi('Jawa Barat', [RegionKota('Bandung', [])]),
]);

/// 在已打开的地区抽屉（H4 表单模式）里逐级点选省 → 市 → 区。
Future<void> pickRegionInSheet(
    WidgetTester tester, String provinsi, String kota, String kecamatan) async {
  for (final key in [
    'deliveryAreaProv_$provinsi',
    'deliveryAreaKota_$kota',
    'deliveryAreaKec_$kecamatan',
  ]) {
    await tester.tap(find.byKey(ValueKey(key)));
    await tester.pumpAndSettle();
  }
}
