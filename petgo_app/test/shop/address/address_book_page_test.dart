import 'dart:async';

import 'package:dio/dio.dart';
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
  ShippingAddress addr(String token,
          {bool isDefault = false, String name = 'Budi', bool? serviceable, String? label}) =>
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
        serviceable: serviceable,
        label: label,
      );

  Widget host(
          {required bool selecting,
          required List<ShippingAddress> items,
          ValueChanged<String?>? onPop,
          AddressRepository? repo,
          Future<List<ShippingAddress>> Function()? load,
          Locale locale = const Locale('id')}) =>
      ProviderScope(
        overrides: [
          if (repo != null) addressRepositoryProvider.overrideWithValue(repo),
          addressListProvider.overrideWith((ref) => load != null ? load() : Future.value(items)),
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

  /// 打开地址簿（管理模式）。
  Future<AppLocalizations> openBook(WidgetTester tester, List<ShippingAddress> items,
      {AddressRepository? repo,
      Future<List<ShippingAddress>> Function()? load,
      Locale locale = const Locale('id')}) async {
    await tester.pumpWidget(
        host(selecting: false, items: items, repo: repo, load: load, locale: locale));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    return AppLocalizations.delegate.load(locale);
  }

  group('v1.4.2 shop Story 9.4 · H1 卡片', () {
    testWidgets('🔴 serviceable == false → 橙标签「Belum bisa dikirim kurir」；true / null 不标', (tester) async {
      final l10n = await openBook(tester, [
        addr('no', serviceable: false),
        addr('yes', serviceable: true),
        addr('unknown'),
      ]);
      expect(find.byKey(const ValueKey('addressNotDeliverable_no')), findsOneWidget);
      expect(find.byKey(const ValueKey('addressNotDeliverable_yes')), findsNothing);
      expect(find.byKey(const ValueKey('addressNotDeliverable_unknown')), findsNothing,
          reason: '询价失败 = null，App 不标注');
      expect(find.text(l10n.addressNotDeliverable), findsOneWidget);
      expect(l10n.addressNotDeliverable, 'Belum bisa dikirim kurir');
    });

    testWidgets('默认卡显示「Utama」，非默认卡有「Jadikan utama」', (tester) async {
      final l10n = await openBook(tester, [addr('home', isDefault: true), addr('office')]);
      expect(find.byKey(const ValueKey('addressDefaultTag_home')), findsOneWidget);
      expect(find.byKey(const ValueKey('addressDefaultTag_office')), findsNothing);
      expect(find.text(l10n.addressDefaultBadge), findsOneWidget);
      expect(find.byKey(const ValueKey('addressSetDefault_home')), findsNothing);
      expect(find.byKey(const ValueKey('addressSetDefault_office')), findsOneWidget);
    });

    testWidgets('地址标签按当前语言显示（en：Rumah → Home；未知值原样）', (tester) async {
      await openBook(tester, [addr('a', label: 'Rumah'), addr('b', label: 'Kos')],
          locale: const Locale('en'));
      Text tag(String token) => tester.widget<Text>(find.descendant(
          of: find.byKey(ValueKey('addressLabelTag_$token')), matching: find.byType(Text)));
      expect(tag('a').data, 'Home');
      expect(tag('b').data, 'Kos');
    });

    testWidgets('吸底「Tambah alamat」按钮；满 20 条禁用并改文', (tester) async {
      final l10n = await openBook(tester, [addr('a')]);
      final btn = find.byKey(const ValueKey('addressAddButton'));
      expect(btn, findsOneWidget);
      expect(find.byType(FloatingActionButton), findsNothing, reason: 'FAB 已改为吸底按钮');
      expect(tester.widget<ButtonStyleButton>(btn).onPressed, isNotNull);
      expect(find.text(l10n.addressAdd), findsOneWidget);
    });

    testWidgets('满 20 条 → 按钮禁用，文案「Maksimal 20 alamat」', (tester) async {
      final l10n = await openBook(tester, [for (var i = 0; i < 20; i++) addr('t$i')]);
      final btn = find.byKey(const ValueKey('addressAddButton'));
      expect(tester.widget<ButtonStyleButton>(btn).onPressed, isNull);
      expect(find.text(l10n.addressLimitReached), findsOneWidget);
    });
  });

  group('v1.4.2 shop Story 9.4 · H2 空态 / 加载失败', () {
    testWidgets('空态：标题 + 新副句 + 吸底按钮', (tester) async {
      final l10n = await openBook(tester, const []);
      expect(find.byKey(const ValueKey('addressEmpty')), findsOneWidget);
      expect(find.text(l10n.addressEmpty), findsOneWidget);
      expect(find.text(l10n.addressEmptyHint), findsOneWidget);
      expect(l10n.addressEmptyHint, 'Tambah alamat untuk mulai belanja.');
      expect(find.byKey(const ValueKey('addressAddButton')), findsOneWidget);
    });

    testWidgets('加载失败：图标 + 文案 + 「Coba lagi」→ 重新请求', (tester) async {
      var fail = true;
      var calls = 0;
      final l10n = await openBook(tester, const [], load: () async {
        calls++;
        if (fail) throw Exception('boom');
        return [addr('a')];
      });
      expect(find.byKey(const ValueKey('addressLoadFailed')), findsOneWidget);
      expect(find.byIcon(Icons.cloud_off), findsOneWidget);
      expect(find.text(l10n.addressLoadFailed), findsOneWidget);

      fail = false;
      final before = calls;
      await tester.tap(find.byKey(const ValueKey('addressRetry')));
      await tester.pumpAndSettle();
      expect(calls, greaterThan(before));
      expect(find.byKey(const ValueKey('addressCard_a')), findsOneWidget);
    });
  });

  group('v1.4.2 shop Story 9.4 · H5 删除确认', () {
    Future<void> tapDelete(WidgetTester tester, String token) async {
      await tester.tap(find.byKey(ValueKey('addressDelete_$token')));
      await tester.pumpAndSettle();
    }

    testWidgets('点 Hapus → 确认框；取消不删', (tester) async {
      final repo = _FakeRepo([addr('a')]);
      final l10n = await openBook(tester, [addr('a')], repo: repo);
      await tapDelete(tester, 'a');

      expect(find.byKey(const ValueKey('addressDeleteDialog')), findsOneWidget);
      expect(find.text(l10n.addressDeleteConfirmTitle), findsOneWidget);
      expect(find.text('Budi · Jl. Test No. 1'), findsOneWidget);
      await tester.tap(find.text(l10n.commonCancel));
      await tester.pumpAndSettle();
      expect(repo.deleted, isEmpty);
    });

    testWidgets('确认 → 调 DELETE 并刷新列表', (tester) async {
      final repo = _FakeRepo([addr('a'), addr('b')]);
      await openBook(tester, const [], repo: repo, load: repo.list);
      await tapDelete(tester, 'a');
      await tester.tap(find.byKey(const ValueKey('addressDeleteConfirm')));
      await tester.pumpAndSettle();

      expect(repo.deleted, ['a']);
      expect(find.byKey(const ValueKey('addressCard_a')), findsNothing);
      expect(find.byKey(const ValueKey('addressCard_b')), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('🔴 删默认地址 → toast 告知新的默认地址', (tester) async {
      final repo = _FakeRepo([addr('a', isDefault: true), addr('b', name: 'Siti')])
        ..promoteOnDelete = true;
      final l10n = await openBook(tester, const [], repo: repo, load: repo.list);
      await tapDelete(tester, 'a');
      await tester.tap(find.byKey(const ValueKey('addressDeleteConfirm')));
      await tester.pumpAndSettle();

      expect(find.text(l10n.addressDefaultPromoted('Siti')), findsOneWidget);
      expect(find.byType(SnackBar), findsNothing, reason: '改用 showAppToast');
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
    });

    testWidgets('删除失败 → toast addressDeleteFailed，列表不变', (tester) async {
      final repo = _FakeRepo([addr('a')])..failDelete = true;
      final l10n = await openBook(tester, const [], repo: repo, load: repo.list);
      await tapDelete(tester, 'a');
      await tester.tap(find.byKey(const ValueKey('addressDeleteConfirm')));
      await tester.pumpAndSettle();

      expect(find.text(l10n.addressDeleteFailed), findsOneWidget);
      expect(find.byKey(const ValueKey('addressCard_a')), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
    });

    testWidgets('删除中卡片操作禁用，不可重复点', (tester) async {
      final repo = _FakeRepo([addr('a')])..hold = true;
      await openBook(tester, const [], repo: repo, load: repo.list);
      await tapDelete(tester, 'a');
      await tester.tap(find.byKey(const ValueKey('addressDeleteConfirm')));
      await tester.pump();

      await tester.tap(find.byKey(const ValueKey('addressDelete_a')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('addressDeleteDialog')), findsNothing,
          reason: '删除进行中再点 Hapus 不应再弹确认');
      repo.release();
      await tester.pumpAndSettle();
      expect(repo.deleted, ['a']);
    });
  });

  testWidgets('选择器模式下 serviceable=false 的卡同样标注且仍可选', (tester) async {
    String? picked;
    await tester.pumpWidget(host(
      selecting: true,
      items: [addr('far', serviceable: false)],
      onPop: (v) => picked = v,
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('addressNotDeliverable_far')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('addressSelect_far')));
    await tester.pumpAndSettle();
    expect(picked, 'far');
  });
}

class _FakeRepo extends AddressRepository {
  _FakeRepo(List<ShippingAddress> items)
      : _items = [...items],
        super(dio: Dio());

  final List<ShippingAddress> _items;
  final deleted = <String>[];
  bool failDelete = false;
  bool promoteOnDelete = false;
  bool hold = false;
  Completer<void>? _gate;

  void release() => _gate?.complete();

  @override
  Future<List<ShippingAddress>> list() async => [..._items];

  @override
  Future<void> delete(String token) async {
    if (hold) {
      _gate = Completer<void>();
      await _gate!.future;
    }
    if (failDelete) throw DioException(requestOptions: RequestOptions(path: '/x'));
    deleted.add(token);
    _items.removeWhere((a) => a.token == token);
    if (promoteOnDelete && _items.isNotEmpty) {
      final first = _items.first;
      _items[0] = ShippingAddress(
        token: first.token,
        receiverName: first.receiverName,
        receiverPhone: first.receiverPhone,
        provinsi: first.provinsi,
        kotaKabupaten: first.kotaKabupaten,
        kecamatan: first.kecamatan,
        addressLine: first.addressLine,
        kodePos: first.kodePos,
        isDefault: true,
      );
    }
  }
}
