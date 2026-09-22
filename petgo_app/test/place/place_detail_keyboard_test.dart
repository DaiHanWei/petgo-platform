import 'dart:io';

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

/// bug 512 · L0：场所详情的评论输入条必须随键盘上移（CLAUDE.md 键盘避让标准「底部贴附输入栏」）。
///
/// 原先输入条挂在 `Scaffold.bottomNavigationBar` —— `resizeToAvoidBottomInset` 只压缩 body，
/// 底部导航位恒贴屏幕底，键盘一弹就被整条盖住。
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
        'photos': const [],
        'addressText': 'Jl. X',
        'latitude': -6.2,
        'longitude': 106.8,
        'markedBy': const {'userId': 1},
        'commentCount': 0,
        'recommendCount': 0,
        'notRecommendCount': 0,
        'photoSlotsRemaining': 9,
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
  const screen = Size(400, 800);
  const kb = 300.0;

  testWidgets('🔴 键盘弹出时评论输入框整条在键盘上方', (tester) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = screen;
    addTearDown(tester.view.reset);

    await _pump(tester);
    final input = find.byKey(const ValueKey('placeCommentInput'));
    expect(input, findsOneWidget);
    // 键盘收起：输入条沉底。
    expect(tester.getRect(input).bottom, greaterThan(screen.height - 120));

    // 模拟键盘弹出。
    tester.view.viewInsets = const FakeViewPadding(bottom: kb);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    final rect = tester.getRect(input);
    expect(rect.bottom, lessThanOrEqualTo(screen.height - kb),
        reason: '🔴 输入框底边落在键盘区域内 = 被键盘挡住（bug 512）');
    // 发送键同理（与输入框同排，被挡等于发不出去）。
    expect(tester.getRect(find.byKey(const ValueKey('placeCommentSend'))).bottom,
        lessThanOrEqualTo(screen.height - kb));
    // 不双算：输入条应紧贴键盘上沿，而不是再飘高一个键盘高度。
    expect(rect.bottom, greaterThan(screen.height - kb - 120),
        reason: '输入条离键盘太远 = viewInsets 被补了两次');
  });

  test('输入条不再挂在 bottomNavigationBar（那一位不随键盘避让）', () {
    // 结构守卫：源码级防回退（注释行不算）。
    final code = File('lib/features/place/presentation/place_detail_page.dart')
        .readAsLinesSync()
        .where((l) => !l.trimLeft().startsWith('//'))
        .join('\n');
    expect(code.contains('bottomNavigationBar:'), isFalse);
  });
}
