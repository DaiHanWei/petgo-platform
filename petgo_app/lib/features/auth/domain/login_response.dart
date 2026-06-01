/// 登录响应模型（对应后端 `LoginResponse`）。
class LoginResponse {
  const LoginResponse({
    required this.accessToken,
    required this.refreshToken,
    required this.role,
    required this.isNewUser,
    required this.onboardingCompleted,
    this.profile,
  });

  final String accessToken;
  final String refreshToken;
  final String role;
  final bool isNewUser;
  final bool onboardingCompleted;
  final UserProfile? profile;

  factory LoginResponse.fromJson(Map<String, dynamic> json) {
    final p = json['profile'];
    return LoginResponse(
      accessToken: json['accessToken'] as String,
      refreshToken: json['refreshToken'] as String,
      role: (json['role'] ?? 'USER') as String,
      isNewUser: (json['isNewUser'] ?? false) as bool,
      onboardingCompleted: (json['onboardingCompleted'] ?? false) as bool,
      profile: p is Map ? UserProfile.fromJson(p.cast<String, dynamic>()) : null,
    );
  }
}

/// 当前用户聚合视图（对应后端 `UserProfileResponse`）。
class UserProfile {
  const UserProfile({
    this.nickname,
    this.displayName,
    this.avatarUrl,
    this.petStatus,
    this.onboardingCompleted = false,
    this.hasPetProfile = false,
  });

  final String? nickname;
  final String? displayName;
  final String? avatarUrl;
  final String? petStatus;
  final bool onboardingCompleted;
  final bool hasPetProfile;

  factory UserProfile.fromJson(Map<String, dynamic> json) => UserProfile(
        nickname: json['nickname'] as String?,
        displayName: json['displayName'] as String?,
        avatarUrl: json['avatarUrl'] as String?,
        petStatus: json['petStatus'] as String?,
        onboardingCompleted: (json['onboardingCompleted'] ?? false) as bool,
        hasPetProfile: (json['hasPetProfile'] ?? false) as bool,
      );
}
