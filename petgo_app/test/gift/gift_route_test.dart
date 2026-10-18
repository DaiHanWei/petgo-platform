import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/gift/presentation/gift_page.dart';
import 'package:tailtopia/features/profile/presentation/visitor_archive_view.dart';

/// L0：礼物页路由（V1.4.0 gift Story 2.2 · AC3.1）。用**真实**的 `routerProvider` 路由表做匹配：
/// `/pets/5/gifts` 必须落礼物页，不能被 `/pets/:petId` 吃成 petId='5/gifts'（那条 builder 是 int.parse，会崩）。
class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, role: 'USER');
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late ProviderContainer container;
  late GoRouter router;

  setUp(() {
    container = ProviderContainer(overrides: [authControllerProvider.overrideWith(_Auth.new)]);
    router = container.read(routerProvider);
  });
  tearDown(() => container.dispose());

  GoRoute leaf(String location) {
    final match = router.configuration.findMatch(Uri.parse(location));
    expect(match.isNotEmpty, isTrue, reason: '$location 没有匹配到任何路由');
    return match.matches.last.route as GoRoute;
  }

  test('/pets/5/gifts → 礼物页路由，petId = 5', () {
    final match = router.configuration.findMatch(Uri.parse('/pets/5/gifts'));
    expect((match.matches.last.route as GoRoute).path, GiftPage.routePattern);
    expect(match.pathParameters['petId'], '5');
  });

  test('/pets/5/gifts?wall=expanded → 同一条路由（query 不影响匹配）', () {
    expect(leaf('/pets/5/gifts?wall=expanded').path, GiftPage.routePattern);
  });

  test('/pets/5 仍是站内访客视图（不受礼物页路由影响）', () {
    expect(leaf('/pets/5').path, VisitorArchiveView.inAppRoutePattern);
  });

  test('/pets/abc/gifts 落礼物页（builder 用 tryParse → 0 → E6），不落访客视图的 int.parse', () {
    expect(leaf('/pets/abc/gifts').path, GiftPage.routePattern);
  });

  test('礼物页不在受控前缀里（门控在入口 requireLogin + 服务端），已登录不被改写', () {
    const auth = AuthState(status: AuthStatus.authenticated, role: 'USER');
    expect(redirectWouldRewrite(auth, '/pets/5/gifts?wall=expanded'), isFalse);
    expect(redirectWouldRewrite(const AuthState.guest(), '/pets/5/gifts'), isFalse);
  });
}
