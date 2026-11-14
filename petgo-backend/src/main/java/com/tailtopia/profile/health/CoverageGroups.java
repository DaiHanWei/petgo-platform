package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.VaccinePreset;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一只宠物全部记录的覆盖组视图（V1.5.0 Story 1.2，架构增量 §3.3，PRD 词表 Covers / Covered state）。纯函数、不可变。
 *
 * <p>「覆盖」单向且是<b>状态</b>：Combo 覆盖 Puppy DP；FLEA_WORM 覆盖 INTERNAL 与 EXTERNAL；同一宠物 INTERNAL 与 EXTERNAL
 * event date 相差 ≤3 天（成对，对的日期 = 两者较晚者）覆盖 FLEA_WORM；任意已分型驱虫覆盖 LEGACY。组 G 处于「被覆盖」⇔
 * 存在覆盖它的记录（或成对）其 event date ≥ G 最新记录的 event date；同日按 created_at（再按 id）晚者算「之后」，
 * 保证关系无环。
 */
public final class CoverageGroups {

    private static final int PAIR_DAYS = 3;

    private final PetType petType;
    private final List<HealthRecordSnapshot> records;
    private final Map<String, List<HealthRecordSnapshot>> byGroup;

    private CoverageGroups(List<HealthRecordSnapshot> records, PetType petType) {
        this.petType = petType;
        this.records = records.stream().sorted(HealthRecordSnapshot::compareChronologically).toList();
        Map<String, List<HealthRecordSnapshot>> map = new LinkedHashMap<>();
        for (HealthRecordSnapshot r : this.records) {
            map.computeIfAbsent(RecordKinds.groupOf(r, petType), k -> new ArrayList<>()).add(r);
        }
        this.byGroup = map;
    }

    public static CoverageGroups of(List<HealthRecordSnapshot> records, PetType petType) {
        return new CoverageGroups(records, petType);
    }

    public String groupOf(HealthRecordSnapshot r) {
        return RecordKinds.groupOf(r, petType);
    }

    /** 组内最新记录（event date → created_at → id 最大）。 */
    public Optional<HealthRecordSnapshot> latest(String group) {
        List<HealthRecordSnapshot> list = byGroup.get(group);
        return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
    }

    public boolean isLatest(HealthRecordSnapshot r) {
        return latest(groupOf(r)).map(l -> l.id() == r.id()).orElse(false);
    }

    public Map<String, List<HealthRecordSnapshot>> groups() {
        return byGroup;
    }

    /** 组被覆盖时返回「覆盖它的那条记录」（取最新的一条；成对取较晚者），否则空。 */
    public Optional<HealthRecordSnapshot> coveredBy(String group) {
        Optional<HealthRecordSnapshot> latest = latest(group);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        HealthRecordSnapshot since = latest.get();
        HealthRecordSnapshot best = null;
        for (HealthRecordSnapshot c : coveringCandidates(group)) {
            // 「覆盖记录 ≥ 组内最新」按完整时间序判（event date → created_at → id）：同日时后录入者覆盖先录入者。
            // 只看 event date 会让同日的 INTERNAL + EXTERNAL 与 FLEA_WORM 互相覆盖、三组全部 DONE、提醒全丢。
            if (HealthRecordSnapshot.compareChronologically(c, since) > 0 && (best == null
                    || HealthRecordSnapshot.compareChronologically(c, best) > 0)) {
                best = c;
            }
        }
        return Optional.ofNullable(best);
    }

    public boolean isCovered(String group) {
        return coveredBy(group).isPresent();
    }

    private List<HealthRecordSnapshot> coveringCandidates(String group) {
        List<HealthRecordSnapshot> out = new ArrayList<>();
        if (group.equals(RecordKinds.groupOfVaccine(VaccinePreset.PUPPY_DP))) {
            out.addAll(byGroup.getOrDefault(RecordKinds.groupOfVaccine(VaccinePreset.COMBO_DHPPI_L), List.of()));
        } else if (group.equals(RecordKinds.groupOfDeworm(DewormType.INTERNAL))
                || group.equals(RecordKinds.groupOfDeworm(DewormType.EXTERNAL))) {
            out.addAll(byGroup.getOrDefault(RecordKinds.groupOfDeworm(DewormType.FLEA_WORM), List.of()));
        } else if (group.equals(RecordKinds.groupOfDeworm(DewormType.FLEA_WORM))) {
            out.addAll(pairs());
        } else if (group.equals(RecordKinds.LEGACY_DEWORM)) {
            for (HealthRecordSnapshot r : records) {
                if (r.type() == HealthRecordType.DEWORM && r.dewormType() != null) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    /** INTERNAL + EXTERNAL 相差 ≤3 天的成对：每对以较晚的那条代表（其 event date = 对的日期）。 */
    private List<HealthRecordSnapshot> pairs() {
        List<HealthRecordSnapshot> internals = byGroup.getOrDefault(
                RecordKinds.groupOfDeworm(DewormType.INTERNAL), List.of());
        List<HealthRecordSnapshot> externals = byGroup.getOrDefault(
                RecordKinds.groupOfDeworm(DewormType.EXTERNAL), List.of());
        List<HealthRecordSnapshot> out = new ArrayList<>();
        for (HealthRecordSnapshot i : internals) {
            for (HealthRecordSnapshot e : externals) {
                if (Math.abs(ChronoUnit.DAYS.between(i.eventDate(), e.eventDate())) <= PAIR_DAYS) {
                    out.add(HealthRecordSnapshot.compareChronologically(i, e) >= 0 ? i : e);
                }
            }
        }
        return out;
    }

    /** Vet visit 的 Next date 已满足：存在另一条 Vet visit 其 event date ≥ Next date − 1 天。返回那条记录。 */
    public Optional<HealthRecordSnapshot> vetVisitSatisfiedBy(HealthRecordSnapshot visit) {
        if (visit.type() != HealthRecordType.VET_VISIT || visit.nextDate() == null) {
            return Optional.empty();
        }
        LocalDate threshold = visit.nextDate().minusDays(1);
        return records.stream()
                .filter(r -> r.type() == HealthRecordType.VET_VISIT && r.id() != visit.id())
                .filter(r -> !r.eventDate().isBefore(threshold))
                .min(HealthRecordSnapshot::compareChronologically);
    }
}
