import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/place/data/location_service.dart';
import 'package:tailtopia/features/place/data/place_repository.dart';
import 'package:tailtopia/features/place/domain/place_comment.dart';
import 'package:tailtopia/features/place/domain/place_detail.dart';
import 'package:tailtopia/features/place/presentation/place_detail_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 ④ Story 2.3 · AC6：场所详情顶栏收藏按钮（「⋯」左侧、条件同「⋯」）。
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
  _Repo({this.gone = false, this.collected = false});

  final bool gone;
  final bool collected;

  @override
  Future<PlaceDetail> fetchDetail(String token, {double? lat, double? lng}) async {
    if (gone) {
      final req = RequestOptions(path: '/places/$token');
      throw DioException(requestOptions: req, response: Response(requestOptions: req, statusCode: 404));
    }
    return PlaceDetail.fromJson({
      'token': token,
      'name': 'Kopi',
      'tags': const [],
      'photos': const [],
      'addressText': 'Jl. X',
      'latitude': -6.2,
      'longitude': 106.8,
      'markedBy': const {'userId': 1},
      'commentCount': 0,
      'recommendCount': 0,
      'notRecommendCount': 0,
      'photoSlotsRemaining': 9,
      'collected': collected,
    });
  }

  @override
  Future<PlaceCommentPage> fetchComments(String token, {String? cursor}) async => PlaceCommentPage.empty;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _TestAuthController extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, role: 'USER');
}

Future<void> _pump(WidgetTester tester, _Repo repo) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      locationGatewayProvider.overrideWithValue(_DeniedGateway()),
      placeRepositoryProvider.overrideWithValue(repo),
      authControllerProvider.overrideWith(_TestAuthController.new),
    ],
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('id'),
      home: const PlaceDetailPage(token: 'tok'),
    ),
  ));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 100));
  await tester.pump(const Duration(milliseconds: 100));
}

void main() {
  testWidgets('成功态：收藏按钮在「⋯」左侧；collected=true 初始实心紫', (tester) async {
    await _pump(tester, _Repo(collected: true));
    final collect = find.byKey(const ValueKey('placeDetailCollect'));
    final more = find.byKey(const ValueKey('placeDetailMore'));
    expect(collect, findsOneWidget);
    expect(more, findsOneWidget);
    expect(tester.getCenter(collect).dx, lessThan(tester.getCenter(more).dx));
    final icon = tester.widget<Icon>(find.descendant(of: collect, matching: find.byType(Icon)));
    expect(icon.icon, Icons.bookmark_rounded);
    expect(icon.color, AppColors.mint);
  });

  testWidgets('404 空态：既无「⋯」也无收藏', (tester) async {
    await _pump(tester, _Repo(gone: true));
    expect(find.byKey(const ValueKey('placeDetailCollect')), findsNothing);
    expect(find.byKey(const ValueKey('placeDetailMore')), findsNothing);
  });

  // V1.4.0 push-collect Story 3.1 · AC5.5：② 3.3 打卡按钮（逐宠物入参）与 ④ 顶栏收藏合并后并存。
  testWidgets('3.1 AC5.5：顶栏收藏 + 正文打卡按钮同屏', (tester) async {
    await _pump(tester, _Repo());
    expect(find.descendant(of: find.byType(AppBar), matching: find.byKey(const ValueKey('placeDetailCollect'))),
        findsOneWidget);
    expect(find.byKey(const ValueKey('placeCheckinButton')), findsOneWidget);
  });
}
