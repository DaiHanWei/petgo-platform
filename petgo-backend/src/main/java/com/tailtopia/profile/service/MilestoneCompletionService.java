package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.MilestoneAutoCompleteMap;
import com.tailtopia.profile.domain.MilestoneAutoEvent;
import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.domain.MilestoneCompletion;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.domain.MilestoneDefinition;
import com.tailtopia.profile.domain.PetMilestone;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.event.MilestoneCompletedEvent;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 里程碑完成写入服务（Story 8.3，FR-42）。**幂等、不可撤销**：以 {@code milestone_completions}
 * 唯一约束（pet_milestone_id）为单一事实源——同一里程碑至多一条完成行，重复/并发安全。
 *
 * <p><b>寻址一律用完整 code</b>（V1.3.0 Story 1.1 · AD-A4）：自动完成的调用方传
 * {@link MilestoneAutoEvent}，由 {@link MilestoneAutoCompleteMap} 按物种查出完整 code；打卡路径本就
 * 持有完整 code，直接传入。**不再有「物种前缀 + 语义后缀」的拼接** —— 那套寻址默认三张清单同号位
 * 含义相同，而通用清单只有 16 项、猫狗各 31 项，一次造出五处线上错误。该宠物清单无此节点 → no-op。
 *
 * <p>健康组合依赖（C-L4/D-L4 = M3+M4+M5）：每次完成后若该 code 是某 combo 前置，且前置全完成 →
 * 自动解锁 combo 节点（SYSTEM_AUTO）。供 8.3 事件订阅与 8.4 用户打卡共用。
 *
 * <p>护栏：无 MQ/缓存；完成由 {@code @TransactionalEventListener}/{@code @Async} 订阅既有领域事件
 * （8.3 监听器）或用户打卡（8.4）调入。
 */
@Service
public class MilestoneCompletionService {

    private static final Logger log = LoggerFactory.getLogger(MilestoneCompletionService.class);

    private final PetProfileRepository profiles;
    private final PetMilestoneRepository milestones;
    private final MilestoneCompletionRepository completions;
    private final HealthRecordRepository healthRecords;
    private final ApplicationEventPublisher events;

    public MilestoneCompletionService(PetProfileRepository profiles, PetMilestoneRepository milestones,
            MilestoneCompletionRepository completions, HealthRecordRepository healthRecords,
            ApplicationEventPublisher events) {
        this.profiles = profiles;
        this.milestones = milestones;
        this.completions = completions;
        this.healthRecords = healthRecords;
        this.events = events;
    }

    /**
     * 按档案 owner + **自动事件**幂等完成（系统自动类无关联内容）。返回是否**新**完成。
     * 事件 → code 由 {@link MilestoneAutoCompleteMap} 按物种显式列举，该物种无对应节点 → no-op。
     */
    @Transactional
    public boolean completeForOwner(long ownerId, MilestoneAutoEvent event,
            MilestoneCompletionSource source) {
        Optional<PetProfile> profile = profiles.findByOwnerId(ownerId);
        if (profile.isEmpty()) {
            return false;
        }
        PetProfile p = profile.get();
        return complete(p.getId(), p.getPetType(), event, source, null);
    }

    /** 按档案 owner + **完整 code** 幂等完成，可带关联成长日历内容（用户打卡 8.4）。返回是否**新**完成。 */
    @Transactional
    public boolean completeCodeForOwner(long ownerId, String code, MilestoneCompletionSource source,
            Long linkedContentId) {
        Optional<PetProfile> profile = profiles.findByOwnerId(ownerId);
        if (profile.isEmpty()) {
            return false;
        }
        PetProfile p = profile.get();
        return complete(p.getId(), p.getPetType(), code, source, linkedContentId);
    }

    /** 直接按 petProfileId + petType + **自动事件**完成（定时扫描 / 内部复用）。 */
    @Transactional
    public boolean complete(long petProfileId, PetType petType, MilestoneAutoEvent event,
            MilestoneCompletionSource source, Long linkedContentId) {
        String code = MilestoneAutoCompleteMap.codeOf(petType, event);
        if (code == null) {
            return false; // 该物种清单无对应节点（显式声明，非遗漏）。
        }
        return complete(petProfileId, petType, code, source, linkedContentId);
    }

