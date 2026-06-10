import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/problem_detail.dart';
import 'package:tailtopia/features/auth/domain/auth_routing.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';

void main() {
  group('AC4 新老用户分流（纯函数）', () {
    test('onboardingCompleted=true → 进 App', () {
      final resp = LoginResponse.fromJson(const {
        'accessToken': 'a',
        'refreshToken': 'r',
        'role': 'USER',
        'isNewUser': false,
        'onboardingCompleted': true,
      });
      expect(decidePostLoginRoute(resp), PostLoginRoute.toApp);
    });

    test('新用户/未完成引导 → 进引导', () {
      final resp = LoginResponse.fromJson(const {
        'accessToken': 'a',
        'refreshToken': 'r',
        'role': 'USER',
        'isNewUser': true,
        'onboardingCompleted': false,
      });
      expect(decidePostLoginRoute(resp), PostLoginRoute.toOnboarding);
    });
  });

  group('LoginResponse.fromJson', () {
    test('解析 profile 内嵌字段', () {
      final resp = LoginResponse.fromJson(const {
        'accessToken': 'acc',
        'refreshToken': 'ref',
        'role': 'USER',
        'isNewUser': true,
        'onboardingCompleted': false,
        'profile': {'nickname': 'Alice', 'displayName': 'Alice G', 'hasPetProfile': false},
      });
      expect(resp.accessToken, 'acc');
      expect(resp.profile?.nickname, 'Alice');
      expect(resp.profile?.hasPetProfile, false);
    });
  });

  group('ProblemDetail 解析', () {
    test('从 map 解析字段 + typeSlug', () {
      final pd = ProblemDetail.fromJson(const {
        'type': 'https://petgo/errors/validation',
        'title': 'Validation Failed',
        'status': 422,
        'detail': 'bad',
        'errors': [
          {'field': 'nickname', 'message': 'too long'}
        ],
      });
      expect(pd, isNotNull);
      expect(pd!.status, 422);
      expect(pd.typeSlug, 'validation');
      expect(pd.errors.single.field, 'nickname');
    });

    test('从 DioException 兜底状态码', () {
      final e = DioException(
        requestOptions: RequestOptions(path: '/x'),
        response: Response(requestOptions: RequestOptions(path: '/x'), statusCode: 429),
      );
      final pd = ProblemDetail.fromDioException(e);
      expect(pd?.status, 429);
    });
  });
}
