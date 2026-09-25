import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/media/media_scope.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/media/data/oss_uploader.dart';
import 'package:tailtopia/features/media/domain/media_upload_use_case.dart';
import 'package:tailtopia/features/place/data/location_service.dart';
import 'package:tailtopia/features/place/data/place_repository.dart';
import 'package:tailtopia/features/place/domain/place_comment.dart';
import 'package:tailtopia/features/place/domain/place_detail.dart';
import 'package:tailtopia/features/place/presentation/place_detail_page.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// bug 20260922-528 · L0：场所页五个事件（PRD b1 §3 的 E-4/5/6/15）真的接上了线。
///
/// `place_created` 在 `place_mark_submit_navigation_test.dart` 里随提交流程一起断言。
/// 🔴 每条都断言**属性里只有 token / 类型 / 数量** —— 场所名、地址、坐标一律不许出现
/// （architecture-b1 §4.2；埋点层的黑名单会把整条丢掉，传了也是空数据）。
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
  int comments = 0;
  int contributions = 0;

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
  Future<PlaceComment> createComment(String token, String body,
      {PlaceCommentAttitude? attitude}) async {
    comments++;
    return PlaceComment.fromJson({'id': 1, 'authorId': 5, 'body': body, 'mine': true});
  }

  @override
  Future<void> contributePhotos(String token, List<String> photoUrls) async {
    contributions++;
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
      const OssUploadResult(objectKey: 'k', publicUrl: 'asset:assets/brand/app_icon_padded.png');

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, role: 'USER');
}

typedef _Event = (String, Map<String, Object>?);

void main() {
  late List<_Event> captured;
  late _Repo repo;
  late int shares;

  setUp(() {
    captured = [];
    repo = _Repo();
    shares = 0;
    Analytics.debugCaptureSink = (e, p) => captured.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Iterable<Map<String, Object>?> props(String event) =>
      captured.where((e) => e.$1 == event).map((e) => e.$2);

  Future<void> pump(WidgetTester tester, {String? from}) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        locationGatewayProvider.overrideWithValue(_DeniedGateway()),
        placeRepositoryProvider.overrideWithValue(repo),
        mediaUploadUseCaseProvider.overrideWithValue(_Media()),
        authControllerProvider.overrideWith(_Auth.new),
        shareServiceProvider.overrideWithValue(
            (String text, {Rect? sharePositionOrigin}) async => shares++),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
        home: PlaceDetailPage(token: 'tok', analyticsFrom: from),
      ),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    await tester.pump(const Duration(milliseconds: 100));
  }

  group('E-5 place_detail_viewed', () {
    testWidgets('进入详情报且只报一次（数据到达多次重建也不重复）', (tester) async {
      await pump(tester, from: kPlaceDetailFromList);
      await tester.pump(const Duration(milliseconds: 300));

      expect(props('place_detail_viewed'), [
        {'place_id': 'tok', 'from': 'list'},
      ]);
    });

    testWidgets('三个入口的 from 原样透传', (tester) async {
      for (final from in [kPlaceDetailFromCreated, kPlaceDetailFromShare]) {
        captured.clear();
        // 先卸掉上一页：同一树位置上 initState 不会再走，等于没重新进页。
        await tester.pumpWidget(const SizedBox());
        await pump(tester, from: from);
        expect(props('place_detail_viewed').single!['from'], from);
      }
    });

    testWidgets('缺省 / 认不出的 from → other，不丢这次浏览', (tester) async {
      await pump(tester);
      expect(props('place_detail_viewed').single!['from'], kPlaceDetailFromOther);

      captured.clear();
      await tester.pumpWidget(const SizedBox());
      await pump(tester, from: 'whatever');
      expect(props('place_detail_viewed').single!['from'], kPlaceDetailFromOther);
    });

    test('路由拼接：带 from 走 query，不带不拼', () {
      expect(PlaceDetailPage.routeFor('t'), '/places/t');
      expect(PlaceDetailPage.routeFor('t', from: kPlaceDetailFromList), '/places/t?from=list');
    });

    test('分享深链落地带 from=share', () {
      expect(deepLinkToLocation(Uri.parse('tailtopia://place/abc')),
          PlaceDetailPage.routeFor('abc', from: kPlaceDetailFromShare));
    });
  });

  testWidgets('E-15 place_shared：调起分享后报，只带 token', (tester) async {
    // 高屏：默认 800x600 下分享键被吸底输入条压住，点不到。
    tester.view.physicalSize = const Size(800, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await pump(tester, from: kPlaceDetailFromList);
    final share = find.byKey(const ValueKey('placeDetailShare'));
    await tester.ensureVisible(share);
    await tester.tap(share);
    await tester.pump();

    expect(shares, 1);
    expect(props('place_shared'), [
      {'place_id': 'tok'},
    ]);
  });

  testWidgets('E-6 place_photo_added：补充照片提交成功后报一条', (tester) async {
    await pump(tester, from: kPlaceDetailFromList);
    await tester.tap(find.byIcon(Icons.add_a_photo_outlined));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(repo.contributions, 1);
    expect(props('place_photo_added'), [
      {'place_id': 'tok'},
    ]);
    // 让 toast 计时器走完。
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('E-6 place_comment_posted：评论发出后报，不带正文 / 态度', (tester) async {
    await pump(tester, from: kPlaceDetailFromList);
    await tester.tap(find.byKey(const ValueKey('placeCommentInput')));
    await tester.pump();
    await tester.enterText(find.byKey(const ValueKey('placeCommentInput')), 'bagus');
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('placeCommentSend')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(repo.comments, 1);
    expect(props('place_comment_posted'), [
      {'place_id': 'tok'},
    ]);
  });
}
