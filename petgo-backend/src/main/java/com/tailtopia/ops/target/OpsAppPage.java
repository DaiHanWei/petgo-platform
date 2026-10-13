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
    HEALTH
}
