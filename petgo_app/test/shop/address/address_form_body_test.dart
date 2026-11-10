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

  testWidgets('🔴 ocr L2：新增时「设为默认」失败 → 提示失败；再点保存走 update，不重复创建', (tester) async {
    final repo = _FakeAddressRepo()
      ..createdIsDefault = false
      ..setDefaultFailsOnce = true;
    tester.view.physicalSize = const Size(800, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    ShippingAddress? got;
    const existing = ShippingAddress(
      token: 'addr-old',
      receiverName: 'X',
      receiverPhone: '081200000000',
      provinsi: 'DKI Jakarta',
      kotaKabupaten: 'Jakarta Selatan',
      kecamatan: 'Kebayoran',
      addressLine: 'Jl. Lama',
      kodePos: '12160',
      label: 'Rumah',
      isDefault: true,
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        addressRepositoryProvider.overrideWithValue(repo),
        addressListProvider.overrideWith((ref) async => const [existing]),
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
          body: AddressFormBody(submitLabel: 'Simpan', onSaved: (a) => got = a),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    final sw = find.byKey(const ValueKey('addressDefaultSwitch'));
    await tester.ensureVisible(sw);
    await tester.tap(sw);
    await tester.pumpAndSettle();

    await fillAndSaveAddressForm(tester);
    expect(repo.created, 1);
    expect(got, isNull, reason: '设默认失败按整体失败提示，不离开表单');

    final save = find.byKey(const ValueKey('addressSaveV2'));
    await tester.ensureVisible(save);
    await tester.tap(save);
    await tester.pumpAndSettle();
    expect(repo.created, 1, reason: '重试不能再 create 一条重复地址');
    expect(repo.updatedTokens, ['addr-new']);
    expect(got?.token, 'addr-new');
  });

  testWidgets('UI D5：compact 抽屉形态只有五项 —— 无标签块、无「设为默认」，不选标签也能保存', (tester) async {
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
          body: AddressFormBody(compact: true, submitLabel: 'Simpan & pakai', onSaved: (a) => got = a),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('addressLabel_Rumah')), findsNothing);
    expect(find.byKey(const ValueKey('addressDefaultSwitch')), findsNothing);
    await fillAndSaveAddressForm(tester);
    expect(repo.created, 1);
    expect(got?.token, 'addr-new');
  });
}

class _FakeAddressRepo extends AddressRepository {
  _FakeAddressRepo() : super(dio: Dio());

  int created = 0;
  bool createdIsDefault = true;
  bool setDefaultFailsOnce = false;
  final List<String> updatedTokens = [];

  ShippingAddress _echo(String token, ShippingAddress a, bool isDefault) => ShippingAddress(
        token: token,
        receiverName: a.receiverName,
        receiverPhone: a.receiverPhone,
        provinsi: a.provinsi,
        kotaKabupaten: a.kotaKabupaten,
        kecamatan: a.kecamatan,
        addressLine: a.addressLine,
        kodePos: a.kodePos,
        label: a.label,
        isDefault: isDefault,
      );

  @override
  Future<ShippingAddress> update(String token, ShippingAddress a) async {
    updatedTokens.add(token);
    return _echo(token, a, false);
  }

  @override
  Future<ShippingAddress> setDefault(String token) async {
    if (setDefaultFailsOnce) {
      setDefaultFailsOnce = false;
      throw DioException(requestOptions: RequestOptions(path: '/x'));
    }
    return const ShippingAddress(
      token: 'addr-new',
      receiverName: 'Siti Aminah',
      receiverPhone: '081234567890',
      provinsi: 'DKI Jakarta',
      kotaKabupaten: 'Jakarta Selatan',
      kecamatan: 'Kebayoran',
      addressLine: 'x',
      kodePos: '12160',
      label: 'Rumah',
      isDefault: true,
    );
  }

  @override
  Future<ShippingAddress> create(ShippingAddress a) async {
    created++;
    if (!createdIsDefault) return _echo('addr-new', a, false);
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
