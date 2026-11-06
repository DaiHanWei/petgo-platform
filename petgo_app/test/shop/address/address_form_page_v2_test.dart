import 'package:dio/dio.dart';
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

import 'fill_address_form.dart';

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
    String? token,
    AddressRepository? repo,
    Locale locale = const Locale('id'),
  }) {
    return ProviderScope(
      overrides: [
        if (repo != null) addressRepositoryProvider.overrideWithValue(repo),
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
              RegionProvinsi('Jawa Barat', [
                RegionKota('Bandung', [RegionKecamatan('Coblong', true)]),
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
        locale: locale,
        home: MediaQuery(
          data: MediaQueryData(size: size, textScaler: TextScaler.linear(textScale)),
          child: AddressFormPageV2(token: token),
        ),
      ),
    );
  }

  /// 选到某个 kecamatan：点「Wilayah」行打开 H4 抽屉，逐级点（v1.4.2 shop Story 9.4）。
  Future<void> pickRegion(WidgetTester tester, String kecamatan,
      {String provinsi = 'DKI Jakarta', String kota = 'Jakarta Selatan'}) async {
    await tester.tap(find.byKey(const ValueKey('addressRegionRow')));
    await tester.pumpAndSettle();
    await pickRegionInSheet(tester, provinsi, kota, kecamatan);
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
    // v1.4.2 shop Story 9.4（H3）：三个下拉合并成一行「Wilayah」+ 抽屉逐级 —— 仍不可自由输入。
    testWidgets('三级合并为「Wilayah」一行（点开抽屉逐级选），没有可输入的行政区文本框', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('addressRegionRow')), findsOneWidget);
      expect(find.text(l10n.addressRegionLabel), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(const ValueKey('addressRegionValue'))).data,
          l10n.addressRegionPick, reason: '未选时显示占位「Pilih wilayah」');
      expect(find.byType(DropdownButtonFormField<String>), findsNothing,
          reason: '三个下拉已合并');

      await pickRegion(tester, 'Kebayoran');
      expect(tester.widget<Text>(find.byKey(const ValueKey('addressRegionValue'))).data,
          'DKI Jakarta, Jakarta Selatan, Kebayoran');
      // 可输入的只有：收件人 / 电话 / 邮编 / 详细地址 —— 行政区不在其中。
      expect(find.byType(TextField), findsNWidgets(4),
          reason: '让用户自己打行政区，服务范围就永远对不上，且下单前无从发现');
    });

    testWidgets('换了上级会清空下级 —— 否则会拼出不存在的行政区组合', (tester) async {
      // v1.4.2 shop Story 9.4：断言移到抽屉内 —— 回到省级换了省，列出的是新省的市 / 区，
      // 旧的市 / 区不再出现、也不打勾；选完区后表单三级一起换成新组合。
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();
      await pickRegion(tester, 'Kebayoran');

      await tester.tap(find.byKey(const ValueKey('addressRegionRow')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('deliveryAreaCrumb_0')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('deliveryAreaProv_Jawa Barat')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('deliveryAreaKota_Jakarta Selatan')), findsNothing,
          reason: '换了上级，下级必须从新上级重新列出');
      expect(find.byKey(const ValueKey('deliveryAreaKota_Bandung')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('deliveryAreaKota_Bandung')));
      await tester.pumpAndSettle();
      expect(find.byIcon(Icons.check), findsNothing, reason: '新路径下没有旧的已选项');
      await tester.tap(find.byKey(const ValueKey('deliveryAreaKec_Coblong')));
      await tester.pumpAndSettle();

      expect(tester.widget<Text>(find.byKey(const ValueKey('addressRegionValue'))).data,
          'Jawa Barat, Bandung, Coblong');
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

  group('v1.4.2 shop Story 9.4 · H3 标题 / 编辑预填 / 顶部错误条 / 标签', () {
    const existing = ShippingAddress(
      token: 'a1',
      receiverName: 'Budi Santoso',
      receiverPhone: '+628123456789',
      provinsi: 'DKI Jakarta',
      kotaKabupaten: 'Jakarta Selatan',
      kecamatan: 'Pesanggrahan',
      addressLine: 'Jl. Melati No. 5, dekat masjid',
      kodePos: '12160',
      label: 'Kantor',
      isDefault: true,
    );

    testWidgets('新增标题「Tambah alamat」', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.pumpWidget(host());
      await tester.pumpAndSettle();
      expect(find.text(l10n.addressAdd), findsOneWidget);
      expect(find.text(l10n.addressEditTitle), findsNothing);
    });

    testWidgets('🔴 编辑：标题「Ubah alamat」+ 预填全部字段（修线上：编辑进来是空表单）', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.pumpWidget(host(existing: const [existing], token: 'a1'));
      await tester.pumpAndSettle();

      expect(find.text(l10n.addressEditTitle), findsOneWidget);
      expect(find.text(l10n.addressAdd), findsNothing);
      String text(String id) =>
          tester.widget<TextField>(find.byKey(ValueKey('addressField_$id'))).controller!.text;
      expect(text('receiver'), 'Budi Santoso');
      expect(text('phone'), '8123456789', reason: '显示规则同新增态：去掉 +62');
      expect(text('kodePos'), '12160');
      expect(text('line'), 'Jl. Melati No. 5, dekat masjid');
      expect(tester.widget<Text>(find.byKey(const ValueKey('addressRegionValue'))).data,
          'DKI Jakarta, Jakarta Selatan, Pesanggrahan');
      expect(find.byKey(const ValueKey('addressOutOfRangeHint')), findsOneWidget,
          reason: '预填后开通范围提示照常出现');
      await _scrollTo(tester, const ValueKey('addressDefaultSwitch'));
      expect(tester.widget<ShopChip>(find.byKey(const ValueKey('addressLabel_Kantor'))).selected,
          isTrue);
      expect(tester.widget<ShopSwitch>(find.byKey(const ValueKey('addressDefaultSwitch'))).value,
          isTrue);
    });

    testWidgets('编辑：列表里找不到该 token → toast addressLoadFailed 并退出', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.pumpWidget(ProviderScope(
        overrides: [addressListProvider.overrideWith((ref) async => const [existing])],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: Builder(
            builder: (ctx) => TextButton(
              onPressed: () => Navigator.of(ctx).push(MaterialPageRoute<void>(
                  builder: (_) => const AddressFormPageV2(token: 'gone'))),
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      expect(find.text(l10n.addressLoadFailed), findsOneWidget);
      expect(find.text('open'), findsOneWidget, reason: '已 pop 回上一页');
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
    });

    testWidgets('🔴 编辑保存走 PUT，标签提交值仍是印尼语字面量', (tester) async {
      final repo = _RecordingRepo();
      await tester.pumpWidget(host(
          existing: const [existing], token: 'a1', repo: repo, locale: const Locale('en')));
      await tester.pumpAndSettle();
      await _scrollTo(tester, const ValueKey('addressLabel_Rumah'));
      await tester.tap(find.byKey(const ValueKey('addressLabel_Rumah')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('addressSaveV2')));
      await tester.pumpAndSettle();

      expect(repo.updatedToken, 'a1');
      expect(repo.updated?.label, 'Rumah', reason: '显示是 Home，提交值必须仍为 Rumah');
      expect(repo.updated?.receiverPhone, '+628123456789');
    });

    testWidgets('标签胶囊显示走 arb（en：Home / Office / Other）', (tester) async {
      await tester.pumpWidget(host(locale: const Locale('en')));
      await tester.pumpAndSettle();
      await _scrollTo(tester, const ValueKey('addressLabel_Rumah'));
      expect(tester.widget<ShopChip>(find.byKey(const ValueKey('addressLabel_Rumah'))).label,
          'Home');
      expect(tester.widget<ShopChip>(find.byKey(const ValueKey('addressLabel_Kantor'))).label,
          'Office');
      expect(tester.widget<ShopChip>(find.byKey(const ValueKey('addressLabel_Lainnya'))).label,
          'Other');
    });

    testWidgets('🔴 提交失败 → 错误条在表单顶部（第一个子项，在收件人块之上）', (tester) async {
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      await tester.pumpWidget(
          host(existing: const [existing], token: 'a1', repo: _RecordingRepo(fail: true)));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('addressSaveV2')));
      await tester.pumpAndSettle();

      final bar = find.byKey(const ValueKey('addressSubmitError'));
      expect(bar, findsOneWidget);
      expect(find.descendant(of: bar, matching: find.text(l10n.addressSaveFailed)), findsOneWidget);
      final barY = tester.getTopLeft(bar).dy;
      final receiverY = tester.getTopLeft(find.byKey(const ValueKey('addressField_receiver'))).dy;
      expect(barY, lessThan(receiverY), reason: '错误条必须在表单顶部');
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

class _RecordingRepo extends AddressRepository {
  _RecordingRepo({this.fail = false}) : super(dio: Dio());

  final bool fail;
  String? updatedToken;
  ShippingAddress? updated;

  @override
  Future<ShippingAddress> update(String token, ShippingAddress a) async {
    if (fail) throw DioException(requestOptions: RequestOptions(path: '/x'));
    updatedToken = token;
    updated = a;
    return ShippingAddress(
      token: token,
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
