package com.tailtopia.healthreminder.schedule;

import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.PushPoint;
import java.time.LocalDate;
import java.util.List;

/**
 * 一个发送组（V1.5.0 Story 2.3）：同一宠物、同一天、同类同推送点合并后的一次推送 + 一条通知中心条目。单条提醒也是一组。
 * 投递（文案、落库、推送、埋点、行状态 SENT）由 2.4 的 {@code HealthReminderDispatcher} 完成。
 */
public record SendGroup(long petId, long ownerId, LocalDate day, PushPoint point, List<HealthReminder> rows) {

    public boolean merged() {
        return rows.size() > 1;
    }
}
