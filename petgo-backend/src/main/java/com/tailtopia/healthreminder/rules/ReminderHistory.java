package com.tailtopia.healthreminder.rules;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 一只宠物已有提醒行的只读视图（V1.5.0 Story 2.1）：规则判「一次性已发」「补发已用」「既有下一跑日期」。纯数据，规则不读库。
 */
public record ReminderHistory(List<Row> rows) {

    /** 规则只关心的列。 */
    public record Row(Long sourceRecordId, ReminderKind kind, ReminderOrigin origin, ReminderStatus status,
            CancelReason cancelReason, LocalDate nextDateRef, LocalDate dueDate, LocalDate sendDate) {

        public static Row of(HealthReminder r) {
            return new Row(r.getSourceRecordId(), r.getReminderKind(), r.getOrigin(), r.getStatus(),
                    r.getCancelReason(), r.getNextDateRef(), r.getDueDate(), r.getSendDate());
        }
    }

    public static final ReminderHistory EMPTY = new ReminderHistory(List.of());

    public static ReminderHistory of(List<HealthReminder> reminders) {
        return new ReminderHistory(reminders.stream().map(Row::of).toList());
    }

    /** 该 kind 是否发过（任一来源）。 */
    public boolean sentEver(ReminderKind kind) {
        return rows.stream().anyMatch(r -> r.kind() == kind && r.status() == ReminderStatus.SENT);
    }

    /** 该 kind 已发行的发送日（升序）。 */
    public List<LocalDate> sentDates(ReminderKind kind) {
        return rows.stream().filter(r -> r.kind() == kind && r.status() == ReminderStatus.SENT)
                .map(Row::sendDate).sorted().toList();
    }

    /**
     * 某 (记录, Next date) 的疫苗恢复补发已用（非 {@code rebuilt} 取消的 RESUME_CATCH_UP 行存在）→ 该 Next date 不再生成任何点
     * （PRD FR-163 Pregnant/nursing catch-up）。
     */
    public boolean catchUpUsed(long sourceRecordId, LocalDate nextDateRef) {
        return rows.stream().anyMatch(r -> r.origin() == ReminderOrigin.RESUME_CATCH_UP
                && r.sourceRecordId() != null && r.sourceRecordId() == sourceRecordId
                && nextDateRef.equals(r.nextDateRef())
                && !(r.status() == ReminderStatus.CANCELLED && r.cancelReason() == CancelReason.REBUILT));
    }

    /**
     * 「下一跑」类事件提醒（猫二次发情 / 公的首次发情）已有行（任一状态）的原定日：保证重建幂等——被日跑取消
     * （名额 / 顺延到期等）的行不会以新的原定日复活，绕开 3 天顺延上限。
     */
    public Optional<LocalDate> existingDueDate(long sourceRecordId, ReminderKind kind) {
        return rows.stream().filter(r -> r.kind() == kind && r.sourceRecordId() != null
                        && r.sourceRecordId() == sourceRecordId)
                .map(Row::dueDate).min(LocalDate::compareTo);
    }

    /**
     * 上一个体检周期的终点：已发 CHECKUP 的发送日，或被日跑作废（名额 / 顺延到期 / 错过日跑 / 被生日等占用 / 旧版）的
     * CHECKUP 的原定日，取较晚者。没有 → null。
     *
     * <p>PRD 只说「已发的体检」算 base；但一个周期若没发出去（被日跑作废），base 不前进，到期日永远停在过去、之后再也
     * 不会有体检——这里把「已尝试过的周期」也当作终点，下一周期从它起算（Story 2.2 待确认）。
     */
    public LocalDate lastCheckupCycleEnd() {
        LocalDate best = null;
        for (Row r : rows) {
            if (r.kind() != ReminderKind.CHECKUP) {
                continue;
            }
            LocalDate d = null;
            if (r.status() == ReminderStatus.SENT) {
                d = r.sendDate();
            } else if (r.status() == ReminderStatus.CANCELLED && r.cancelReason() != null
                    && switch (r.cancelReason()) {
                        case SKIPPED_CAP, EXPIRED_DEFER, MISSED_RUN, SKIPPED_SCHEDULED_PUSH, OLD_APP -> true;
                        default -> false;
                    }) {
                // 排队行的原定日是上线日，真实尝试日是（逐日后移的）发送日。
                d = r.origin() == ReminderOrigin.LAUNCH_QUEUE ? r.sendDate() : r.dueDate();
            }
            if (d != null && (best == null || d.isAfter(best))) {
                best = d;
            }
        }
        return best;
    }
}
