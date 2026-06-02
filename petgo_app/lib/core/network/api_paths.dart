/// 后端 API 路径常量（`/api/v1`）。集中管理，禁止散落字符串。
class ApiPaths {
  ApiPaths._();

  static const String base = '/api/v1';

  static const String authGoogle = '$base/auth/google';
  static const String authRefresh = '$base/auth/refresh';

  /// 当前用户主体统一端点（决策 C1：全平台用 /me，不用 /users/me）。
  static const String me = '$base/me';

  /// 媒体 STS 上传凭证（Story 2.1）。
  static const String mediaStsCredentials = '$base/media/sts-credentials';

  /// 宠物档案（Story 2.2）。
  static const String petProfiles = '$base/pet-profiles';
  static const String petProfileMe = '$base/pet-profiles/me';
}
