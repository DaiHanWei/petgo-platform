import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/place/data/location_service.dart';
import 'package:tailtopia/features/place/data/place_repository.dart';
import 'package:tailtopia/features/place/domain/place_list_filter.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';
import 'package:tailtopia/features/place/presentation/place_list_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.0 batch-b1 Story 1.11 · L0：场所列表类型 / 标签筛选（前端 AC7–AC11）。
///
/// 后端契约（与 petgo-backend `PlaceController.list` 对齐）：
/// `GET /api/v1/places?type=CAFE&type=PARK&tag=OUTDOOR_SEATING&tag=PET_MENU`
/// —— 可重复参数（**不是**逗号拼接），取值为后端枚举字面量（大小写敏感）。
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

class _Repo implements PlaceRepository {
  final calls = <PlaceListFilter>[];

  /// 有筛选时返回空（模拟「没有符合条件的场所」）；无筛选时返回一条。
  bool emptyWhenFiltered = true;

  @override
  Future<PlaceListResult> fetchPlaces(
      {double? lat, double? lng, PlaceListFilter filter = PlaceListFilter.none}) async {
    calls.add(filter);
    if (filter.isNotEmpty && emptyWhenFiltered) {
      return const PlaceListResult(items: [], sortMode: PlaceSortMode.recent);
    }
    return PlaceListResult(
      items: [
        PlaceSummary.fromJson(const {
          'token': 'tok1',
          'name': 'Kopi Kayu',
          'type': 'CAFE',
          'tags': ['PET_MENU'],
          'photoCount': 0,
          'commentCount': 0,
          'recommendCount': 0,
          'notRecommendCount': 0,
        }),
      ],
      sortMode: PlaceSortMode.recent,
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

Future<AppLocalizations> _pump(WidgetTester tester, _Repo repo) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      locationGatewayProvider.overrideWithValue(_DeniedGateway()),
      placeRepositoryProvider.overrideWithValue(repo),
    ],
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('id'),
      home: const PlaceListPage(),
    ),
  ));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 100));
  return AppLocalizations.of(tester.element(find.byType(PlaceListPage)));
}

Future<void> _settle(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 500));
}

Future<void> _pickTypes(WidgetTester tester, List<PlaceType> types) async {
  await tester.tap(find.byKey(const ValueKey('placeFilterTypeChip')));
  await _settle(tester);
  for (final t in types) {
    await tester.tap(find.byKey(ValueKey('placeFilterOption-$t')));
    await tester.pump();
  }
  await tester.tap(find.byKey(const ValueKey('placeFilterApply')));
  await _settle(tester);
}

