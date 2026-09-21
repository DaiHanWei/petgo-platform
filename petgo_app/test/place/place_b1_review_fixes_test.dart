import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/place/data/location_service.dart';
import 'package:tailtopia/features/place/data/place_repository.dart';
import 'package:tailtopia/features/place/domain/place_comment.dart';
import 'package:tailtopia/features/place/domain/place_detail.dart';
import 'package:tailtopia/features/place/presentation/place_comments_controller.dart';
import 'package:tailtopia/features/place/presentation/place_location_controller.dart';

/// batch-b1 复审（2026-09-18）场所侧三条的回归：
/// - F3 剩余补图名额以服务端为准（本地看不到别人审核中的照片）；
/// - F5 评论「加载更多」连点不重复追加；
/// - F6 去系统设置开了定位回到 App，权限态要重读。
Map<String, dynamic> _detailJson({Object? slots, List<Map<String, dynamic>> photos = const []}) => {
      'token': 't',
      'name': 'Kopi',
      'tags': const [],
      'photos': photos,
      'addressText': 'Jl. X',
      'latitude': -6.2,
      'longitude': 106.8,
      'markedBy': const {'userId': 1},
      'commentCount': 0,
      'recommendCount': 0,
      'notRecommendCount': 0,
      'photoSlotsRemaining': ?slots,
    };

Map<String, dynamic> _photo(int id, String status) => {
      'id': id,
      'url': 'https://cdn/$id.jpg',
      'uploaderId': 1,
      'moderationStatus': status,
      'mine': true,
    };

class _CommentsRepo implements PlaceRepository {
  final pending = <Completer<PlaceCommentPage>>[];
  final cursors = <String?>[];

  @override
  Future<PlaceCommentPage> fetchComments(String token, {String? cursor}) {
    cursors.add(cursor);
    if (cursor == null) {
      return Future.value(PlaceCommentPage(
          items: [_comment(1)], hasMore: true, total: 2, nextCursor: 'c1'));
    }
    final c = Completer<PlaceCommentPage>();
    pending.add(c);
    return c.future;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

PlaceComment _comment(int id) => PlaceComment.fromJson({'id': id, 'authorId': 9, 'body': 'b$id'});

class _Gateway implements LocationGateway {
  LocationPermissionOutcome current = LocationPermissionOutcome.permanentlyDenied;
  int statusCalls = 0;

  @override
  Future<LocationPermissionOutcome> status() async {
    statusCalls++;
    return current;
  }

  @override
  Future<LocationPermissionOutcome> request() async => current;

  @override
  Future<DeviceCoordinates?> currentCoordinates() async =>
      const DeviceCoordinates(latitude: -6.2, longitude: 106.8);

  @override
  Future<bool> openSettings() async => true;
}

void main() {
  group('F3 剩余补图名额', () {
    test('以服务端下发为准（别人审核中的照片本地看不到）', () {
      final d = PlaceDetail.fromJson(_detailJson(slots: 0, photos: [_photo(1, 'VISIBLE')]));
      expect(d.photos, hasLength(1));
      expect(d.photoSlotsRemaining, 0, reason: '服务端说满了就是满了');
    });

    test('老后端不下发 → 按本地非拒绝照片估算（自己被拒的不占名额）', () {
      final d = PlaceDetail.fromJson(_detailJson(photos: [
        _photo(1, 'VISIBLE'),
        _photo(2, 'REJECTED'),
        _photo(3, 'UNDER_REVIEW'),
      ]));
      expect(d.photoSlotsRemaining, PlaceDetail.maxPhotos - 2);
    });
  });

  group('F5 评论「加载更多」', () {
    test('在途时再点 → 只发一次请求、只追加一次', () async {
      final repo = _CommentsRepo();
      final c = ProviderContainer(overrides: [placeRepositoryProvider.overrideWithValue(repo)]);
      addTearDown(c.dispose);
      final sub = c.listen(placeCommentsProvider('t'), (_, _) {});
      addTearDown(sub.close);
      await c.read(placeCommentsProvider('t').future);

      final n = c.read(placeCommentsProvider('t').notifier);
      final a = n.loadMore();
      final b = n.loadMore(); // 连点第二下
      expect(repo.pending, hasLength(1), reason: '同一个游标不许发两次');

      repo.pending.single.complete(
          PlaceCommentPage(items: [_comment(2)], hasMore: false, total: 2));
      await Future.wait([a, b]);

      expect(c.read(placeCommentsProvider('t')).value!.items.map((e) => e.id), [1, 2]);
    });

    test('等待期间列表被整体重拉 → 迟到的那一页丢掉，不拼到新列表上', () async {
      final repo = _CommentsRepo();
      final c = ProviderContainer(overrides: [placeRepositoryProvider.overrideWithValue(repo)]);
      addTearDown(c.dispose);
      final sub = c.listen(placeCommentsProvider('t'), (_, _) {});
      addTearDown(sub.close);
      await c.read(placeCommentsProvider('t').future);

      final n = c.read(placeCommentsProvider('t').notifier);
      final more = n.loadMore();
      await n.reload();
      repo.pending.single.complete(
          PlaceCommentPage(items: [_comment(2)], hasMore: false, total: 2));
      await more;

      expect(c.read(placeCommentsProvider('t')).value!.items.map((e) => e.id), [1]);
    });
  });

  group('F6 去设置开定位后回到 App', () {
    testWidgets('回前台 → 重读权限，提示条随之消失', (tester) async {
      final gateway = _Gateway();
      final c = ProviderContainer(overrides: [locationGatewayProvider.overrideWithValue(gateway)]);
      addTearDown(c.dispose);
      final sub = c.listen(placeLocationProvider, (_, _) {});
      addTearDown(sub.close);

      await tester.runAsync(() => c.read(placeLocationProvider.future));
      expect(c.read(placeLocationProvider).value!.mustGoToSettings, isTrue);

      await tester.runAsync(() => c.read(placeLocationProvider.notifier).openSettings());
      // 用户在系统设置里打开了权限，然后切回 App。
      gateway.current = LocationPermissionOutcome.granted;
      // 逐级切换（AppLifecycleListener 断言状态只能相邻迁移）：离开 → 回来。
      for (final st in const [
        AppLifecycleState.inactive,
        AppLifecycleState.hidden,
        AppLifecycleState.paused,
        AppLifecycleState.hidden,
        AppLifecycleState.inactive,
        AppLifecycleState.resumed,
      ]) {
        tester.binding.handleAppLifecycleStateChanged(st);
      }
      await tester.runAsync(() => c.read(placeLocationProvider.future));

      final s = c.read(placeLocationProvider).value!;
      expect(s.needsPermissionBanner, isFalse, reason: '不重读就会一直挂着「去设置」');
      expect(s.coordinates, isNotNull);
      // 让 Riverpod 的回收定时器走完，免得测试框架报「还有未决定时器」。
      await tester.pump(const Duration(seconds: 1));
    });
  });
}
