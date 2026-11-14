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
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 里程碑 / 新手任务清单切换（V1.5.0 pet-species Story 1.2 · D-4）。用户改类型 / 运营代改且大类改变时，
 * 在改类型的同一事务里调用。
 *
 * <ol>
 *   <li>当前活跃 roster 行全部退役（<b>不删</b>：完成记录、徽章、分享页、时间线、已发成长值全保留）；</li>
 *   <li>按新清单：已有同 code 行（来回改过）复活，缺的新建；</li>
 *   <li><b>按事实补记</b>：同一自动事件在旧清单已完成、新清单对应项未完成 → 直接存
 *       {@link MilestoneCompletion#ofCelebrated}（Lulus Pemula 同理；打卡类无语义对应，不补）；</li>
 *   <li>健康组合节点（C-L4 / D-L4）前置在新清单全部完成 → 同样补记；</li>
 *   <li>改 {@code milestoneTrack}，再补账号级 flag（{@code applyExistingFlagsToPet} 按新清单算 code）。</li>
 * </ol>
 *
 * <p>🔴 <b>不发任何事件</b>（{@code MilestoneCompletedEvent} / {@code AccountMilestoneCompletedEvent}）：
 * 成长值与里程碑通知由事件驱动，补记的是「同一件事在另一套清单里的对应项」，再发就是重复发奖励。
 */
@Component
public class MilestoneTrackSwitcher {

    private final PetMilestoneRepository milestones;
    private final MilestoneCompletionRepository completions;
    private final AccountMilestoneService accountMilestones;
    private final Clock clock;

    @Autowired
    public MilestoneTrackSwitcher(PetMilestoneRepository milestones, MilestoneCompletionRepository completions,
            AccountMilestoneService accountMilestones) {
        this(milestones, completions, accountMilestones, Clock.systemUTC());
    }

    MilestoneTrackSwitcher(PetMilestoneRepository milestones, MilestoneCompletionRepository completions,
            AccountMilestoneService accountMilestones, Clock clock) {
        this.milestones = milestones;
        this.completions = completions;
        this.accountMilestones = accountMilestones;
        this.clock = clock;
    }

    /** 把这只宠物的清单切到 {@code newTrack}；与当前相同 → 直接返回。须在调用方事务内。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void switchTo(PetProfile pet, PetType newTrack) {
        PetType oldTrack = pet.getMilestoneTrack();
        if (newTrack == null || newTrack == oldTrack) {
            return;
        }
        long petId = pet.getId();
        Instant now = clock.instant();

        // 1. 全部 roster 行（含退役）按 code 索引；活跃行退役。
        Map<String, PetMilestone> byCode = new LinkedHashMap<>();
        for (PetMilestone m : milestones.findByPetProfileId(petId)) {
            byCode.put(m.getCode(), m);
            m.retire(now);
        }
        // 2. 新清单：已有同 code 行复活，缺的新建。
        List<PetMilestone> touched = new ArrayList<>(byCode.values());
        for (MilestoneDefinition def : MilestoneCatalog.forType(newTrack)) {
            PetMilestone existing = byCode.get(def.code());
            if (existing != null) {
                existing.revive();
            } else {
                PetMilestone created = PetMilestone.of(petId, def);
                touched.add(created);
            }
        }
        List<PetMilestone> saved = milestones.saveAll(touched);
        for (PetMilestone m : saved) {
            byCode.put(m.getCode(), m);
        }

        // 3. 按事实补记（不发事件）。
        Map<Long, MilestoneCompletion> doneByRow = new HashMap<>();
        List<Long> rowIds = byCode.values().stream().map(PetMilestone::getId).filter(id -> id != null).toList();
        if (!rowIds.isEmpty()) {
            for (MilestoneCompletion c : completions.findByPetMilestoneIdIn(rowIds)) {
                doneByRow.put(c.getPetMilestoneId(), c);
            }
        }
        List<MilestoneCompletion> backfill = new ArrayList<>();
        for (MilestoneAutoEvent e : MilestoneAutoEvent.values()) {
            carry(MilestoneAutoCompleteMap.codeOf(oldTrack, e), MilestoneAutoCompleteMap.codeOf(newTrack, e),
                    byCode, doneByRow, backfill);
        }
        carry(MilestoneCatalog.lulusPemulaCode(oldTrack), MilestoneCatalog.lulusPemulaCode(newTrack),
                byCode, doneByRow, backfill);
        carryHealthCombos(newTrack, byCode, doneByRow, backfill);
        if (!backfill.isEmpty()) {
            completions.saveAll(backfill);
        }

        // 4. 改清单归属，再按新清单补账号级 flag（它按 pet.getMilestoneTrack() 算 code，须在改之后）。
        pet.setMilestoneTrack(newTrack);
        accountMilestones.applyExistingFlagsToPet(pet);
    }

    /**
     * 健康组合节点（C-L4 / D-L4）：新清单里前置全已完成（含刚补记的）、组合节点未完成 → 按事实补记，时间取前置里最晚的。
     * 组合节点平时只在「某前置新完成」时检查，补记不经那条路径，不补就会永远差这一个。
     */
    private static void carryHealthCombos(PetType newTrack, Map<String, PetMilestone> byCode,
            Map<Long, MilestoneCompletion> doneByRow, List<MilestoneCompletion> out) {
        for (Map.Entry<String, java.util.Set<String>> combo : MilestoneCatalog.HEALTH_COMBO.entrySet()) {
            PetMilestone comboRow = byCode.get(combo.getKey());
            if (comboRow == null || comboRow.getId() == null || !comboRow.isActive()
                    || doneByRow.containsKey(comboRow.getId())) {
                continue;
            }
            Instant latest = null;
            boolean all = true;
            for (String pre : combo.getValue()) {
                PetMilestone r = byCode.get(pre);
                MilestoneCompletion c = r == null || r.getId() == null ? null : doneByRow.get(r.getId());
                if (c == null || !r.isActive()) {
                    all = false;
                    break;
                }
                if (latest == null || c.getCompletedAt().isAfter(latest)) {
                    latest = c.getCompletedAt();
                }
            }
            if (all && latest != null) {
                MilestoneCompletion c = MilestoneCompletion.ofCelebrated(comboRow.getId(),
                        MilestoneCompletionSource.SYSTEM_AUTO, latest);
                doneByRow.put(comboRow.getId(), c);
                out.add(c);
            }
        }
    }

    private static void carry(String oldCode, String newCode, Map<String, PetMilestone> byCode,
            Map<Long, MilestoneCompletion> doneByRow, List<MilestoneCompletion> out) {
        if (oldCode == null || newCode == null) {
            return;
        }
        PetMilestone oldRow = byCode.get(oldCode);
        PetMilestone newRow = byCode.get(newCode);
        if (oldRow == null || newRow == null || oldRow.getId() == null || newRow.getId() == null) {
            return;
        }
        MilestoneCompletion oldDone = doneByRow.get(oldRow.getId());
        if (oldDone == null || doneByRow.containsKey(newRow.getId())) {
            return;
        }
        MilestoneCompletion c = MilestoneCompletion.ofCelebrated(newRow.getId(), MilestoneCompletionSource.SYSTEM_AUTO,
                oldDone.getCompletedAt());
        doneByRow.put(newRow.getId(), c);
        out.add(c);
    }
}
