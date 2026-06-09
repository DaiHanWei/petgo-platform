package com.petgo.profile.service;

import com.petgo.profile.domain.MilestoneCompletion;
import com.petgo.profile.domain.MilestoneDefinition;
import com.petgo.profile.domain.MilestoneLevel;
import com.petgo.profile.domain.PetMilestone;
import com.petgo.profile.domain.PetProfile;
import com.petgo.profile.domain.PetType;
import com.petgo.profile.dto.MilestoneGroupResponse;
import com.petgo.profile.dto.MilestoneItemResponse;
import com.petgo.profile.dto.MilestoneListResponse;
import com.petgo.profile.repository.MilestoneCompletionRepository;
import com.petgo.profile.repository.PetMilestoneRepository;
import com.petgo.profile.repository.PetProfileRepository;
import com.petgo.shared.error.AppException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 里程碑服务（Story 8.1，FR-42 / 决策 F16）。归 profile 域。
 *
 * <p>职责：① 建档时按 {@link com.petgo.profile.domain.MilestoneCatalog} 按 pet_type 物化 roster
 * （{@link #assignRoster}，幂等）；② 组装列表页 L/M/S 分区进度响应（{@link #getMilestones}）。
 * 清单为后端固定常量，本服务不写运营可编辑逻辑（护栏）。
 */
@Service
public class MilestoneService {

    /** 展示分区顺序：L → M → S（FR-42 列表页）。 */
    private static final List<MilestoneLevel> DISPLAY_ORDER = List.of(
            MilestoneLevel.L, MilestoneLevel.M, MilestoneLevel.S);

    private final PetProfileRepository profiles;
    private final PetMilestoneRepository milestones;
    private final MilestoneCompletionRepository completions;

    public MilestoneService(PetProfileRepository profiles, PetMilestoneRepository milestones,
            MilestoneCompletionRepository completions) {
        this.profiles = profiles;
        this.milestones = milestones;
        this.completions = completions;
    }

    /**
     * 建档时按 pet_type 物化 roster（幂等）。已存在 roster → 跳过（唯一约束兜底并发/重复建档）。
     * 由 {@link ProfileService#create} 同模块直调（非事件订阅）；存量档案经 {@link #getMilestones} lazy 兜底。
     */
    @Transactional
    public void assignRoster(long petProfileId, PetType petType) {
        if (milestones.existsByPetProfileId(petProfileId)) {
            return;
        }
        List<PetMilestone> roster = new ArrayList<>();
        for (MilestoneDefinition def : com.petgo.profile.domain.MilestoneCatalog.forType(petType)) {
            roster.add(PetMilestone.of(petProfileId, def));
        }
        try {
            milestones.saveAll(roster);
        } catch (DataIntegrityViolationException e) {
            // 并发双建窗：唯一约束 (pet_profile_id, code) 兜底，已被另一事务物化 → 视为成功。
        }
    }

    /** 当前用户里程碑列表（无档案 → 404）。读路径若 roster 缺失（存量档案）则 lazy 物化兜底。 */
    @Transactional
    public MilestoneListResponse getMilestones(long ownerId) {
        PetProfile pet = profiles.findByOwnerId(ownerId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
        long petProfileId = pet.getId();

        List<PetMilestone> roster = milestones.findByPetProfileIdOrderBySortOrderAsc(petProfileId);
        if (roster.isEmpty()) {
            assignRoster(petProfileId, pet.getPetType());
            roster = milestones.findByPetProfileIdOrderBySortOrderAsc(petProfileId);
        }

        Set<Long> completedMilestoneIds = new java.util.HashSet<>();
        Map<Long, java.time.Instant> completedAtById = new java.util.HashMap<>();
        for (MilestoneCompletion c : completions.findByPetMilestoneIdIn(
                roster.stream().map(PetMilestone::getId).toList())) {
            completedMilestoneIds.add(c.getPetMilestoneId());
            completedAtById.put(c.getPetMilestoneId(), c.getCompletedAt());
        }

        // 按级别分组（保持 sortOrder 顺序），再按 L/M/S 展示序拼装。
        Map<MilestoneLevel, List<MilestoneItemResponse>> byLevel = new LinkedHashMap<>();
        Map<MilestoneLevel, Integer> doneByLevel = new LinkedHashMap<>();
        for (MilestoneLevel lvl : DISPLAY_ORDER) {
            byLevel.put(lvl, new ArrayList<>());
            doneByLevel.put(lvl, 0);
        }

        int totalDone = 0;
        for (PetMilestone m : roster) {
            MilestoneDefinition def = com.petgo.profile.domain.MilestoneCatalog.byCode(m.getCode());
            String title = def != null ? def.titleZh() : m.getCode();
            boolean completed = completedMilestoneIds.contains(m.getId());
            if (completed) {
                totalDone++;
                doneByLevel.merge(m.getLevel(), 1, Integer::sum);
            }
            byLevel.get(m.getLevel()).add(new MilestoneItemResponse(
                    m.getCode(), title, m.getLevel().name(), m.getTriggerType().name(),
                    completed, completed ? completedAtById.get(m.getId()) : null));
        }

        List<MilestoneGroupResponse> groups = new ArrayList<>();
        for (MilestoneLevel lvl : DISPLAY_ORDER) {
            List<MilestoneItemResponse> items = byLevel.get(lvl);
            groups.add(new MilestoneGroupResponse(
                    lvl.name(), doneByLevel.get(lvl), items.size(), items));
        }

        return new MilestoneListResponse(
                pet.getName(), pet.getAvatarUrl(), totalDone, roster.size(), groups);
    }
}
