import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/content/domain/pinned_slot.dart';
import 'package:tailtopia/features/content/presentation/promo_target.dart';

/// L0：话题页深链 / 推广卡来源 / 游客可达（V1.4.2 social-topic · Story 4.3 · AC1）。
void main() {
  group('deepLinkToLocation', () {
    test('tailtopia://topic/KucingLucu → /topics/KucingLucu', () {
      expect(deepLinkToLocation(Uri.parse('tailtopia://topic/KucingLucu')), '/topics/KucingLucu');
    });

    test('空 name → /home', () {
      expect(deepLinkToLocation(Uri.parse('tailtopia://topic')), '/home');
      expect(deepLinkToLocation(Uri.parse('tailtopia://topic/')), '/home');
    });

    test('非 ASCII 名字编码进路径段', () {
      expect(deepLinkToLocation(Uri.parse('tailtopia://topic/${Uri.encodeComponent('猫咪')}')),
          '/topics/${Uri.encodeComponent('猫咪')}');
    });
  });

  group('推广卡来源 from=pin', () {
    test('落 /topics/… 追加 from=pin；其余落点原样', () {
      expect(promoLocationWithSource('/topics/kucinglucu'), '/topics/kucinglucu?from=pin');
      expect(promoLocationWithSource('/topics/a?x=1'), '/topics/a?x=1&from=pin');
      expect(promoLocationWithSource('/places/abc?from=share'), '/places/abc?from=share');
      expect(promoLocationWithSource('/home'), '/home');
    });

    testWidgets('openPromoTarget 推广卡深链落话题页时带 from=pin', (tester) async {
      final router = GoRouter(
        initialLocation: '/start',
        routes: [
          GoRoute(
            path: '/start',
            builder: (c, s) => Scaffold(
              body: Builder(
                builder: (ctx) => TextButton(
                  onPressed: () => openPromoTarget(
                    ctx,
                    const PromoCard(
                      imageUrl: 'x',
                      title: 't',
                      linkUrl: 'tailtopia://topic/KucingLucu',
                    ),
                  ),
                  child: const Text('go'),
                ),
              ),
            ),
          ),
          GoRoute(path: '/topics/:name', builder: (c, s) => Scaffold(body: Text('topic ${s.uri}'))),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(MaterialApp.router(routerConfig: router));
      await tester.tap(find.text('go'));
      await tester.pumpAndSettle();
      expect(find.text('topic /topics/KucingLucu?from=pin'), findsOneWidget);
    });
  });

  test('路由：游客直开 /topics/x 不被 redirect（不在受控前缀里）', () {
    expect(redirectWouldRewrite(const AuthState.guest(), '/topics/x'), isFalse);
    expect(redirectWouldRewrite(const AuthState.guest(), '/topics/x?from=home_row'), isFalse);
  });
}
