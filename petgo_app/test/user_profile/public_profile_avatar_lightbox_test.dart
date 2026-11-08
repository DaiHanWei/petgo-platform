import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_pet_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_profile_repository.dart';
import 'package:tailtopia/features/user_profile/data/public_user_posts_repository.dart';
import 'package:tailtopia/features/user_profile/presentation/public_profile_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/media/image_lightbox.dart';

/// V1.4.2：主页头像点开看大图（复用 ImageLightbox）；无头像占位不可点。
///
/// 覆盖 AC2（两个计数都在、同一行）· AC4（2 列网格 / 点进详情页 / 分页）·
/// AC5（FR-118.7 的「不放」清单，反向验收）。
/// AC1/AC3（可见范围与无 N+1）在**服务端**，由 `UserPublicPostsTest` 守。

const int _kViewerId = 5;
const int _kTargetId = 7;

class _FakeProfileRepo implements PublicProfileRepository {
  _FakeProfileRepo(this.profile);
  final PublicProfile profile;
  @override
  Future<PublicProfile> getPublicProfile(int userId) async => profile;
}

class _FakePostsRepo implements PublicUserPostsRepository {
  _FakePostsRepo(this.pages);

  /// 依次返回的各页；用完之后一律回空页。
  final List<PublicUserPostPage> pages;

  /// 每次调用带进来的 cursor —— 「加载更多」有没有真的往后翻，靠它证。
  final List<String?> cursors = <String?>[];

  @override
  Future<PublicUserPostPage> fetch(int userId, {String? cursor}) async {
    cursors.add(cursor);
    return cursors.length <= pages.length ? pages[cursors.length - 1] : PublicUserPostPage.empty;
  }
}

class _LoggedInAuth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: _kViewerId, nickname: 'Me', onboardingCompleted: true),
      );
}

PublicProfile _profile({int postCount = 18, int likeCount = 342, String? avatarUrl}) => PublicProfile(
      avatarUrl: avatarUrl,
      postCount: postCount,
      likeCount: likeCount,
      isDeactivated: false,
      self: false,
      nickname: 'Rina',
      joinedAt: DateTime.utc(2026, 3, 4),
    );

/// 记录最后一次落到 `/content/:id` 的 id —— 用来验「点任一内容进既有内容详情页」。
class _Nav {
  int? lastContentId;
}

Future<_Nav> _pump(
  WidgetTester tester, {
  required PublicUserPostsRepository postsRepo,
  PublicProfile? profile,
}) async {
  final nav = _Nav();
  // V1.3.4 居中名片把身份区拉高了 —— 给一块高屏，让网格与「加载更多」都在可视区内（ListView 懒构建）。
  tester.view.physicalSize = const Size(1080, 3200);
  tester.view.devicePixelRatio = 2.0;
  addTearDown(tester.view.reset);
  final router = GoRouter(
    initialLocation: '${PublicProfilePage.routeBase}/$_kTargetId',
    routes: [
      GoRoute(
        path: PublicProfilePage.routePattern,
        builder: (_, state) =>
            PublicProfilePage(userId: int.parse(state.pathParameters['userId']!)),
      ),
      GoRoute(
        path: '/content/:id',
        builder: (_, state) {
          nav.lastContentId = int.parse(state.pathParameters['id']!);
          return const Scaffold(body: Center(child: Text('detail')));
        },
      ),
    ],
  );
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    publicProfileRepositoryProvider.overrideWithValue(_FakeProfileRepo(profile ?? _profile())),
    publicUserPostsRepositoryProvider.overrideWithValue(postsRepo),
    // 宠物卡在本文件里不是被验对象 —— 给一个「没有宠物」，免得走真网络。
    publicProfilePetsProvider(_kTargetId).overrideWith((ref) async => const []),
    authControllerProvider.overrideWith(_LoggedInAuth.new),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: router,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
  return nav;
}

void main() {
  testWidgets('有头像：点头像打开全屏灯箱，加载的是原图 URL', (tester) async {
    debugNetworkImageHttpClientProvider = () => _FakeHttpClient();
    try {
      await _pump(tester,
          postsRepo: _FakePostsRepo(const []),
          profile: _profile(avatarUrl: 'https://cdn.example/avatar.jpg'));
      expect(find.byType(ImageLightbox), findsNothing);
      await tester.tap(find.byKey(const ValueKey('profileAvatarTap')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      final lb = tester.widget<ImageLightbox>(find.byType(ImageLightbox));
      expect(lb.urls, ['https://cdn.example/avatar.jpg']);
      expect(lb.source, 'user_profile_avatar');
      await tester.pumpWidget(const SizedBox());
    } finally {
      debugNetworkImageHttpClientProvider = null;
    }
  });

  testWidgets('无头像：占位不可点，不出灯箱', (tester) async {
    await _pump(tester, postsRepo: _FakePostsRepo(const []), profile: _profile());
    expect(find.byKey(const ValueKey('profileAvatar')), findsOneWidget);
    expect(find.byKey(const ValueKey('profileAvatarTap')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('profileAvatar')));
    await tester.pumpAndSettle();
    expect(find.byType(ImageLightbox), findsNothing);
  });
}

/// 任何图片请求都回一张 1x1 透明 PNG —— 让 NetworkImage 在 widget test 里能解码成功。
/// 1x1 透明 PNG 的字节。
final Uint8List _tinyPng = Uint8List.fromList(<int>[
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
  0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
  0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
  0x42, 0x60, 0x82,
]);

class _FakeHttpClient implements HttpClient {
  @override
  Future<HttpClientRequest> getUrl(Uri url) async => _FakeHttpClientRequest();

  @override
  noSuchMethod(Invocation invocation) => throw UnsupportedError('未用到的 HttpClient 成员');
}

class _FakeHttpClientRequest implements HttpClientRequest {
  @override
  final HttpHeaders headers = _FakeHttpHeaders();

  @override
  Future<HttpClientResponse> close() async => _FakeHttpClientResponse();

  @override
  noSuchMethod(Invocation invocation) => throw UnsupportedError('未用到的请求成员');
}

class _FakeHttpClientResponse implements HttpClientResponse {
  @override
  int get statusCode => HttpStatus.ok;

  @override
  int get contentLength => _tinyPng.length;

  @override
  HttpClientResponseCompressionState get compressionState =>
      HttpClientResponseCompressionState.notCompressed;

  @override
  StreamSubscription<List<int>> listen(void Function(List<int>)? onData,
      {Function? onError, void Function()? onDone, bool? cancelOnError}) {
    return Stream<List<int>>.fromIterable(<List<int>>[_tinyPng])
        .listen(onData, onError: onError, onDone: onDone, cancelOnError: cancelOnError);
  }

  @override
  noSuchMethod(Invocation invocation) => throw UnsupportedError('未用到的响应成员');
}


class _FakeHttpHeaders implements HttpHeaders {
  @override
  noSuchMethod(Invocation invocation) => null;
}
