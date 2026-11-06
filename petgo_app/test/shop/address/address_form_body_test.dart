import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/address/data/address_repository.dart';
import 'package:tailtopia/features/shop/address/domain/shipping_address.dart';
import 'package:tailtopia/features/shop/address/presentation/address_form_body.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import 'fill_address_form.dart';

/// v1.4.2 shop Story 7.1 AC4.6：地址表单抽成组件（地址簿页与结算页 D5 共用）。
void main() {
  testWidgets('保存成功：onSaved 拿到**服务端返回**的地址（不是端上拼的那份）', (tester) async {
    final repo = _FakeAddressRepo();
    ShippingAddress? got;
    await tester.pumpWidget(ProviderScope(
      overrides: [
        addressRepositoryProvider.overrideWithValue(repo),
        addressListProvider.overrideWith((ref) async => const []),
        regionTreeProvider.overrideWith((ref) async => kTestRegions),
      ],
      child: MaterialApp(
        localizationsDelegates: const [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        home: Scaffold(
          body: AddressFormBody(submitLabel: 'Simpan & pakai', onSaved: (a) => got = a),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Simpan & pakai'), findsOneWidget, reason: '主按钮文案由宿主传入');

    await fillAndSaveAddressForm(tester);

    expect(repo.created, 1);
    expect(got?.token, 'addr-new', reason: 'onSaved 必须拿到服务端分配的 token');
  });
}

class _FakeAddressRepo extends AddressRepository {
  _FakeAddressRepo() : super(dio: Dio());

  int created = 0;

  @override
  Future<ShippingAddress> create(ShippingAddress a) async {
    created++;
    return ShippingAddress(
      token: 'addr-new',
      receiverName: a.receiverName,
      receiverPhone: a.receiverPhone,
      provinsi: a.provinsi,
      kotaKabupaten: a.kotaKabupaten,
      kecamatan: a.kecamatan,
      addressLine: a.addressLine,
      kodePos: a.kodePos,
      label: a.label,
      isDefault: true,
    );
  }
}
