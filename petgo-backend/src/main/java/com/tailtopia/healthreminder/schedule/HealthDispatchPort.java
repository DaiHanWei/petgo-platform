package com.tailtopia.healthreminder.schedule;

/**
 * 投递出口（V1.5.0 Story 2.3 定义，Story 2.4 实现 {@code HealthReminderDispatcher}）：渲染文案 → 插 health_notifications →
 * {@code NotificationService#sendHealthReminder} → 行置 SENT → 埋点。在调用方（每宠物 REQUIRES_NEW）事务内执行。
 */
public interface HealthDispatchPort {

    /** 投递结果：{@code sent=true} 表示行已置 SENT（不论推送本身是否送达，推送结果另记）。 */
    record DispatchOutcome(boolean sent, Long healthNotificationId) {

        public static final DispatchOutcome NOT_SENT = new DispatchOutcome(false, null);
    }

    DispatchOutcome dispatch(SendGroup group);
}
