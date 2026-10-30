import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:tailtopia/features/merchant_binding/data/merchant_binding_repository.dart';
import 'package:tailtopia/features/merchant_binding/presentation/merchant_code_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.1 门店邀请码页（定稿 `tailtopia-design/v1.4.1/门店邀请码`）：读取 / 填码 / 确认 / 成功 / 已绑定 / 各失败态。
class _FakeRepo implements MerchantBindingRepository {
  _FakeRepo({this.initial = MerchantBinding.none, this.failCurrent = false, this.bindError});

  MerchantBinding initial;
  bool failCurrent;
  MerchantBindError? bindError;
  final List<String> boundCodes = [];

  @override
  Future<MerchantBinding> current() async {
    if (failCurrent) throw Exception('boom');
    return initial;
  }

  @override
  Future<MerchantBinding> bind(String code) async {
    boundCodes.add(code);
    if (bindError != null) throw MerchantBindException(bindError!);
    initial = MerchantBinding(bound: true, inviteCode: code, boundAt: DateTime.utc(2026, 10, 24, 3));
    return initial;
  }
}

Future<void> _pump(WidgetTester tester, _FakeRepo repo) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [merchantBindingRepositoryProvider.overrideWithValue(repo)],
    child: const MaterialApp(
      locale: Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: MerchantCodePage(),
    ),
  ));
  await tester.pumpAndSettle();
}

FilledButton _submit(WidgetTester tester) =>
    tester.widget<FilledButton>(find.byKey(const ValueKey('merchantCodeSubmit')));

void main() {
  setUpAll(() async => initializeDateFormatting('id'));

  testWidgets('未绑定：满 6 位才可提交；粘贴带空格的码被滤成纯数字；确认后显示成功页', (tester) async {
    final repo = _FakeRepo();
    await _pump(tester, repo);

    expect(find.text('Satu akun hanya bisa terhubung ke 1 toko dan tidak bisa diganti.'), findsOneWidget);
    expect(_submit(tester).onPressed, isNull);

    await tester.enterText(find.byKey(const ValueKey('merchantCodeInput')), '000 035');
    await tester.pump();
    expect(_submit(tester).onPressed, isNotNull);

    await tester.tap(find.byKey(const ValueKey('merchantCodeSubmit')));
    await tester.pumpAndSettle();
    expect(find.text('Hubungkan dengan toko ini?'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('merchantCodeConfirm')));
    await tester.pumpAndSettle();

    expect(repo.boundCodes, ['000035']);
    expect(find.byKey(const ValueKey('merchantCodeSuccess')), findsOneWidget);
    expect(find.text('000035'), findsOneWidget);
  });

  testWidgets('码无效 → 输入框下红字，改动输入即清除', (tester) async {
    final repo = _FakeRepo(bindError: MerchantBindError.invalidCode);
    await _pump(tester, repo);
    await tester.enterText(find.byKey(const ValueKey('merchantCodeInput')), '123456');
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('merchantCodeSubmit')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('merchantCodeConfirm')));
    await tester.pumpAndSettle();

    expect(find.text('Kode tidak valid. Cek lagi kode dari toko.'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('merchantCodeInput')), '12345');
    await tester.pump();
    expect(find.byKey(const ValueKey('merchantCodeError')), findsNothing);
  });

  testWidgets('已绑定过（409）→ 锁定输入，点 Lihat 进只读页', (tester) async {
    final repo = _FakeRepo(bindError: MerchantBindError.alreadyBound);
    await _pump(tester, repo);
    await tester.enterText(find.byKey(const ValueKey('merchantCodeInput')), '000035');
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('merchantCodeSubmit')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('merchantCodeConfirm')));
    await tester.pumpAndSettle();

    expect(find.textContaining('Akunmu sudah terhubung dengan toko.'), findsOneWidget);
    expect(_submit(tester).onPressed, isNull);

    repo.initial = MerchantBinding(bound: true, inviteCode: '000777', boundAt: DateTime.utc(2026, 10, 1));
    await tester.tap(find.byKey(const ValueKey('merchantCodeViewBound')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('merchantCodeBound')), findsOneWidget);
    expect(find.text('000777'), findsOneWidget);
  });

  testWidgets('已绑定：只显示邀请码与绑定日期（WIB），没有输入框', (tester) async {
    // 2026-10-23 20:00 UTC = 24 Okt WIB
    final repo = _FakeRepo(
        initial: MerchantBinding(bound: true, inviteCode: '000035', boundAt: DateTime.utc(2026, 10, 23, 20)));
    await _pump(tester, repo);
    expect(find.byKey(const ValueKey('merchantCodeInput')), findsNothing);
    expect(find.text('000035'), findsOneWidget);
    expect(find.text('Terhubung sejak 24 Okt 2026'), findsOneWidget);
    expect(find.text('Satu akun hanya bisa terhubung ke 1 toko.'), findsOneWidget);
  });

  testWidgets('读取失败 → 重试后恢复', (tester) async {
    final repo = _FakeRepo(failCurrent: true);
    await _pump(tester, repo);
    expect(find.byKey(const ValueKey('merchantCodeRetry')), findsOneWidget);
    repo.failCurrent = false;
    await tester.tap(find.byKey(const ValueKey('merchantCodeRetry')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('merchantCodeInput')), findsOneWidget);
  });
}
