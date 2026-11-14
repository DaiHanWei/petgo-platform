package com.tailtopia.notify.service;

/**
 * {@link NotificationService#sendHealthReminder} 的结果（V1.5.0 健康记录提醒 Story 2.4，架构 Z-7）。
 *
 * <p>腾讯推送对「没有注册设备」也返回成功，无法识别真正的无 token；能识别的只有：虚拟号（无 IM 账号）→ {@code NO_TOKEN}、
 * 分类被关 → {@code MUTED}、推送调用返回失败 → {@code FAILED}，其余 {@code SENT}。
 */
public record HealthPushOutcome(long notificationId, Result result) {

    public enum Result {
        SENT,
        MUTED,
        NO_TOKEN,
        FAILED
    }
}
