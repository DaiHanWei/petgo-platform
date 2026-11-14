package com.tailtopia.ops.target;

/**
 * {@link OpsTargetType#APP_PAGE} 允许的 App 内页面（AD-16，<b>只这 5 个</b>）。
 *
 * <p>App 侧映射：HOME {@code /home} · PUBLISH 发帖弹层 · TOKO {@code /shop} ·
 * KNOW_YOUR_PET {@code /profile/pet-insights} · HEALTH {@code /profile/health}。
 * 加值须同步 App 端 {@code openOpsTarget}，否则旧版本 App 收到未知页面只能静默忽略。
 */
public enum OpsAppPage {
    HOME,
    PUBLISH,
    TOKO,
    KNOW_YOUR_PET,
    HEALTH,
    /**
     * V1.5.0 超值礼包页（地基 Story 0.1 占位，只往后加）。App 跳转与停售 toast 由 {@code feat/1.5.0-starter-pack} 实现；
     * 旧版 App 不认识本值 → 解析为空、不跳转（{@code ops_target.dart} fromWire 返回 null）。
     */
    STARTER_PACK,
    /** V1.5.0 宠物类型页（地基 Story 0.1 占位，只往后加）。App 跳转由 {@code feat/1.5.0-pet-species} 实现。 */
    PET_SPECIES
}
