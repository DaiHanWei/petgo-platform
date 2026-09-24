// bug 20260921-508：地图选点弹层在未授权定位时给「开启定位」提示条（仍可手动选点）。
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
// google_maps_flutter 的平台接口包是它的传递依赖；测试里要换掉平台实现，只能直接引用。
// ignore: depend_on_referenced_packages
import 'package:google_maps_flutter_platform_interface/google_maps_flutter_platform_interface.dart';
import 'package:tailtopia/features/place/data/location_service.dart';
import 'package:tailtopia/features/place/presentation/place_map_picker_sheet.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 测试环境没有原生地图：用一个只画占位的平台实现替身，GoogleMap 就能在 widget 测试里 build。
class _FakeMapsPlatform extends GoogleMapsFlutterPlatform {
  @override
  Future<void> init(int mapId) async {}
  @override
  Widget buildViewWithConfiguration(int creationId, PlatformViewCreatedCallback onPlatformViewCreated,
          {required MapWidgetConfiguration widgetConfiguration,
          MapConfiguration mapConfiguration = const MapConfiguration(),
          MapObjects mapObjects = const MapObjects()}) =>
      const ColoredBox(color: Color(0xFFEEEEEE));
  @override
  Stream<MarkerTapEvent> onMarkerTap({required int mapId}) => const Stream.empty();
  @override
  Stream<MarkerDragEndEvent> onMarkerDragEnd({required int mapId}) => const Stream.empty();
  @override
  Stream<MapTapEvent> onTap({required int mapId}) => const Stream.empty();
  @override
  void dispose({required int mapId}) {}
}

Widget _host(Future<DeviceCoordinates?> Function()? onEnable) => MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(body: PlaceMapPickerSheet(initial: null, onEnableLocation: onEnable)),
    );

void main() {
  setUpAll(() => GoogleMapsFlutterPlatform.instance = _FakeMapsPlatform());

  testWidgets('未授权：顶部出「开启定位」提示条；拿到定位后提示条消失、确认可点', (tester) async {
    var asked = 0;
    await tester.pumpWidget(_host(() async {
      asked++;
      return const DeviceCoordinates(latitude: -6.2, longitude: 106.8);
    }));
    await tester.pump();
    expect(find.byKey(const ValueKey('placeMapPickerLocationBanner')), findsOneWidget);
    final confirm = find.byType(FilledButton);
    expect(tester.widget<FilledButton>(confirm).onPressed, isNull,
        reason: '无定位且没动过针时不许直接确认（原有防呆不变）');

    await tester.tap(find.byKey(const ValueKey('placeMapPickerEnableLocation')));
    await tester.pumpAndSettle();
    expect(asked, 1);
    expect(find.byKey(const ValueKey('placeMapPickerLocationBanner')), findsNothing);
    expect(tester.widget<FilledButton>(confirm).onPressed, isNotNull);
  });

  testWidgets('仍拒绝（拿不到坐标）：提示条保留，照样可以手动选点', (tester) async {
    await tester.pumpWidget(_host(() async => null));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('placeMapPickerEnableLocation')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('placeMapPickerLocationBanner')), findsOneWidget);
  });

  testWidgets('已授权（调用方不给回调）：不出提示条', (tester) async {
    await tester.pumpWidget(_host(null));
    await tester.pump();
    expect(find.byKey(const ValueKey('placeMapPickerLocationBanner')), findsNothing);
  });
}