    /** 直接按 petProfileId + petType + **完整 code** 完成（内部 / combo 复用）。 */
    @Transactional
    public boolean complete(long petProfileId, PetType petType, String code,
            MilestoneCompletionSource source, Long linkedContentId) {
        Optional<PetMilestone> row = milestones.findByPetProfileIdAndCode(petProfileId, code);
        if (row.isEmpty()) {
            // roster 里没有这一行。正常情况下走不到：自动路径的 code 出自映射表、已被
            // MilestoneAutoCompleteMapTest 逐条比对过该物种的清单；打卡路径在上游已查过 roster。
            // 真走到这里说明 roster 与编译期目录走散了 —— 仍按 no-op 处理，不抛。
            return false;
        }
        PetMilestone m = row.get();
        if (completions.existsByPetMilestoneId(m.getId())) {
            return false; // 已完成 → 幂等不重复、不可撤销。
        }
        try {
            completions.save(MilestoneCompletion.of(m.getId(), source, linkedContentId));
        } catch (DataIntegrityViolationException e) {
            // 并发双投递：唯一约束（pet_milestone_id / linked_content_id）兜底 → 视为已完成。
            return false;
        }
        log.info("milestone completed: code={} source={}", code, source); // 不落 PII/健康内容
        // 完成领域事件（Story 8.6）：notify 订阅，L 级 → MILESTONE_NODE 达成推送 + 通知中心 6.6 真数据。
        MilestoneDefinition def = MilestoneCatalog.byCode(code);
        events.publishEvent(new MilestoneCompletedEvent(
                resolveOwnerId(petProfileId), code, m.getLevel(),
                def != null ? def.titleZh() : code, source));
        maybeUnlockHealthCombo(petProfileId, petType, code);
        // Lulus Pemula 聚合（7.3）：S1–S5 任一新完成后，若 6 新手任务全达成则解锁。
        if (MilestoneCatalog.isNewbiePrereq(code)) {
            checkAndUnlockLulusPemula(petProfileId, petType);
        }
        return true;
    }

    /**
     * 聚合里程碑「Lulus Pemula」运行时解锁入口（Story 7.3）：由健康记录创建事件触发
     * （{@link MilestoneAutoCompleteListener#onHealthRecordCreated}）——健康记录任务可能是最后一块。
     * 单宠假设：owner→当前档案。幂等（{@link #complete} 唯一约束短路）。
     */
    @Transactional
    public void maybeUnlockLulusPemulaForOwner(long ownerId) {
        profiles.findByOwnerId(ownerId)
                .ifPresent(p -> checkAndUnlockLulusPemula(p.getId(), p.getPetType()));
    }

    /**
     * 6 新手任务全达成判定：S1–S5 里程碑全完成 **且** 该宠物有 ≥1 条健康记录 → 完成 Lulus Pemula。
     * 未全达成 → no-op。第 6 任务（健康记录）非里程碑节点，故内联存在性判定（不入 combo map）。
     */
    private void checkAndUnlockLulusPemula(long petProfileId, PetType petType) {
        boolean allMilestonePrereqs = MilestoneCatalog.newbiePrereqCodes(petType).stream()
                .allMatch(code -> milestones
                        .findByPetProfileIdAndCode(petProfileId, code)
                        .map(pm -> completions.existsByPetMilestoneId(pm.getId()))
                        .orElse(false));
        if (!allMilestonePrereqs) {
            return;
        }
        if (!healthRecords.existsByPetProfileId(petProfileId)) {
            return;
        }
        complete(petProfileId, petType, MilestoneCatalog.lulusPemulaCode(petType),
                MilestoneCompletionSource.SYSTEM_AUTO, null);
    }

