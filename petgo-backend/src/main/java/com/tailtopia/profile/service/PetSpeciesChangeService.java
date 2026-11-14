package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSpecies;
import com.tailtopia.profile.domain.PetSpeciesChangeSource;
import com.tailtopia.profile.event.PetSpeciesChangedEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 宠物类型修改（V1.5.0 pet-species Story 1.2 · D-8）。在编辑档案事务里调用。
 *
 * <ul>
 *   <li>新值 = 当前值 → 不算修改；</li>
 *   <li>对宠物行加悲观写锁并重读，以锁后的值判定（防两台设备同时改绕过冷却）；</li>
 *   <li>{@code typeChanged}（宫格项变）→ 校验 90 天冷却（冷却中抛 409，整次请求回滚）、写 {@code pet_type_changed_at}、
 *       请求没带品种时清空品种；「其他」组内换物种不占冷却、不清品种；</li>
 *   <li>{@code groupChanged}（大类变）→ 里程碑清单切换；</li>
 *   <li>记修改记录（USER），提交后埋点 {@code pet_type_changed}。</li>
 * </ul>
 */
@Service
public class PetSpeciesChangeService {

    private final EntityManager em;
    private final MilestoneTrackSwitcher switcher;
    private final PetSpeciesChangeLog changeLog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public PetSpeciesChangeService(EntityManager em, MilestoneTrackSwitcher switcher, PetSpeciesChangeLog changeLog,
            ApplicationEventPublisher events) {
        this(em, switcher, changeLog, events, Clock.systemUTC());
    }

    PetSpeciesChangeService(EntityManager em, MilestoneTrackSwitcher switcher, PetSpeciesChangeLog changeLog,
            ApplicationEventPublisher events, Clock clock) {
        this.em = em;
        this.switcher = switcher;
        this.changeLog = changeLog;
        this.events = events;
        this.clock = clock;
    }

    /**
     * 用户改类型。
     *
     * @param breedAbsent 请求没带 {@code breed}（null）——类型变了时据此清空品种
     * @return 是否真的改了（新值 ≠ 锁后的当前值）
     * @throws PetSpeciesCooldownException 改了宫格项且仍在冷却中
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean applyUserChange(PetProfile profile, PetSpecies newSpecies, boolean breedAbsent) {
        if (newSpecies == profile.getPetSpecies()) {
            return false;
        }
        // 悲观锁 + 重读：此刻 profile 尚无任何改动（在其他字段之前调用），refresh 不会丢用户输入。
        em.refresh(profile, LockModeType.PESSIMISTIC_WRITE);
        PetSpecies old = profile.getPetSpecies();
        if (newSpecies == old) {
            return false;
        }
        boolean typeChanged = old.pickerType() != newSpecies.pickerType();
        boolean groupChanged = old.group() != newSpecies.group();
        Instant now = clock.instant();
        if (typeChanged) {
            LocalDate from = PetSpeciesCooldown.changeableFromOrNull(profile,
                    LocalDate.ofInstant(now, com.tailtopia.shared.schedule.ScheduleWindow.WIB));
            if (from != null) {
                throw new PetSpeciesCooldownException(from);
            }
        }
        profile.applySpecies(newSpecies);
        if (typeChanged) {
            profile.markTypeChangedAt(now);
            if (breedAbsent) {
                profile.setBreed(null);
            }
        }
        if (groupChanged) {
            switcher.switchTo(profile, newSpecies.group());
        }
        changeLog.append(profile.getId(), old, newSpecies, PetSpeciesChangeSource.USER, null, null);
        events.publishEvent(new PetSpeciesChangedEvent(profile.getOwnerId(), profile.getId(), old.name(),
                newSpecies.name(), groupChanged, PetSpeciesChangedEvent.SOURCE_USER));
        return true;
    }
}