void main() {
  testWidgets('AC7/AC10：无筛选时两个 chip 为未选态，请求不带任何筛选参数', (tester) async {
    final repo = _Repo();
    final l10n = await _pump(tester, repo);

    expect(find.text(l10n.placeFilterType), findsOneWidget); // Jenis
    expect(find.text(l10n.placeFilterTag), findsOneWidget); // Tag
    expect(repo.calls, [PlaceListFilter.none], reason: '无筛选请求须与 1.11 之前一字不差');
    expect(find.text('Kopi Kayu'), findsOneWidget);
  });

  testWidgets('AC8：类型弹层列出全部 7 类、标签弹层列出全部 6 个', (tester) async {
    final repo = _Repo();
    await _pump(tester, repo);

    await tester.tap(find.byKey(const ValueKey('placeFilterTypeChip')));
    await _settle(tester);
    for (final t in PlaceType.values) {
      expect(find.byKey(ValueKey('placeFilterOption-$t')), findsOneWidget);
    }
    await tester.tapAt(const Offset(10, 10)); // 点遮罩关掉 = 不改
    await _settle(tester);
    expect(repo.calls, hasLength(1), reason: '关掉弹层不应触发新请求');

    await tester.tap(find.byKey(const ValueKey('placeFilterTagChip')));
    await _settle(tester);
    for (final t in PlaceTag.values) {
      expect(find.byKey(ValueKey('placeFilterOption-$t')), findsOneWidget);
    }
  });

  testWidgets('AC9/AC10：应用后 chip 显示数量，请求带上所选类型', (tester) async {
    final repo = _Repo()..emptyWhenFiltered = false;
    final l10n = await _pump(tester, repo);

    await _pickTypes(tester, [PlaceType.cafe, PlaceType.park]);

    expect(find.text('${l10n.placeFilterType} · 2'), findsOneWidget);
    expect(repo.calls.last,
        const PlaceListFilter(types: {PlaceType.cafe, PlaceType.park}));
    expect(repo.calls.last.typeParams, ['CAFE', 'PARK']);
    // 标签维度未动，仍是未选态。
    expect(find.text(l10n.placeFilterTag), findsOneWidget);
  });

  testWidgets('AC11：筛选后为空 → 独立空态 +「Hapus filter」，不显示「标记场所」引导', (tester) async {
    final repo = _Repo();
    final l10n = await _pump(tester, repo);

    await _pickTypes(tester, [PlaceType.hotel]);

    expect(find.byKey(const ValueKey('placeFilterEmpty')), findsOneWidget);
    expect(find.text(l10n.placeFilterEmptyTitle), findsOneWidget);
    expect(find.text(l10n.placeEmptyTitle), findsNothing,
        reason: '🔴「一个场所都没有」的引导语义不同，筛选空态不得复用');
    expect(find.byKey(const ValueKey('placeEmptyPin')), findsNothing);

    await tester.tap(find.text(l10n.placeFilterClearAll));
    await _settle(tester);

    expect(repo.calls.last, PlaceListFilter.none);
    expect(find.text('Kopi Kayu'), findsOneWidget);
    expect(find.text(l10n.placeFilterType), findsOneWidget, reason: 'chip 回到未选态');
  });

  testWidgets('AC8：Reset 清空该维度（只清这一维）', (tester) async {
    final repo = _Repo()..emptyWhenFiltered = false;
    final l10n = await _pump(tester, repo);

    await _pickTypes(tester, [PlaceType.cafe]);
    // 再选一个标签。
    await tester.tap(find.byKey(const ValueKey('placeFilterTagChip')));
    await _settle(tester);
    await tester.tap(find.byKey(ValueKey('placeFilterOption-${PlaceTag.petMenu}')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('placeFilterApply')));
    await _settle(tester);
    expect(repo.calls.last,
        const PlaceListFilter(types: {PlaceType.cafe}, tags: {PlaceTag.petMenu}));

    // 类型弹层里 Reset → 只清类型。
    await tester.tap(find.byKey(const ValueKey('placeFilterTypeChip')));
    await _settle(tester);
    await tester.tap(find.byKey(const ValueKey('placeFilterReset')));
    await _settle(tester);

    expect(repo.calls.last, const PlaceListFilter(tags: {PlaceTag.petMenu}));
    expect(find.text(l10n.placeFilterType), findsOneWidget);
    expect(find.text('${l10n.placeFilterTag} · 1'), findsOneWidget);
  });

  group('请求编码（与后端契约对齐）', () {
    Future<Uri> captureUri(PlaceListFilter filter, {double? lat, double? lng}) async {
      late Uri uri;
      final dio = Dio(BaseOptions(baseUrl: 'https://api.test'));
      dio.interceptors.add(InterceptorsWrapper(onRequest: (o, h) {
        uri = o.uri;
        h.resolve(Response(
            requestOptions: o,
            statusCode: 200,
            data: <String, dynamic>{'items': <dynamic>[], 'sortMode': 'recent'}));
      }));
      await PlaceRepository(dio: dio).fetchPlaces(lat: lat, lng: lng, filter: filter);
      return uri;
    }

    test('可重复参数（type=…&type=…），不是逗号拼接', () async {
      final uri = await captureUri(const PlaceListFilter(
        types: {PlaceType.park, PlaceType.cafe},
        tags: {PlaceTag.petMenu, PlaceTag.outdoorSeating},
      ));
      expect(uri.queryParametersAll['type'], ['CAFE', 'PARK']);
      expect(uri.queryParametersAll['tag'], ['OUTDOOR_SEATING', 'PET_MENU']);
      expect(uri.query.contains('%2C'), isFalse);
      expect(uri.query.contains(','), isFalse);
    });

    test('无筛选 → 不出现 type / tag 键（与现状一字不差）', () async {
      final uri = await captureUri(PlaceListFilter.none);
      expect(uri.query, isEmpty);
    });

    test('与坐标任意组合', () async {
      final uri = await captureUri(
          const PlaceListFilter(types: {PlaceType.petService}),
          lat: -6.2,
          lng: 106.8);
      expect(uri.queryParameters['lat'], '-6.2');
      expect(uri.queryParameters['lng'], '106.8');
      expect(uri.queryParametersAll['type'], ['PET_SERVICE']);
      expect(uri.queryParametersAll.containsKey('tag'), isFalse);
    });
  });
}