    /**
     * 计数类自动完成（成长日历记录数阈值）：首张照片（≥1）、满 10 条、满 30 条。
     * 由发布成长日历事件触发，传入该宠物当前成长日历总数。
     *
     * <p>⚠️ 满 10 条对通用宠物是 <b>G-M4</b>（不是 M10 —— 通用清单没有 M10，旧寻址指空，
     * 这条合法路径从上线至今是死的）；满 30 条通用清单无节点。映射见 {@link MilestoneAutoCompleteMap}。
     */
    @Transactional
    public void onGrowthMomentCount(long ownerId, long growthMomentCount) {
        if (growthMomentCount >= 1) {
            completeForOwner(ownerId, MilestoneAutoEvent.GROWTH_MOMENT_FIRST,
                    MilestoneCompletionSource.SYSTEM_AUTO);
        }
        if (growthMomentCount >= 10) {
            completeForOwner(ownerId, MilestoneAutoEvent.GROWTH_MOMENT_10,
                    MilestoneCompletionSource.SYSTEM_AUTO);
        }
        if (growthMomentCount >= 30) {
            completeForOwner(ownerId, MilestoneAutoEvent.GROWTH_MOMENT_30,
                    MilestoneCompletionSource.SYSTEM_AUTO);
        }
    }

    /**
     * 「系统推送 + 用户当天发布」L 级节点的发布回填（Story 8.6 · FR-42）：发布成长日历记录时，若已达
     * 对应节点时点则完成 —— 第一个生日 L1（生日当天 month/day 命中）、陪伴满 100 天 L2、满 365 天 L3。
     * 幂等（completeForOwner 短路）；source=PUBLISH。
     */
    @Transactional
    public void completeDateGatedLNodesOnPublish(long ownerId) {
        PetProfile p = profiles.findByOwnerId(ownerId).orElse(null);
        if (p == null) {
            return;
        }
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        java.time.LocalDate birthday = p.getBirthday();
        if (birthday != null && birthday.getMonthValue() == today.getMonthValue()
                && birthday.getDayOfMonth() == today.getDayOfMonth()) {
            completeForOwner(ownerId, MilestoneAutoEvent.FIRST_BIRTHDAY,
                    MilestoneCompletionSource.PUBLISH);
        }
        if (p.getCreatedAt() != null) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(
                    p.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate(), today);
            if (days >= 100) {
                completeForOwner(ownerId, MilestoneAutoEvent.COMPANION_100_DAYS,
                        MilestoneCompletionSource.PUBLISH);
            }
            if (days >= 365) {
                completeForOwner(ownerId, MilestoneAutoEvent.COMPANION_365_DAYS,
                        MilestoneCompletionSource.PUBLISH);
            }
        }
    }

    /** 健康组合依赖：完成的 code 若为某 combo 前置且前置全完成 → 解锁 combo 节点（SYSTEM_AUTO）。 */
    private void maybeUnlockHealthCombo(long petProfileId, PetType petType, String completedCode) {
        for (Map.Entry<String, Set<String>> combo : MilestoneCatalog.HEALTH_COMBO.entrySet()) {
            String comboCode = combo.getKey();
            Set<String> prereqs = combo.getValue();
            if (!prereqs.contains(completedCode)) {
                continue;
            }
            boolean allDone = prereqs.stream().allMatch(pc -> milestones
                    .findByPetProfileIdAndCode(petProfileId, pc)
                    .map(pm -> completions.existsByPetMilestoneId(pm.getId()))
                    .orElse(false));
            if (allDone) {
                complete(petProfileId, petType, comboCode,
                        MilestoneCompletionSource.SYSTEM_AUTO, null);
            }
        }
    }

    /** 由 petProfileId 反查 owner user id（完成事件携带，notify 按 owner 推送）。 */
    private long resolveOwnerId(long petProfileId) {
        return profiles.findById(petProfileId).map(PetProfile::getOwnerId).orElse(0L);
    }

    // ⚠️ 这里曾有 prefixOf(PetType) / suffixOf(String) 两个辅助方法，AD-A4 已把它们连根拔掉：
    // 「物种前缀 + 语义后缀」拼 code 正是五处线上错位的根因。需要 code 就查
    // MilestoneAutoCompleteMap（自动事件）或由调用方直接传完整 code，**不要把它们加回来**。
}
