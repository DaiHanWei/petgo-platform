/// 后端 API 路径常量（`/api/v1`）。集中管理，禁止散落字符串。
class ApiPaths {
  ApiPaths._();

  static const String base = '/api/v1';

  static const String authGoogle = '$base/auth/google';
  static const String authRefresh = '$base/auth/refresh';

  /// 当前用户主体统一端点（决策 C1：全平台用 /me，不用 /users/me）。
  static const String me = '$base/me';
}
