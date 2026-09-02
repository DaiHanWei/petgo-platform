import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/shop/address/data/address_repository.dart';
import 'package:tailtopia/features/shop/address/domain/shipping_address.dart';
import 'package:tailtopia/features/shop/address/presentation/address_form_page_v2.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_buttons.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_controls.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 新增收货地址 · **设计稿版式**（V1.4.0 第 1 批）。
///
/// v1 版式的用例在 `test/shop/address/`，两套互不影响。
///
/// 本类看三件事：**范围外不得阻断保存**（FR-98/FR-99 的分界）、
/// **行政区不得自由输入**、以及**首个地址强制为默认**。
/// 前两件写错的后果都不是「界面难看」，是用户永远存不进一个地址、或存进一个查不到的地址。
void main() {
  Widget host({
    List<ShippingAddress> existing = const [],
    RegionTree? regions,
    Size size = const Size(411, 891),
    double textScale = 1,
  }) {
    return ProviderScope(
      overrides: [
        addressListProvider.overrideWith((ref) async => existing),
        regionTreeProvider.overrideWith((ref) async =>
            regions ??
            const RegionTree([
              RegionProvinsi('DKI Jakarta', [
                RegionKota('Jakarta Selatan', [
                  RegionKecamatan('Kebayoran', true),
                  RegionKecamatan('Pesanggrahan', false),
                ]),
              ]),
            ])),
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
        home: MediaQuery(
          data: MediaQueryData(size: size, textScaler: TextScaler.linear(textScale)),
          child: const AddressFormPageV2(),
        ),
      ),
    );
  }

  /// 选到某个 kecamatan。三级级联要逐级点。
  Future<void> pickRegion(WidgetTester tester, String kecamatan) async {
    for (final (id, value) in [
      ('provinsi', 'DKI Jakarta'),
      ('kota', 'Jakarta Selatan'),
      ('kecamatan', kecamatan),
    ]) {
      await tester.tap(find.byKey(ValueKey('addressPicker_$id')));
      await tester.pumpAndSettle();
      // 不可配送的 kecamatan 显示名带「· belum dilayani」后缀 → 用包含匹配。
      await tester.tap(find.textContaining(value).last);
      await tester.pumpAndSettle();
    }
  }

  /// 🔴 D-17（2026-09-02 stag，P2）：Label 是必填，却**既无必填标记、校验失败也零反馈**。
  ///
  /// 复现：除 Label 外全部填妥（且已提示 `We deliver to this area`），点 Save ——
  /// **页面纹丝不动**：无 toast、无字段标红、不滚动。选任一 Label 后再点立即成功。
  ///
  /// 根因两层：① `label` 不在端上的校验序列里，于是直接提交、由服务端拒；
  /// ② 服务端错误落到 `_submitError`，而那块提示画在表单**顶部**，
  ///    用户是在**底部**点的保存 —— 提示在看不见的地方。
  ///
  /// ⚠️ 报告点名这是本轮**第三次**撞见「校验失败零反馈」（前两次：D-8 上传 403 静默、
  /// D-12 退货提交无提示），是全局性的反馈缺失，不是这一页的疏忽。
  group('🔴 D-17：Label 必填要标出来，拦下来要说话', () {
    testWidgets('🔴 标签区有必填标记', (tester) async {
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();
      // ⚠️ 表单是 ListView（懒构建），标签区在最下面 —— 不滚过去它压根不在树里。
      await _scrollTo(tester, const ValueKey('addressLabel_Rumah'));

      expect(find.text(' *'), findsWidgets,
          reason: '必填却没有任何标记，用户不知道这里非选不可');
    });

    testWidgets('🔴 未选 Label 点保存 → 就地标红，而不是静默', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('addressSaveV2')));
      await tester.pumpAndSettle();
      await _scrollTo(tester, const ValueKey('addressLabel_Rumah'));

      final err = tester.widget<Text>(find.byKey(const ValueKey('addressLabelError')));
      expect(err.data, l10n.addressRequired,
          reason: '「页面纹丝不动」正是 D-17 的形态');
    });

    testWidgets('选了 Label → 该项不再报错', (tester) async {
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('addressSaveV2')));
      await tester.pumpAndSettle();
      await _scrollTo(tester, const ValueKey('addressLabel_Rumah'));
      expect(find.byKey(const ValueKey('addressLabelError')), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('addressLabel_Rumah')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('addressSaveV2')));
      await tester.pumpAndSettle();
      await _scrollTo(tester, const ValueKey('addressLabel_Rumah'));

      expect(find.byKey(const ValueKey('addressLabelError')), findsNothing);
    });

    testWidgets('🔴 不默认替用户选 Rumah', (tester) async {
      // 报告给了「默认选中 Rumah」这个选项，但那是替用户做了一次他没做过的选择，
      // 而这个标签会显示在他日后的地址列表里。宁可要求他点一下。
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();
      await _scrollTo(tester, const ValueKey('addressLabel_Rumah'));

      final chip = tester.widget<ShopChip>(
          find.byKey(const ValueKey('addressLabel_Rumah')));
      expect(chip.selected, isFalse);
    });
  });

  group('🔴 行政区三级级联，不做自由输入', () {
    testWidgets('三级都是选择器，没有可输入的行政区文本框', (tester) async {
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();

      for (final id in ['provinsi', 'kota', 'kecamatan']) {
        expect(find.byKey(ValueKey('addressPicker_$id')), findsOneWidget);
      }
      // 可输入的只有：收件人 / 电话 / 邮编 / 详细地址 —— 行政区不在其中。
      expect(find.byType(TextField), findsNWidgets(4),
          reason: '让用户自己打行政区，服务范围就永远对不上，且下单前无从发现');
    });

    testWidgets('换了上级会清空下级 —— 否则会拼出不存在的行政区组合', (tester) async {
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();
      await pickRegion(tester, 'Kebayoran');

      // 重新选 provinsi → kecamatan 的范围提示应消失（说明已被清空）
      await tester.tap(find.byKey(const ValueKey('addressPicker_provinsi')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('DKI Jakarta').last);
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('addressInRangeHint')), findsNothing);
    });
  });

  group('🔴 FR-98/FR-99 分界：范围外只告知、不阻断保存', () {
    testWidgets('选到不可配送的 kecamatan → 橙提示，但保存按钮仍可点', (tester) async {
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();
      await pickRegion(tester, 'Pesanggrahan');

      expect(find.byKey(const ValueKey('addressOutOfRangeHint')), findsOneWidget);

      // 🔴 保存按钮不因超范围而禁用 —— 用户可能为将来备一个地址，
      //    真正的拦截发生在结算页（FR-98 / FR-99 的分界）。
      final save = tester.widget<ShopButton>(find.byKey(const ValueKey('addressSaveV2')));
      expect(save.onTap, isNotNull);
      expect(save.variant, isNot(ShopButtonVariant.disabled));
    });

    testWidgets('选到可配送的 kecamatan → 紫提示', (tester) async {
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();
      await pickRegion(tester, 'Kebayoran');

      expect(find.byKey(const ValueKey('addressInRangeHint')), findsOneWidget);
      expect(find.byKey(const ValueKey('addressOutOfRangeHint')), findsNothing);
    });

    testWidgets('未选 kecamatan 时不渲染任何范围提示', (tester) async {
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('addressInRangeHint')), findsNothing);
      expect(find.byKey(const ValueKey('addressOutOfRangeHint')), findsNothing);
    });
  });

  group('🔴 首个地址强制为默认', () {
    testWidgets('地址簿为空 → 默认开关常亮且点不动', (tester) async {
      await tester.pumpWidget(host(existing: const []));
      await tester.pumpAndSettle();

      await _scrollTo(tester, const ValueKey('addressDefaultSwitch'));

      final sw = tester.widget<ShopSwitch>(find.byKey(const ValueKey('addressDefaultSwitch')));
      expect(sw.alwaysOn, isTrue,
          reason: '「唯一的地址却不是默认地址」在结算页表现为「没有可用地址」，用户无从理解');
    });

    testWidgets('已有地址 → 开关可自由切换', (tester) async {
      await tester.pumpWidget(host(existing: const [
        ShippingAddress(
          token: 'a1',
          receiverName: 'Budi',
          receiverPhone: '8123456789',
          provinsi: 'DKI Jakarta',
          kotaKabupaten: 'Jakarta Selatan',
          kecamatan: 'Kebayoran',
          addressLine: 'Jl. Test No. 1',
          kodePos: '12160',
          isDefault: true,
        ),
      ]));
      await tester.pumpAndSettle();

      await _scrollTo(tester, const ValueKey('addressDefaultSwitch'));

      final sw = tester.widget<ShopSwitch>(find.byKey(const ValueKey('addressDefaultSwitch')));
      expect(sw.alwaysOn, isFalse);
    });
  });

  group('校验', () {
    testWidgets('空表单点保存 → 第一个字段报错并滚到它', (tester) async {
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('addressSaveV2')));
      await tester.pumpAndSettle();

      expect(find.text('Wajib diisi'), findsWidgets);
    });

    testWidgets('详细地址少于 10 字报错 —— 一行「Jl. A」送不到货', (tester) async {
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const ValueKey('addressField_line')), 'Jl. A');
      await tester.tap(find.byKey(const ValueKey('addressSaveV2')));
      await tester.pumpAndSettle();

      expect(find.text('Isi minimal 10 karakter'), findsOneWidget);
    });

    testWidgets('邮编非 5 位报错', (tester) async {
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const ValueKey('addressField_kodePos')), '121');
      await tester.tap(find.byKey(const ValueKey('addressSaveV2')));
      await tester.pumpAndSettle();

      expect(find.text('Isi 5 angka'), findsOneWidget);
    });

    testWidgets('🔴 手机号用与服务端同一套归一化口径', (tester) async {
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const ValueKey('addressField_phone')), '123');
      await tester.tap(find.byKey(const ValueKey('addressSaveV2')));
      await tester.pumpAndSettle();

      // 前端自己写一套正则必然与后端漂移 —— 表现为「前端过了后端拒」。
      expect(find.textContaining('nomor HP Indonesia'), findsOneWidget);
    });
  });

  group('布局不得溢出', () {
    testWidgets('411dp · 标准字号', (tester) async {
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });

    testWidgets('1.3 倍字号 + 全字段报错', (tester) async {
      await tester.pumpWidget(host(textScale: 1.3));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('addressSaveV2')));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  });
}

/// 滚到指定 key 可见。
///
/// ⚠️ 必须显式给 `scrollable` —— 本页除了外层 ListView，下拉框与多行输入框
/// 各自也带 Scrollable，不指定的话 `scrollUntilVisible` 会因「找到多个」直接抛错。
Future<void> _scrollTo(WidgetTester tester, Key key) async {
  await tester.scrollUntilVisible(
    find.byKey(key),
    200,
    scrollable: find.byType(Scrollable).first,
    maxScrolls: 20,
  );
  await tester.pumpAndSettle();
}
