import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/place/data/location_service.dart';
import 'package:tailtopia/features/place/data/place_repository.dart';
import 'package:tailtopia/features/place/domain/place_comment.dart';
import 'package:tailtopia/features/place/domain/place_detail.dart';
import 'package:tailtopia/features/place/presentation/place_detail_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// bug 20260922-517 · L0：场所详情照片为可横滑的一行，每张右上角带「i/n」计数角标（UI 稿 A4）。
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
  @override
  Future<PlaceDetail> fetchDetail(String token, {double? lat, double? lng}) async =>
      PlaceDetail.fromJson({
        'token': token,
        'name': 'Kopi',
        'tags': const [],
        'photos': [
          for (var i = 1; i <= 3; i++)
            {'id': i, 'url': 'https://x.invalid/p$i.jpg', 'uploaderId': 9, 'uploaderNickname': 'Albar', 'moderationStatus': 'VISIBLE'},
        ],
        'addressText': 'Jl. X',
        'latitude': -6.2,
        'longitude': 106.8,
        'markedBy': const {'userId': 1},
        'commentCount': 0,
        'recommendCount': 0,
        'notRecommendCount': 0,
        'photoSlotsRemaining': 0,
      });

  @override
  Future<PlaceCommentPage> fetchComments(String token, {String? cursor}) async =>
      PlaceCommentPage.empty;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _TestAuthController extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, role: 'USER');
}

Future<void> _pump(WidgetTester tester) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      locationGatewayProvider.overrideWithValue(_DeniedGateway()),
      placeRepositoryProvider.overrideWithValue(_Repo()),
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
  testWidgets('照片横滑流 + 每张「i/n」计数角标', (tester) async {
    await _pump(tester);
    // 横向可滑动的一行（不是单张静态图）。
    final strip = find.byWidgetPredicate(
        (w) => w is ListView && w.scrollDirection == Axis.horizontal);
    expect(strip, findsOneWidget);
    // 首张可见即带「1/3」角标；滑动后后续角标可达。
    expect(find.text('1/3'), findsOneWidget);
    await tester.drag(strip, const Offset(-600, 0));
    await tester.pump();
    expect(find.text('3/3'), findsOneWidget);
  });
}
