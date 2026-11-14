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
}
