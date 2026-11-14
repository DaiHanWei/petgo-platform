package com.tailtopia.admin.usermgmt.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSpecies;
import com.tailtopia.profile.domain.PetSpeciesChangeSource;
import com.tailtopia.profile.event.PetSpeciesChangedEvent;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.MilestoneTrackSwitcher;
import com.tailtopia.profile.service.PetSpeciesChangeLog;
import com.tailtopia.profile.service.PetWriteGuard;
import com.tailtopia.shared.error.AppException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营代改宠物类型（V1.5.0 pet-species Story 2.1 · AB-42 · 架构 D-14）。
 *
 * <ul>
 *   <li>品种清空（运营不填品种，2026-11-13 定）；</li>
 *   <li>🔴 <b>不写、不改 {@code pet_type_changed_at}</b>：不占用户的 90 天次数、不重置用户冷却；</li>
 *   <li>大类改变 → 换里程碑 / 新手任务清单（运营应用户要求代改算用户主动修改，PRD 3.3.3；迁移才不换）；</li>
 *   <li>修改记录 source = ADMIN（操作人 + 原因）；审计 summary 🔒 <b>不含原因</b>（可能有工单号）；</li>
 *   <li>只读宠物同样拒（{@code PetWriteGuard}，待拍板 Q-6 默认）；提交后埋点 {@code pet_type_changed}（source = admin）。</li>
 * </ul>
 * 日志不记原因、不记宠物名。
 */
@Service
public class AdminPetSpeciesService {

    /** 原因上限（同 {@code pet_species_changes.reason}）。 */
    public static final int REASON_MAX = 200;

    private final PetProfileRepository profiles;
    private final PetWriteGuard writeGuard;
    private final MilestoneTrackSwitcher switcher;
    private final PetSpeciesChangeLog changeLog;
    private final AdminAuditService audit;
    private final ApplicationEventPublisher events;

    public AdminPetSpeciesService(PetProfileRepository profiles, PetWriteGuard writeGuard,
            MilestoneTrackSwitcher switcher, PetSpeciesChangeLog changeLog, AdminAuditService audit,
            ApplicationEventPublisher events) {
        this.profiles = profiles;
        this.writeGuard = writeGuard;
        this.switcher = switcher;
        this.changeLog = changeLog;
        this.audit = audit;
        this.events = events;
    }

    /** 弹层预览用：宠物须属于该用户（否则 404），且非只读（否则 409，弹层不打开，复审 2.1 #1）。 */
    @Transactional(readOnly = true)
    public PetProfile requireOwnedPet(long userId, long petId) {
        PetProfile pet = profiles.findByIdAndOwnerId(petId, userId)
                .orElseThrow(() -> AppException.notFound("宠物不存在").code("admin.err.petSpecies.petNotFound"));
        writeGuard.requireWritable(pet);
        return pet;
    }

    /**
     * 代改。
     *
     * @return 改后的宠物
     */
    @Transactional
    public PetProfile change(long adminId, long userId, long petId, String rawSpecies, String rawReason) {
        // 行锁（同用户改类型的 D-8 锁）且作为本事务第一次读这只宠物（之后不会拿到一级缓存里的旧快照）；
        // 归属先于一切：不属于该用户的 petId 一律 404。
        PetProfile pet = profiles.findByIdForUpdate(petId)
                .filter(p -> p.getOwnerId() != null && p.getOwnerId() == userId)
                .orElseThrow(() -> AppException.notFound("宠物不存在").code("admin.err.petSpecies.petNotFound"));
        // 只读先于输入校验：只读是确定结局，不让运营先补原因再被拒（复审 2.1 #3）。
        writeGuard.requireWritable(pet);
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.isEmpty()) {
            throw AppException.validation("请填写修改原因").code("admin.err.petSpecies.reasonRequired");
        }
        if (reason.length() > REASON_MAX) {
            throw AppException.validation("修改原因最多 200 字").code("admin.err.petSpecies.reasonTooLong");
        }
        PetSpecies target = PetSpecies.parse(rawSpecies)
                .orElseThrow(() -> AppException.validation("宠物类型不合法").code("admin.err.petSpecies.invalid"));
        PetSpecies old = pet.getPetSpecies();
        if (old == target) {
            throw AppException.validation("新类型与当前相同").code("admin.err.petSpecies.same");
        }
        boolean groupChanged = old.group() != target.group();
        pet.applySpecies(target);
        pet.setBreed(null);
        if (groupChanged) {
            switcher.switchTo(pet, target.group());
        }
        changeLog.append(petId, old, target, PetSpeciesChangeSource.ADMIN, adminId, reason);
        audit.record(adminId, AuditActions.PET_SPECIES_CHANGED, "PET", String.valueOf(petId),
                old.name() + "→" + target.name() + " user=" + userId);
        events.publishEvent(new PetSpeciesChangedEvent(pet.getOwnerId(), petId, old.name(), target.name(),
                groupChanged, PetSpeciesChangedEvent.SOURCE_ADMIN));
        return pet;
    }
}
