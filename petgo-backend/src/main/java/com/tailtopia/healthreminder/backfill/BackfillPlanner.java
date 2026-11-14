package com.tailtopia.healthreminder.backfill;

import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.RecordOrigin;
import com.tailtopia.profile.domain.VaccinePreset;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.NextDateRules;
import com.tailtopia.profile.health.PetHealthSnapshot;
import com.tailtopia.profile.health.RecordDraft;
import com.tailtopia.profile.health.VaccineKeywordMatcher;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 上线回填的决策（V1.5.0 Story 1.3 · AC1，PRD FR-168 Backfill / AC50–AC53，架构增量 §4.7「部署时」）。纯函数：
 * 「一只宠物的档案 + 全部记录 → 要写的字段」，Runner 只负责分批读写。
 *
 * <p>只处理 {@code record_origin = BACKFILL} 且该字段还没被回填过的行（天然幂等）：
 * <ul>
 *   <li>疫苗：预设为空且物种 CAT/DOG → 关键词匹配（命中写预设，否则 OTHER；{@code vaccine_name} 原文不动）；</li>
 *   <li>有预设（非 OTHER）、Next date 为空的疫苗 → 系列规则（剂次未知 → 空剂次分支）；</li>
 *   <li>驱虫：类型保持空（Legacy），CAT/DOG 的 Next date = event + 3 个月；</li>
 *   <li>发情：仅未绝育母狗的周期首条写平均间隔规则结果；CUSTOM / NEUTER / OTHER 物种不写；</li>
 *   <li>有任一存量 NEUTER 记录的宠物 → 绝育状态置 NEUTERED。</li>
 * </ul>
 */
public final class BackfillPlanner {

    /** 一条记录要写的字段；null = 不改。 */
    public record RecordUpdate(long recordId, VaccinePreset setPreset, LocalDate setNextDate) {
    }

    /** 一只宠物的回填方案。 */
    public record Plan(List<RecordUpdate> records, boolean markNeutered) {

        public boolean isEmpty() {
            return records.isEmpty() && !markNeutered;
        }
    }

    private BackfillPlanner() {
    }

    public static Plan plan(PetHealthSnapshot pet, List<HealthRecordSnapshot> records) {
        // 只认存量（BACKFILL）的绝育记录：上线后用户自己改的绝育状态不被下一次启动回填改回去。
        boolean markNeutered = !pet.isNeutered() && records.stream()
                .anyMatch(r -> r.type() == HealthRecordType.NEUTER && r.origin() == RecordOrigin.BACKFILL);
        // 绝育在同一次回填里生效：发情预填按「已绝育」判（不写发情 Next date）。
        PetHealthSnapshot effectivePet = markNeutered ? neutered(pet) : pet;
        List<RecordUpdate> updates = new ArrayList<>();
        if (!pet.isCatOrDog()) {
            return new Plan(updates, markNeutered);
        }
        for (HealthRecordSnapshot r : records) {
            if (r.origin() != RecordOrigin.BACKFILL) {
                continue;
            }
            VaccinePreset setPreset = null;
            VaccinePreset preset = r.vaccinePreset();
            if (r.type() == HealthRecordType.VACCINE && preset == null) {
                setPreset = VaccineKeywordMatcher.match(r.vaccineName(), pet.petType());
                preset = setPreset;
            }
            LocalDate setNext = null;
            if (r.nextDate() == null && prefillable(r.type())) {
                setNext = NextDateRules.defaultNextDate(effectivePet, RecordDraft.of(r, preset), records);
            }
            if (setPreset != null || setNext != null) {
                updates.add(new RecordUpdate(r.id(), setPreset, setNext));
            }
        }
        return new Plan(updates, markNeutered);
    }

    private static boolean prefillable(HealthRecordType type) {
        return type == HealthRecordType.VACCINE || type == HealthRecordType.DEWORM
                || type == HealthRecordType.MENSTRUATION;
    }

    private static PetHealthSnapshot neutered(PetHealthSnapshot p) {
        return new PetHealthSnapshot(p.petId(), p.ownerId(), p.petType(), p.sex(), p.birthday(),
                com.tailtopia.profile.domain.NeuterStatus.NEUTERED, p.catInHome(), p.pregnantNursing(),
                p.createdDate(), p.status(), p.createdAt(), p.sexSetAt());
    }
}
