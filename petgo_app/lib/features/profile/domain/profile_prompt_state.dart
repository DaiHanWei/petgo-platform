/// 首页档案提示条状态（Story 1.7，FR-0H）。
///
/// 纯客户端状态（`shared_preferences` 持久化 restartCount/dismissedPermanently/petProfileCompleted；
/// dismissedThisSession 仅内存，重启清空）。后端只提供 `hasPetProfile`。
class ProfilePromptState {
  const ProfilePromptState({
    this.restartCount = 0,
    this.dismissedPermanently = false,
    this.petProfileCompleted = false,
    this.dismissedThisSession = false,
  });

  /// 冷启动累计次数（持久化）。
  final int restartCount;

  /// 第 3 次重启关闭后永久关闭（持久化）。
  final bool dismissedPermanently;

  /// 档案完成上传后永久关闭（持久化）。
  final bool petProfileCompleted;

  /// 当次 session 已关闭（内存，重启清空）。
  final bool dismissedThisSession;

  ProfilePromptState copyWith({
    int? restartCount,
    bool? dismissedPermanently,
    bool? petProfileCompleted,
    bool? dismissedThisSession,
  }) =>
      ProfilePromptState(
        restartCount: restartCount ?? this.restartCount,
        dismissedPermanently: dismissedPermanently ?? this.dismissedPermanently,
        petProfileCompleted: petProfileCompleted ?? this.petProfileCompleted,
        dismissedThisSession: dismissedThisSession ?? this.dismissedThisSession,
      );
}

/// 显示判定（可单测纯函数）。
///
/// 仅 `pet_status==A` 且未完成档案时可能显示；B/C **完全不激活**。
/// 「前 3 次重启均显示，第 3 次关闭后或完成上传后永不再显示」：
/// - 已完成档案 / 已永久关闭 / 本 session 已关闭 → 不显示；
/// - 否则按重启计数 ≤3 显示。
bool shouldShowProfilePrompt({
  required String? petStatus,
  required bool hasPetProfile,
  required ProfilePromptState state,
}) {
  if (petStatus != 'HAS_PET') return false; // PLANNING/ENTHUSIAST 不激活
  if (hasPetProfile || state.petProfileCompleted || state.dismissedPermanently) return false;
  if (state.dismissedThisSession) return false;
  return state.restartCount <= 3;
}

/// 冷启动：重启计数 +1（封顶到 4 即足以判定 >3 不显示，避免无限增长）。
ProfilePromptState onColdStartIncrement(ProfilePromptState s) =>
    s.copyWith(restartCount: s.restartCount >= 4 ? 4 : s.restartCount + 1);

/// 关闭 X：本 session 不再显示；**第 3 次（及以后）重启时关闭 → 永久关闭**；计数不增。
ProfilePromptState onDismiss(ProfilePromptState s) {
  if (s.restartCount >= 3) {
    return s.copyWith(dismissedPermanently: true, dismissedThisSession: true);
  }
  return s.copyWith(dismissedThisSession: true);
}

/// 档案完成上传 → 永久不显示。
ProfilePromptState onProfileCompleted(ProfilePromptState s) =>
    s.copyWith(petProfileCompleted: true);
