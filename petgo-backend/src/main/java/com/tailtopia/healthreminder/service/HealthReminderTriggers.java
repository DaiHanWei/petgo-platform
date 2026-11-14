package com.tailtopia.healthreminder.service;

import com.tailtopia.profile.event.HealthRecordChangedEvent;
import com.tailtopia.profile.event.PetHealthProfileChangedEvent;
import com.tailtopia.profile.event.ProfileCreatedEvent;
import com.tailtopia.profile.slot.PetStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 健康提醒重建的触发（V1.5.0 Story 2.1 · AC5，架构增量 §4.3 触发表）。🔴 写库监听纪律：{@code AFTER_COMMIT} + {@code @Async}，
 * 内部调用<b>另一个 bean</b> 的 {@link HealthReminderRebuilder#rebuildPet}（REQUIRES_NEW），绝不自调用 {@code @Transactional}。
 * 监听异常只 warn（异常类名），不影响原业务事务。
 */
@Component
public class HealthReminderTriggers {

    private static final Logger log = LoggerFactory.getLogger(HealthReminderTriggers.class);

    private final HealthReminderRebuilder rebuilder;
    private final PregnancyResumeHandler resumeHandler;

    public HealthReminderTriggers(HealthReminderRebuilder rebuilder, PregnancyResumeHandler resumeHandler) {
        this.rebuilder = rebuilder;
        this.resumeHandler = resumeHandler;
    }

    /** 记录增 / 删 / 改（只改备注不重建，PRD FR-163 Rebuild）。 */
    @Async
    @TransactionalEventListener
    public void onHealthRecordChanged(HealthRecordChangedEvent e) {
        if (e.change() == HealthRecordChangedEvent.Change.UPDATED && !e.reminderRelevant()) {
            return;
        }
        RebuildTrigger trigger = e.change() == HealthRecordChangedEvent.Change.DELETED
                ? RebuildTrigger.recordDeleted(e.recordId())
                : RebuildTrigger.of("record_" + e.change().name().toLowerCase(java.util.Locale.ROOT));
        rebuild(e.petId(), trigger);
    }

    /** 档案：生日 / 性别 / 绝育 / 设为怀孕 → 重建；清除怀孕 → 恢复处理（不走普通重建）。 */
    @Async
    @TransactionalEventListener
    public void onPetHealthProfileChanged(PetHealthProfileChangedEvent e) {
        if (e.birthdayChanged() || e.sexChanged() || e.neuterChanged() || e.pregnantSetYes()) {
            rebuild(e.petId(), RebuildTrigger.of("profile_changed"));
        }
        if (e.pregnantCleared() && !e.autoCleared()) { // 自动清除由日跑同步走恢复表
            try {
                resumeHandler.onPregnancyCleared(e.petId(), e.changedAt());
            } catch (RuntimeException ex) {
                log.warn("health reminder resume failed petId={} error={}", e.petId(), ex.getClass().getSimpleName());
            }
        }
    }

    /** 建档（档案类建议由 2.2 生效）。 */
    @Async
    @TransactionalEventListener
    public void onProfileCreated(ProfileCreatedEvent e) {
        rebuild(e.petProfileId(), RebuildTrigger.of("profile_created"));
    }

    /** 只读 ↔ 正常。 */
    @Async
    @TransactionalEventListener
    public void onPetStatusChanged(PetStatusChangedEvent e) {
        rebuild(e.petId(), RebuildTrigger.of("pet_status_" + e.newStatus().name().toLowerCase(java.util.Locale.ROOT)));
    }

    /** 唯一索引冲突（并发写入同键）视为已有：整只重试一次。 */
    public void rebuild(long petId, RebuildTrigger trigger) {
        try {
            try {
                rebuilder.rebuildPet(petId, trigger);
            } catch (DataIntegrityViolationException dup) {
                rebuilder.rebuildPet(petId, trigger);
            }
        } catch (RuntimeException ex) {
            log.warn("health reminder rebuild failed petId={} trigger={} error={}", petId, trigger.source(),
                    ex.getClass().getSimpleName());
        }
    }
}
