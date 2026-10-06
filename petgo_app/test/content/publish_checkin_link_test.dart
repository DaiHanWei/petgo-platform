import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/publish_checkin_place.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.2 Story 1.5 · L0：打卡后顺手发帖 —— 请求体、控制器透传、发帖页场所条、详情模型。
void main() {
  group('请求体（AD-10）', () {
    Future<Map<String, dynamic>> bodyOf(String? token) async {
      late Map<String, dynamic> sent;
      final dio = Dio()
        ..interceptors.add(InterceptorsWrapper(onRequest: (o, h) {
          sent = Map<String, dynamic>.from(o.data as Map);
          h.resolve(Response(requestOptions: o, statusCode: 201, data: {'id': 1}));
        }));
      await DioContentRepository(dio).publish(
        type: ContentType.daily,
        text: 'hi',
        idempotencyKey: 'k',
        placeCheckinToken: token,
      );
      return sent;
    }

    test('带 token → 请求体有 placeCheckinToken', () async {
      expect((await bodyOf('t' * 32))['placeCheckinToken'], 't' * 32);
    });

    test('🔴 无 token → 整个键不出现（老路径逐字节不变）', () async {
      expect((await bodyOf(null)).containsKey('placeCheckinToken'), isFalse);
      expect((await bodyOf('')).containsKey('placeCheckinToken'), isFalse);
    });
  });

  group('控制器透传', () {
    test('任何类型都透传 token（切到 Moment 仍带，AD-10「任何帖子类型均可」）', () async {
      final repo = _RecordingRepo();
      final c = PublishController(repository: repo, uploadOne: (b) async => 'https://cdn/x.jpg')
        ..setType(ContentType.growthMoment)
        ..setType(ContentType.daily)
        ..setText('Ngopi');
      await c.publish(idempotencyKey: 'k', placeCheckinToken: 'c' * 32);
      expect(repo.lastToken, 'c' * 32);
      expect(repo.lastType, ContentType.daily);
    });
  });

  group('发帖页场所条（AC3）', () {
    const place = PublishCheckinPlace(placeToken: 'p', placeName: 'Kopi Kucing', placeType: PlaceType.cafe);

    Future<PublishController> pump(WidgetTester tester, {String? token}) async {
      tester.view.physicalSize = const Size(1200, 3200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      final controller = PublishController(repository: _RecordingRepo(), uploadOne: (b) async => 'x');
      final container = ProviderContainer(overrides: [
        publishControllerProvider.overrideWithValue(controller),
        profileRepositoryProvider.overrideWithValue(_WithPetRepo()),
        authControllerProvider.overrideWith(_WithArchiveAuth.new),
      ]);
      addTearDown(container.dispose);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('id'),
          home: Scaffold(
            body: PublishComposePage(
              preset: ContentType.growthMoment,
              placeCheckinToken: token,
              placeCheckinPlace: token == null ? null : place,
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();
      return controller;
    }

    testWidgets('带 token：Diary 预选 + 只读场所条；切类型后场所条仍在', (tester) async {
      final c = await pump(tester, token: 'c' * 32);
      expect(c.type, ContentType.growthMoment);
      expect(find.byKey(const ValueKey('publishCheckinPlaceStrip')), findsOneWidget);
      expect(find.text('Kopi Kucing'), findsOneWidget);

      c.setType(ContentType.daily);
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('publishCheckinPlaceStrip')), findsOneWidget);
    });

    testWidgets('普通入口（无 token）不出场所条', (tester) async {
      await pump(tester);
      expect(find.byKey(const ValueKey('publishCheckinPlaceStrip')), findsNothing);
    });

    test('源码：发布透传 widget.placeCheckinToken；埋点只在带 token 时发、不带正文 / 宠物名', () {
      final src = File('lib/features/content/presentation/publish_compose_page.dart').readAsStringSync();
      expect(src, contains('placeCheckinToken: widget.placeCheckinToken'));
      final at = src.indexOf("'place_checkin_post_created'");
      expect(at, greaterThan(0));
      final guard = src.lastIndexOf('if (widget.placeCheckinToken != null', at);
      expect(guard, greaterThan(0), reason: '无 token 的普通发布不发该事件');
      final block = src.substring(at, src.indexOf('});', at));
      expect(block, contains("'place_id': checkinPlace.placeToken"));
      for (final banned in ['text', 'name', 'pet', 'latitude']) {
        expect(block.contains("'$banned"), isFalse);
      }
    });
  });

  group('帖子详情 checkinPlace（AC5.3）', () {
    Map<String, dynamic> wire(Object? place) => {
          'id': 1,
          'authorId': 7,
          'authorDeleted': false,
          'type': 'DAILY',
          'likeCount': 0,
          'commentCount': 0,
          'liked': false,
          'isAuthor': true,
          'createdAt': '2026-09-30T00:00:00Z',
          'checkinPlace': ?place,
        };

    test('ACTIVE / UNAVAILABLE / 缺键', () {
      final a = ContentDetail.fromJson(wire({'token': 'p' * 32, 'name': 'Kopi', 'status': 'ACTIVE'}));
      expect(a.checkinPlace?.token, 'p' * 32);
      expect(a.checkinPlace?.available, isTrue);
      final u = ContentDetail.fromJson(wire({'token': 'p' * 32, 'name': 'Kopi', 'status': 'UNAVAILABLE'}));
      expect(u.checkinPlace?.available, isFalse);
      final odd = ContentDetail.fromJson(wire({'token': 'p' * 32, 'name': 'Kopi'}));
      expect(odd.checkinPlace?.available, isFalse, reason: 'fail-closed');
      expect(ContentDetail.fromJson(wire(null)).checkinPlace, isNull);
    });
  });
}

class _RecordingRepo implements ContentRepository {
  String? lastToken;
  ContentType? lastType;

  @override
  Future<int> publish({
    required ContentType type,
    int? petId,
    String? text,
    List<String> imageUrls = const [],
    List<ImageSize?> imageSizes = const [],
    DateTime? eventDate,
    required String idempotencyKey,
    bool syncToMoment = true,
    List<int> mentionedUserIds = const [],
    String? placeCheckinToken,
  }) async {
    lastToken = placeCheckinToken;
    lastType = type;
    return 1;
  }
}

class _WithPetRepo implements ProfileRepository {
  @override
  Future<PetProfile?> getMyProfile() async => const PetProfile(id: 1, name: 'Oyen', cardToken: 'T');

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _WithArchiveAuth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(
          nickname: 'Aurel',
          petStatus: 'HAS_PET',
          hasPetProfile: true,
          onboardingCompleted: true,
        ),
      );

  @override
  Future<void> ensureRestored() => Future<void>.value();
}
