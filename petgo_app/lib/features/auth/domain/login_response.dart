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
    this.id,
    this.nickname,
    this.displayName,
    this.email,
    this.avatarUrl,
    this.signature,
    this.petStatus,
    this.onboardingCompleted = false,
    this.hasPetProfile = false,
    this.isSystemDefaultName = false,
    this.phone,
    this.createdAt,
    this.userToken,
    this.postCount,
    this.likeCount,
    this.followingCount,
    this.followerCount,
  });

  /// 当前用户 id（Story 3.5：评论删除入口可见性判定）。
  final int? id;
  final String? nickname;
  final String? displayName;

  /// 邮箱（PII）：仅 /me 本人聚合视图返回，绝不进 Feed/作者视图。
  final String? email;
  final String? avatarUrl;

  /// 一句话个性签名（bug 20260721-327，用户级）。
  final String? signature;
  final String? petStatus;
  final bool onboardingCompleted;
  final bool hasPetProfile;

  /// 昵称当前是否为违规重置的系统默认编码名（内容审核 cm-4，D-CM4）。
  /// 后端下发即解析；V1 可选用于轻量「去改名」引导（非必需，无 UI 强制）。
  final bool isSystemDefaultName;

  /// 手机号（V1.1.6 Story 7.1 · FR-70）。选填、不验证、不用于登录；
  /// 未填写 / 已撤回时为 null。
  ///
  /// 🛡 与 email 同一口径：**仅 /me 本人聚合视图返回**，绝不进任何对他人展示的投影。
  /// 这里拿到的是**完整号码** —— 脱敏是显示层的事（设置页列表脱敏、编辑抽屉展示完整）。
  final String? phone;

  /// 注册时间（Story 7.2 · FR-70）。手机号软引导的时机是「第 3 天打开 App」，
  /// 只能从这里算。⚠️ 服务端下发 UTC，**时区换算在 [PhonePromptTiming] 一处做**。
  final DateTime? createdAt;

  /// 对外不可枚举用户 token（V1.3.4 follow D-2，`/me` 下发）。
  final String? userToken;

  /// 我的全部帖子数（V1.3.4 D-5：含私密 / 仅自己可见、本人待审帖，与「我的发布」网格同源）。
  ///
  /// ⚠️ **可空 = 不知道**：登录响应（`/auth/*`）不带它（T-6），只有 `GET /me` 才有。
  /// 我的页四格在它为 null 时显示「–」（T-14），而不是把「不知道」显示成 0。
  final int? postCount;

  /// 我全部帖子的获赞数（同上，可空 = 不知道）。
  final int? likeCount;

  /// 我关注的人数（可空 = 不知道）。
  final int? followingCount;

  /// 关注我的人数（可空 = 不知道）。
  final int? followerCount;

  factory UserProfile.fromJson(Map<String, dynamic> json) => UserProfile(
        id: json['id'] as int?,
        nickname: json['nickname'] as String?,
        displayName: json['displayName'] as String?,
        email: json['email'] as String?,
        avatarUrl: json['avatarUrl'] as String?,
        signature: json['signature'] as String?,
        petStatus: json['petStatus'] as String?,
        onboardingCompleted: (json['onboardingCompleted'] ?? false) as bool,
        hasPetProfile: (json['hasPetProfile'] ?? false) as bool,
        isSystemDefaultName: (json['isSystemDefaultName'] ?? false) as bool,
        phone: json['phone'] as String?,
        createdAt: json['createdAt'] == null
            ? null
            : DateTime.tryParse(json['createdAt'] as String),
        userToken: json['userToken'] as String?,
        postCount: (json['postCount'] as num?)?.toInt(),
        likeCount: (json['likeCount'] as num?)?.toInt(),
        followingCount: (json['followingCount'] as num?)?.toInt(),
        followerCount: (json['followerCount'] as num?)?.toInt(),
      );

  UserProfile copyWith({
    int? id,
    String? nickname,
    String? displayName,
    String? email,
    String? avatarUrl,
    String? signature,
    String? petStatus,
    bool? onboardingCompleted,
    bool? hasPetProfile,
    bool? isSystemDefaultName,
    String? phone,
    DateTime? createdAt,
    String? userToken,
    int? postCount,
    int? likeCount,
    int? followingCount,
    int? followerCount,
  }) =>
      UserProfile(
        id: id ?? this.id,
        nickname: nickname ?? this.nickname,
        displayName: displayName ?? this.displayName,
        email: email ?? this.email,
        avatarUrl: avatarUrl ?? this.avatarUrl,
        signature: signature ?? this.signature,
        petStatus: petStatus ?? this.petStatus,
        onboardingCompleted: onboardingCompleted ?? this.onboardingCompleted,
        hasPetProfile: hasPetProfile ?? this.hasPetProfile,
        isSystemDefaultName: isSystemDefaultName ?? this.isSystemDefaultName,
        phone: phone ?? this.phone,
        createdAt: createdAt ?? this.createdAt,
        userToken: userToken ?? this.userToken,
        postCount: postCount ?? this.postCount,
        likeCount: likeCount ?? this.likeCount,
        followingCount: followingCount ?? this.followingCount,
        followerCount: followerCount ?? this.followerCount,
      );
}
