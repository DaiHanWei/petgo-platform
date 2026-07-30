/// 兽医登录响应模型（对应后端 `VetLoginResponse`，Story 5.1）。
///
/// 与用户侧 [LoginResponse] 隔离：兽医只有 accessToken/refreshToken/displayName/role(=VET)，
/// 无 onboarding/新老用户分流（兽医由运营开户，无引导流）。
class VetLoginResponse {
  const VetLoginResponse({
    required this.accessToken,
    required this.refreshToken,
    required this.displayName,
    required this.role,
  });

  final String accessToken;
  final String refreshToken;
  final String displayName;
  final String role;

  factory VetLoginResponse.fromJson(Map<String, dynamic> json) => VetLoginResponse(
        accessToken: json['accessToken'] as String,
        refreshToken: json['refreshToken'] as String,
        displayName: (json['displayName'] ?? '') as String,
        role: (json['role'] ?? 'VET') as String,
      );
}

/// 兽医自身视图（对应后端 `VetMeResponse`）。
class VetMe {
  const VetMe({required this.id, required this.displayName, required this.status, this.avatarUrl});

  final int id;
  final String displayName;
  final String status;

  /// 运营在后台上传的头像 CDN URL；null → 首字母占位。
  final String? avatarUrl;

  factory VetMe.fromJson(Map<String, dynamic> json) => VetMe(
        id: (json['id'] as num).toInt(),
        displayName: (json['displayName'] ?? '') as String,
        status: (json['status'] ?? 'ACTIVE') as String,
        avatarUrl: json['avatarUrl'] as String?,
      );
}
