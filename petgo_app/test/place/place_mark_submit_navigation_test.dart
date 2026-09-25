import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/media/media_scope.dart';
import 'package:tailtopia/features/media/data/oss_uploader.dart';
import 'package:tailtopia/features/media/domain/media_upload_use_case.dart';
import 'package:tailtopia/features/place/data/location_service.dart';
import 'package:tailtopia/features/place/data/place_repository.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';
import 'package:tailtopia/features/place/presentation/place_detail_page.dart';
import 'package:tailtopia/features/place/presentation/place_mark_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// bug 514 · L0：标记场所提交成功后**进入新场所详情页**（UI 稿），而不是退回列表。
///
/// 守三件事：① 落到 `/places/{新 token}`；② 表单页被**替换**掉（返回即回列表，
/// 不会回到一张已提交过的表单）；③ 成功 toast 仍在。
class _GrantedGateway implements LocationGateway {
  @override
  Future<LocationPermissionOutcome> status() async => LocationPermissionOutcome.granted;

  @override
  Future<LocationPermissionOutcome> request() async => LocationPermissionOutcome.granted;

  @override
  Future<DeviceCoordinates?> currentCoordinates() async =>
      const DeviceCoordinates(latitude: -6.2, longitude: 106.8);

  @override
  Future<bool> openSettings() async => true;
}

class _Repo implements PlaceRepository {
  int creates = 0;

  @override
  Future<String> createPlace({
    required String name,
    required PlaceType type,
    required List<PlaceTag> tags,
    required double latitude,
    required double longitude,
    required String addressText,
    required List<String> photoUrls,
    String? description,
    String? idempotencyKey,
  }) async {
    creates++;
    return 'newTok';
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Media implements MediaUploadUseCase {
  @override
  Future<List<Uint8List>> pickMultiAndProcess({required int limit, BuildContext? context}) async =>
      [Uint8List.fromList(const [1, 2, 3])];

  @override
  Future<OssUploadResult> uploadBytes({required MediaScope scope, required Uint8List bytes}) async =>
      // asset: 走 Image.asset，测试里不发网络请求（Image.network 在测试里恒 400）。
      const OssUploadResult(objectKey: 'k', publicUrl: 'asset:assets/brand/app_icon_padded.png');

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  testWidgets('🔴 提交成功 → pushReplacement 到新场所详情页；返回回到列表', (tester) async {
    final repo = _Repo();
    // bug 20260922-528：E-4 place_created + 详情入口 from=created。
    final captured = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => captured.add((e, p));
    addTearDown(() => Analytics.debugCaptureSink = null);
    String? detailFrom;
    final router = GoRouter(
      initialLocation: '/places',
      routes: [
        GoRoute(
          path: '/places',
          builder: (c, s) => Scaffold(
            body: Center(
              child: TextButton(
                onPressed: () => c.push(PlaceMarkPage.routePath),
                child: const Text('LIST'),
              ),
            ),
          ),
        ),
        GoRoute(path: PlaceMarkPage.routePath, builder: (c, s) => const PlaceMarkPage()),
        // 详情页用桩：本用例只关心「落到了哪个 token」，不关心详情渲染。
        GoRoute(
          path: PlaceDetailPage.routePattern,
          builder: (c, s) {
            detailFrom = s.uri.queryParameters['from'];
            return Scaffold(
              appBar: AppBar(),
              body: Text('DETAIL ${s.pathParameters['token']}'),
            );
          },
        ),
      ],
    );
    addTearDown(router.dispose);

    await tester.pumpWidget(ProviderScope(
      overrides: [
        locationGatewayProvider.overrideWithValue(_GrantedGateway()),
        placeRepositoryProvider.overrideWithValue(repo),
        mediaUploadUseCaseProvider.overrideWithValue(_Media()),
      ],
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
      ),
    ));
    await tester.pump();

    await tester.tap(find.text('LIST'));
    await tester.pumpAndSettle();
    expect(find.byType(PlaceMarkPage), findsOneWidget);
    final l10n = AppLocalizations.of(tester.element(find.byType(PlaceMarkPage)));

    // 填满必填项：名称 / 类型 / 标签 / 地址 / 照片（位置由已授权定位灌入）。
    await tester.enterText(
        find.widgetWithText(TextField, l10n.placeMarkNameHint), 'Kopi Anjing');
    await tester.tap(find.text(PlaceType.cafe.labelFor(l10n)));
    await tester.pump();
    await tester.ensureVisible(find.text(PlaceTag.petMenu.labelFor(l10n)));
    await tester.tap(find.text(PlaceTag.petMenu.labelFor(l10n)));
    await tester.pump();
    final address = find.widgetWithText(TextField, l10n.placeMarkAddressHint);
    await tester.ensureVisible(address);
    await tester.enterText(address, 'Jl. Sudirman 1');
    await tester.ensureVisible(find.byKey(const ValueKey('placeMarkAddPhoto')));
    await tester.tap(find.byKey(const ValueKey('placeMarkAddPhoto')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    final submit = find.byKey(const ValueKey('placeMarkSubmit'));
    expect(tester.widget<TextButton>(submit).onPressed, isNotNull,
        reason: '必填都填了，保存应可点');
    await tester.tap(submit);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text(l10n.placeMarkSuccess), findsOneWidget, reason: '成功 toast 保留');
    // 等转场动画走完（toast 2.6s 后自行消失，不影响这里）。
    await tester.pump(const Duration(seconds: 1));

    expect(repo.creates, 1);
    expect(find.text('DETAIL newTok'), findsOneWidget, reason: '🔴 成功后应进入新场所详情页');
    expect(find.byType(PlaceMarkPage), findsNothing, reason: '表单页应被替换掉');
    expect(detailFrom, kPlaceDetailFromCreated, reason: '详情页的 place_detail_viewed 靠它区分入口');
    // 🔴 只有类型与数量 —— 场所名 / 地址 / 坐标一律不进埋点。
    expect(captured.where((e) => e.$1 == 'place_created').map((e) => e.$2), [
      {'type': PlaceType.cafe.api, 'tag_count': 1, 'photo_count': 1},
    ]);

    // 返回 → 回到列表，而不是回到已提交的表单。
    router.pop();
    await tester.pump();
    await tester.pump(const Duration(seconds: 3));
    expect(find.text('LIST'), findsOneWidget);
    expect(find.byType(PlaceMarkPage), findsNothing);
  });
}

extension on PlaceType {
  String labelFor(AppLocalizations l10n) => switch (this) {
        PlaceType.cafe => l10n.placeTypeCafe,
        _ => throw UnimplementedError(),
      };
}

extension on PlaceTag {
  String labelFor(AppLocalizations l10n) => switch (this) {
        PlaceTag.petMenu => l10n.placeTagPetMenu,
        _ => throw UnimplementedError(),
      };
}
