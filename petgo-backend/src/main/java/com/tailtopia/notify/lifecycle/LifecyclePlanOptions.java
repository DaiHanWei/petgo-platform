package com.tailtopia.notify.lifecycle;

/**
 * 生命周期计划选项（V1.5.0 onboarding-recall Story 6.1）。
 *
 * @param winbackEnabled   是否产出召回节点 {@code LIFECYCLE_WINBACK}（6-2 由 {@code winback-enabled} 配置驱动；
 *                         false = 30 天没来的老用户不收任何生命周期推送）
 * @param winbackAfterDays 「多久没回来算流失」（天）
 */
public record LifecyclePlanOptions(boolean winbackEnabled, int winbackAfterDays) {

    /** 默认：不产出召回。 */
    public static final LifecyclePlanOptions DEFAULT = new LifecyclePlanOptions(false, 7);
}
