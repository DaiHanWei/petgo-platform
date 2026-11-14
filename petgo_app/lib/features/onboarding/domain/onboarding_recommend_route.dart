/// 首日推荐页的路由常量与出口去向（V1.5.0 onboarding-recall Story 1.2 · FR-173 · OR-7，纯函数，L0 可测）。
library;

/// 首日推荐页的路由（V1.5.0 onboarding-recall Story 1.2 · FR-173 · OR-7）。
const String kOnboardingRecommendRoute = '/onboarding/recommend';

/// 引导出口 → 推荐页的入口值（埋点 `entry`，PRD §4）。
const String kOnboardingRecommendEntryAfterPost = 'after_post';
const String kOnboardingRecommendEntrySkipProfile = 'skip_profile';
const String kOnboardingRecommendEntrySkipPost = 'skip_post';
const String kOnboardingRecommendEntryNoPet = 'no_pet';

/// 推荐页结束后的落点值：`profile` → `/profile`，其余 → `/home`。
const String kOnboardingRecommendLandingProfile = 'profile';
const String kOnboardingRecommendLandingHome = 'home';

/// 拼一个推荐页地址（所有引导出口统一走这里，不在调用点手拼 query）。
String onboardingRecommendLocation({required String entry, required String landing}) =>
    '$kOnboardingRecommendRoute?entry=$entry&landing=$landing';

/// 建档庆祝页（`/profile/created`）主 CTA「Rekam Momen Pertama」的去向。
///
/// 只有首日引导（`/onboarding/profile` → 建档，`flow=onboarding`）来的才带 `from=onboarding`
/// （发布成功 / 不发都进首日推荐页）；其他入口建档（Diary / 我的 / 添加宠物）保持原去向 `/publish`
/// ——PRD「推荐页只给 1.5.0 起注册的新用户」。
String celebrationPublishLocation({required bool fromOnboarding}) =>
    fromOnboarding ? '/publish?preset=growth-calendar&from=onboarding' : '/publish';

/// 建档庆祝页次链接「Lihat profil dulu」的去向（同上口径）。
String celebrationViewProfileLocation({required bool fromOnboarding}) => fromOnboarding
    ? onboardingRecommendLocation(
        entry: kOnboardingRecommendEntrySkipPost, landing: kOnboardingRecommendLandingProfile)
    : '/profile';
