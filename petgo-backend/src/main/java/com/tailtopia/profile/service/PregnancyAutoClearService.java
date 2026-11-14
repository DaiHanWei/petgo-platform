package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.event.PetHealthProfileChangedEvent;
import com.tailtopia.profile.health.PregnancyFlag;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 怀孕 / 哺乳标记自动清除（V1.5.0 Story 1.3 · AC3，PRD FR-162 Auto-clear，架构增量 §4.6）。由 Story 2.3 的日跑在第一步调用。
 *
 * <p>把「标记为是且自动关闭日 ≤ day」的宠物置否、清 {@code pregnant_set_at}，并为每只发布
 * {@link PetHealthProfileChangedEvent}（{@code pregnantCleared=true}，{@code changedAt} = day 的 00:00 WIB），
 * 监听方按恢复表处理（不走普通重建）。🔒 日志只记计数。
 */
@Service
public class PregnancyAutoClearService {

    private static final Logger log = LoggerFactory.getLogger(PregnancyAutoClearService.class);

    private final PetProfileRepository profiles;
    private final ApplicationEventPublisher events;

    public PregnancyAutoClearService(PetProfileRepository profiles, ApplicationEventPublisher events) {
        this.profiles = profiles;
        this.events = events;
    }

    /** @return 本次业务上由「是」变「否」的宠物数（公的残留落库值也会清，但不计入）。 */
    @Transactional
    public int clearExpired(LocalDate day) {
        return clearExpiredPets(day).size();
    }

    /** 同 {@link #clearExpired}，返回业务上「由是变否」的宠物 id（日跑据此同步走恢复处理）。 */
    @Transactional
    public java.util.List<Long> clearExpiredPets(LocalDate day) {
        java.util.List<Long> clearedPets = new java.util.ArrayList<>();
        Instant clearedAt = day.atStartOfDay(ScheduleWindow.WIB).toInstant();
        int rows = 0;
        for (PetProfile pet : profiles.findByPregnantNursingTrue()) {
            if (!PregnancyFlag.expiredOn(pet, day)) {
                continue;
            }
            boolean wasEffective = pet.isPregnantNursingEffective();
            pet.setPregnantNursing(false, clearedAt);
            profiles.save(pet);
            // 公的（业务上从未怀孕）只清落库值，不发「清除」事件，免得监听方对它走恢复补发。
            if (wasEffective) {
                events.publishEvent(new PetHealthProfileChangedEvent(pet.getOwnerId(), pet.getId(),
                        false, false, false, false, true, clearedAt, true));
                clearedPets.add(pet.getId());
            }
            rows++;
        }
        log.info("health pregnancy auto-clear day={} cleared={} rowsReset={}", day, clearedPets.size(), rows);
        return clearedPets;
    }
}
