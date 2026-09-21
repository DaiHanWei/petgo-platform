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

    /**
     * 已收手：到期，<b>或</b>被手动提前结束。
     *
     * <p>🔴 {@code phase} 不够用：{@code ScheduleWindow} 的第一判据是 {@code now < startsAt} → PENDING，
     * 所以把一条**待生效**的排期「提前结束」之后，phase 仍然是 PENDING。
     * 只看 phase 的话，界面上会是「待生效 + 按钮还在」，运营点了像没反应，再点一次才得到
     * 「该排期已结束，无需再操作」。判据必须把 {@code terminatedAt} 一起算进来。
     */
    public boolean ended() {
        return phase == SchedulePhase.ENDED || terminatedAt != null;
    }

    /** 是否可编辑 / 可提前结束：仅「待生效」与「生效中」两态（见 {@link #ended()}）。 */
    public boolean editable() {
        return !ended();
    }
}
