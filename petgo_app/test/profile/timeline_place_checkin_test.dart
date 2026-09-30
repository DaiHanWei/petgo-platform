import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_scope.dart';
import 'package:tailtopia/features/profile/domain/calendar_month.dart';
import 'package:tailtopia/features/profile/domain/day_detail.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/presentation/day_detail_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.2 Story 1.6 · L0：Diary 打卡条目的模型、能力参数与某天详情渲染。
void main() {
  Map<String, dynamic> wire({Object? status = 'ACTIVE'}) => {
        'kind': 'PLACE_CHECKIN',
        'itemType': 'PLACE_CHECKIN_BANNER',
        'date': '2026-09-29T20:30:00Z',
        'eventDate': '2026-09-29',
        'checkinPlace': {'placeToken': 'p' * 32, 'name': 'Kopi Kucing', 'status': ?status},
      };

  group('模型（AC4.1）', () {
    test('解析 PLACE_CHECKIN_BANNER + checkinPlace（时间线 wire 键 placeToken）', () {
      final it = TimelineItem.fromJson(wire());
      expect(it.resolvedType, TimelineItemType.placeCheckinBanner);
      expect(it.kind, TimelineKind.placeCheckin);
      expect(it.checkinPlace?.token, 'p' * 32);
      expect(it.checkinPlace?.name, 'Kopi Kucing');
      expect(it.checkinPlace?.available, isTrue);
      expect(it.displayDate, DateTime.parse('2026-09-29'), reason: '用服务端给的 UTC 日，不拿 checked_at 算本地日');
    });

    test('UNAVAILABLE / 缺 status → 不可用（fail-closed）；缺 checkinPlace → null', () {
      expect(TimelineItem.fromJson(wire(status: 'UNAVAILABLE')).checkinPlace?.available, isFalse);
      expect(TimelineItem.fromJson(wire(status: null)).checkinPlace?.available, isFalse);
      expect(TimelineItem.fromJson(wire()..remove('checkinPlace')).checkinPlace, isNull);
    });

    test('日历 hasPlaceCheckin：缺键 → false', () {
      expect(CalendarDayCell.fromJson(const {'day': 3}).hasPlaceCheckin, isFalse);
      expect(CalendarDayCell.fromJson(const {'day': 3, 'hasPlaceCheckin': true}).hasPlaceCheckin, isTrue);
    });
  });

  group('能力参数 supports（AC4.6）', () {
    Future<List<RequestOptions>> capture(Future<void> Function(DioTimelineRepository r) call) async {
      final sent = <RequestOptions>[];
      final dio = Dio()
        ..interceptors.add(InterceptorsWrapper(onRequest: (o, h) {
          sent.add(o);
          final data = o.path.contains('calendar')
              ? {'year': 2026, 'month': 9, 'days': []}
              : o.path.contains('day')
                  ? {'date': '2026-09-29', 'items': []}
                  : {'items': [], 'hasMore': false};
          h.resolve(Response(requestOptions: o, statusCode: 200, data: data));
        }));
      await call(DioTimelineRepository(dio));
      return sent;
    }

    test('作者态三处都带 supports=place_checkin', () async {
      final sent = await capture((r) async {
        await r.getTimeline();
        await r.getCalendar(2026, 9);
        await r.getDay(DateTime(2026, 9, 29));
      });
      expect(sent, hasLength(3));
      for (final o in sent) {
        expect(o.queryParameters['supports'], kTimelineSupports, reason: o.path);
      }
      expect(kTimelineSupports, ['place_checkin']);
    });

    test('🔴 访客态（分享 token / 站内）一律不带 supports', () async {
      final sent = await capture((r) async {
        await r.getTimeline(scope: const ArchiveScope.visitor('T'));
        await r.getTimeline(scope: const ArchiveScope.inAppVisitor(9));
        await r.getCalendar(2026, 9, scope: const ArchiveScope.visitor('T'));
        await r.getDay(DateTime(2026, 9, 29), scope: const ArchiveScope.visitor('T'));
      });
      expect(sent, hasLength(4));
      for (final o in sent) {
        expect(o.queryParameters.containsKey('supports'), isFalse, reason: o.path);
      }
    });
  });

  group('某天详情（AC4.4）', () {
    Future<void> pump(WidgetTester tester, {String? token}) async {
      final day = DateTime(2026, 9, 29);
      final detail = DayDetail(date: day, items: [TimelineItem.fromJson(wire())]);
      final router = GoRouter(initialLocation: '/d', routes: [
        GoRoute(path: '/d', builder: (_, _) => DayDetailPage(date: day, token: token)),
        GoRoute(
            path: '/places/:token',
            builder: (_, s) => Text('place:${s.uri.queryParameters['from']}')),
      ]);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          dayDetailProvider.overrideWith((ref, d) async => detail),
          visitorDayDetailProvider.overrideWith((ref, a) async => detail),
        ],
        child: MaterialApp.router(
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          routerConfig: router,
        ),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('打卡条目渲染为打卡通栏（不回落照片卡）；点击进场所详情', (tester) async {
      await pump(tester);
      expect(find.byKey(const ValueKey('timelineCheckinBanner')), findsOneWidget);
      expect(find.byKey(const ValueKey('dayItem_null')), findsNothing, reason: '不得被当成 postId 为空的照片卡');
      await tester.tap(find.byKey(const ValueKey('timelineCheckinBanner')));
      await tester.pumpAndSettle();
      expect(find.text('place:diary'), findsOneWidget);
    });

    testWidgets('访客态（防御）：即便出现也不可点', (tester) async {
      await pump(tester, token: 'T');
      await tester.tap(find.byKey(const ValueKey('timelineCheckinBanner')));
      await tester.pumpAndSettle();
      expect(find.text('place:diary'), findsNothing);
    });
  });
}
