import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/place/data/location_service.dart';
import 'package:tailtopia/features/place/presentation/place_mark_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.0 batch-b1 Story 1.3 · UI 稿 A5/A8 还原度（L0）。
///
/// 守的是改版后**行为不变的那几条**：保存挪到顶栏后仍然「必填未满 → 灰」（AC2）、
/// 「提交后不可修改」提示条仍在（AC4）、字段错误仍是内联且只对碰过的字段显示（AC7）。
class _DeniedGateway implements LocationGateway {
  @override
  Future<LocationPermissionOutcome> status() async => LocationPermissionOutcome.denied;

  @override
  Future<LocationPermissionOutcome> request() async => LocationPermissionOutcome.denied;

  @override
  Future<DeviceCoordinates?> currentCoordinates() async => null;

  @override
  Future<bool> openSettings() async => true;
}

Future<AppLocalizations> _pump(WidgetTester tester) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [locationGatewayProvider.overrideWithValue(_DeniedGateway())],
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
      home: const PlaceMarkPage(),
    ),
  ));
  // 不用 pumpAndSettle：让定位 future 落定即可（表单里没有常驻动画，但别把用例绑在它上面）。
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 100));
  return AppLocalizations.of(tester.element(find.byType(PlaceMarkPage)));
}

void main() {
  testWidgets('AC2：空表单时顶栏「Simpan」是禁用的，且没有吸底大按钮', (tester) async {
    final l10n = await _pump(tester);

    final submit = find.byKey(const ValueKey('placeMarkSubmit'));
    expect(submit, findsOneWidget);
    // 在 AppBar 里（不是吸底栏）。
    expect(find.descendant(of: find.byType(AppBar), matching: submit), findsOneWidget);
    expect(tester.widget<TextButton>(submit).onPressed, isNull);
    expect(find.byType(FilledButton), findsNothing);

    // 左侧「Batal」。
    expect(find.descendant(of: find.byType(AppBar), matching: find.text(l10n.commonCancel)),
        findsOneWidget);
  });

  testWidgets('AC4：「提交后不可修改」提示条仍在表单最上方', (tester) async {
    final l10n = await _pump(tester);
    expect(find.text(l10n.placeMarkImmutableNotice), findsOneWidget);
  });

  testWidgets('A5：位置行排在文字地址之前，且整行就是地图选点入口', (tester) async {
    // 表单是懒构建的 ListView：位置字段有了「LOKASI」标签后，地址框落到默认 800 高视口之外
    // 就不会被构建。给足高度让整张表单都排出来，比较的才是真实的上下顺序。
    tester.view.physicalSize = const Size(1080, 4800);
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    await _pump(tester);
    final pick = find.byKey(const ValueKey('placeMarkPickOnMap'));
    expect(pick, findsOneWidget);
    expect(tester.widget(pick), isA<InkWell>());

    final addressField = find.byType(TextField).at(1);
    expect(tester.getTopLeft(pick).dy, lessThan(tester.getTopLeft(addressField).dy));
  });

  testWidgets('AC7/A8：名称碰过又清空 → 输入框红框 + 外置错误字（左缘对齐），Simpan 仍灰', (tester) async {
    final l10n = await _pump(tester);
    expect(find.text(l10n.placeMarkNameError), findsNothing); // 未碰过不提前指责

    final name = find.byType(TextField).first;
    await tester.enterText(name, 'x');
    await tester.enterText(name, '');
    await tester.pump();

    // 输入框自身进入错误态（红框）：decoration.error 非空 → InputDecorator 用 errorBorder。
    final decoration = tester.widget<TextField>(name).decoration!;
    expect(decoration.error, isNotNull);
    expect((decoration.errorBorder! as OutlineInputBorder).borderSide.color,
        AppColors.popRed);
    // 错误文字外置（UI 稿 A8）：只出现一次，且与输入框左边缘对齐（与 chip 组错误字同一左缘）。
    expect(find.text(l10n.placeMarkNameError), findsOneWidget);
    expect(tester.getTopLeft(find.text(l10n.placeMarkNameError)).dx,
        tester.getTopLeft(name).dx);
    expect(tester.getTopLeft(find.text(l10n.placeMarkNameError)).dy,
        greaterThan(tester.getBottomLeft(name).dy - 1));
    expect(
        tester.widget<TextButton>(find.byKey(const ValueKey('placeMarkSubmit'))).onPressed,
        isNull);
  });
}
