package com.petgo.profile.service;

import com.petgo.profile.domain.MilestoneCatalog;
import com.petgo.profile.domain.MilestoneCompletion;
import com.petgo.profile.domain.MilestoneCompletionSource;
import com.petgo.profile.domain.PetMilestone;
import com.petgo.profile.domain.PetProfile;
import com.petgo.profile.domain.PetType;
import com.petgo.profile.repository.MilestoneCompletionRepository;
import com.petgo.profile.repository.PetMilestoneRepository;
import com.petgo.profile.repository.PetProfileRepository;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 里程碑完成写入服务（Story 8.3，FR-42）。**幂等、不可撤销**：以 {@code milestone_completions}
 * 唯一约束（pet_milestone_id）为单一事实源——同一里程碑至多一条完成行，重复/并发安全。
 *
 * <p>清单按 pet_type 前缀解析（CAT→C / DOG→D / OTHER→G）：调用方传**语义后缀**（如 {@code "S1"}、
 * {@code "M10"}），本服务据档案类型拼出 code（{@code C-S1}）。该宠物清单无此节点（如 OTHER 无 S6）→ no-op。
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

    public MilestoneCompletionService(PetProfileRepository profiles, PetMilestoneRepository milestones,
            MilestoneCompletionRepository completions) {
        this.profiles = profiles;
        this.milestones = milestones;
        this.completions = completions;
    }

    /** 按档案 owner + 语义后缀幂等完成（系统自动类无关联内容）。返回是否**新**完成。 */
    @Transactional
    public boolean completeForOwner(long ownerId, String suffix, MilestoneCompletionSource source) {
        return completeForOwner(ownerId, suffix, source, null);
    }

    /** 按档案 owner + 语义后缀幂等完成，可带关联成长日历内容（用户打卡 8.4）。返回是否**新**完成。 */
    @Transactional
    public boolean completeForOwner(long ownerId, String suffix, MilestoneCompletionSource source,
            Long linkedContentId) {
        Optional<PetProfile> profile = profiles.findByOwnerId(ownerId);
        if (profile.isEmpty()) {
            return false;
        }
        PetProfile p = profile.get();
        return complete(p.getId(), p.getPetType(), suffix, source, linkedContentId);
    }

    /** 直接按 petProfileId + petType + 语义后缀完成（内部 / combo 复用）。 */
    @Transactional
    public boolean complete(long petProfileId, PetType petType, String suffix,
            MilestoneCompletionSource source, Long linkedContentId) {
        String code = prefixOf(petType) + "-" + suffix;
        Optional<PetMilestone> row = milestones.findByPetProfileIdAndCode(petProfileId, code);
        if (row.isEmpty()) {
            return false; // 该清单无此节点（如 OTHER 无 S6 洗澡）。
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
        maybeUnlockHealthCombo(petProfileId, petType, code);
        return true;
    }

    /**
     * 计数类自动完成（成长日历记录数阈值）：首张照片 S2（≥1）、满 10 条 M10、满 30 条 L5。
     * 由发布成长日历事件触发，传入该宠物当前成长日历总数。
     */
    @Transactional
    public void onGrowthMomentCount(long ownerId, long growthMomentCount) {
        if (growthMomentCount >= 1) {
            completeForOwner(ownerId, "S2", MilestoneCompletionSource.SYSTEM_AUTO);
        }
        if (growthMomentCount >= 10) {
            completeForOwner(ownerId, "M10", MilestoneCompletionSource.SYSTEM_AUTO);
        }
        if (growthMomentCount >= 30) {
            completeForOwner(ownerId, "L5", MilestoneCompletionSource.SYSTEM_AUTO);
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
                complete(petProfileId, petType, suffixOf(comboCode),
                        MilestoneCompletionSource.SYSTEM_AUTO, null);
            }
        }
    }

    /** pet_type → 清单 code 前缀（CAT→C / DOG→D / OTHER→G）。 */
    private static String prefixOf(PetType type) {
        return switch (type) {
            case CAT -> "C";
            case DOG -> "D";
            case OTHER -> "G";
        };
    }

    /** code（C-S1）→ 语义后缀（S1）。 */
    private static String suffixOf(String code) {
        int dash = code.indexOf('-');
        return dash >= 0 ? code.substring(dash + 1) : code;
    }
}
