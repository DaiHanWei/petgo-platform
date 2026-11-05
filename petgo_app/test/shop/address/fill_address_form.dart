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
  for (final (id, value) in [
    ('provinsi', 'DKI Jakarta'),
    ('kota', 'Jakarta Selatan'),
    ('kecamatan', 'Kebayoran'),
  ]) {
    final picker = find.byKey(ValueKey('addressPicker_$id'));
    await tester.ensureVisible(picker);
    await tester.tap(picker);
    await tester.pumpAndSettle();
    await tester.tap(find.textContaining(value).last);
    await tester.pumpAndSettle();
  }
  await field('kodePos', '12160');
  await field('line', 'Jl. Melati No. 5, dekat masjid');
  final label = find.byKey(const ValueKey('addressLabel_Rumah'));
  await tester.ensureVisible(label);
  await tester.tap(label);
  await tester.pumpAndSettle();
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
