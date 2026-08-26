package com.tailtopia.admin.pin.dto;

import com.tailtopia.shared.schedule.SchedulePhase;
import java.time.Instant;

/**
 * 顶置配置列表的一行（Story 11.1 · AB-10A）。Thymeleaf getter 访问。
 *
 * @param phase        待生效 / 生效中 / 已结束 —— 走 {@code ScheduleWindow} 那份**唯一判定**
 * @param contentGone  🔴 仅 CONTENT 类型有意义：所引用的内容**已不可对外展示**
 *                     （已删 / 非 PUBLISHED / 非 PUBLIC）。判定与 App 的 Feed 坑位**同源**
 *                     （{@code ContentDisplayability}），否则会出现「后台说生效中、
 *                     App 坑位却是空的」这种运营无从下手的不一致。
 */
public record PinRow(
        long id,
        String slot,
        String objectType,
        Long contentId,
        String summary,
        Instant startsAt,
        Instant endsAt,
        Instant terminatedAt,
        SchedulePhase phase,
        boolean contentGone) {

    /** 是否可编辑 / 可提前结束：仅「待生效」与「生效中」两态。 */
    public boolean editable() {
        return phase != SchedulePhase.ENDED;
    }
}
