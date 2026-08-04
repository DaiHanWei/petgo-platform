import 'auth_state.dart';

/// 应用级用户状态（V1.1.2 · AD-8 · FR-78）。**冷启动落地 Tab 与埋点 `user_state` 的唯一判定源**。
///
/// ⚠️ Story 6.1 的埋点 `user_state` 属性直接取 [AppUserState.wire]。落地分流与埋点两处各写一份
/// 判定，会让「看板上的用户分布」与「实际落到哪个 Tab」对不上 —— 所以这里只留一份。
///
/// 与 Diary 页的 `DiaryUserState`（Story 2.1 · AD-15）的关系：
/// - 本枚举是**应用级**状态（含兽医、区分 B/C），输入是登录响应里的 `hasPetProfile` 标志；
/// - `DiaryUserState` 是**该页的渲染分支**（把 B/C 合并、且以真实拉取到的档案为准）。
/// 前者由后者派生（`DiaryUserState.fromAppUserState`），判定顺序只写在这里一处。
enum AppUserState {
  /// 未登录游客。
  guest('guest'),

  /// 兽医账号（单 App 双角色，与用户侧路由互斥）。
  vet('vet'),

  /// 状态 A（我有宠物）且已建档。
  ownerWithProfile('owner_with_profile'),

  /// 状态 A（我有宠物）但还没填档案。
  ownerWithoutProfile('owner_without_profile'),

  /// 状态 B：还没养，打算养。
  planning('planning'),

  /// 状态 C：喜欢宠物但没打算养。
  enthusiast('enthusiast');

  const AppUserState(this.wire);

  /// 埋点 / 日志用的稳定字面量（snake_case）。**Story 6.1 的 `user_state` 属性取值即此**。
  final String wire;

  /// 冷启动落地 Tab 的目标路径（AD-8 落地矩阵）。
  ///
  /// **不持久化「上次落在哪」**：宠物状态会变，记住上次反而会错 —— 每次冷启动按当时状态实时判定。
  String get landingLocation => switch (this) {
        // 游客落 Diary 游客引导态（FR-80 种草页）；门控例外集精确放行 `/profile` 本身。
        AppUserState.guest => '/profile',
        AppUserState.vet => '/vet/workbench',
        // ⚠️ 口径（V1.1.2 Story 7.4 · FR-78 订正 2026-08-04）：
        // **只有真正建了宠物档案的人才落 Diary，游客是唯一例外**（仍落 Diary 看 FR-80 种草页）。
        // A·已建档 → Diary 看真实成长本。
        AppUserState.ownerWithProfile => '/profile',
        // A·未建档 → **Discovery**（改前落 Diary 建档引导）。产品判断：档案未建时 Diary 里
        // 没有属于他的真实内容，落地页是一张空引导页；内容流的即时价值更高。建档引导仍在
        // Diary Tab 内等他（FR-81 页面不删不改，只是不再作为落地页）。
        // 已知代价：建档转化率可能下降，上线后按 OQ-24 观测。
        AppUserState.ownerWithoutProfile => '/home',
        // B/C 落 Discovery：Diary 对他们是功能拒绝页，开屏第一眼看到它比游客体验更差。
        AppUserState.planning => '/home',
        AppUserState.enthusiast => '/home',
      };
}

/// 从登录态直接取六态（埋点与落地分流共用；Story 6.1 的 `user_state` 属性取它的 `wire`）。
AppUserState appUserStateOf(AuthState auth) => resolveAppUserState(
      isLoggedIn: auth.isLoggedIn,
      isVet: auth.isVet,
      petStatus: auth.profile?.petStatus,
      hasPetProfile: auth.profile?.hasPetProfile ?? false,
    );

/// 六态判定（按序，互斥且穷尽）。未知 `petStatus`（如 profile 尚未回填）按状态 A 处理，
/// 与 Diary 页判定同口径。
AppUserState resolveAppUserState({
  required bool isLoggedIn,
  required bool isVet,
  required String? petStatus,
  required bool hasPetProfile,
}) {
  if (isVet) return AppUserState.vet; // 兽医优先：其登录态没有 UserProfile
  if (!isLoggedIn) return AppUserState.guest;
  return switch (petStatus) {
    'PLANNING' => AppUserState.planning,
    'ENTHUSIAST' => AppUserState.enthusiast,
    _ => hasPetProfile ? AppUserState.ownerWithProfile : AppUserState.ownerWithoutProfile,
  };
}
