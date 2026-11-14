package com.tailtopia.healthreminder.rules;

import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.health.CoverageGroups;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.HeatEpisodes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 「来源记录的提醒已被满足」判定（V1.5.0 Story 2.1，架构增量 §4.3 第 2 / 4 步，SM-7）。纯函数。
 *
 * <p>来源记录所在覆盖组出现了更新的记录 → 那条；组被覆盖 → 覆盖它的记录；Vet visit → event date ≥ Next date − 1 的另一条；
 * 发情 → 出现了更新的发情周期首条。来源记录已不存在 → 空（由调用方按 record_deleted 处理）。
 */
public final class SatisfactionLookup {

    private final Map<Long, HealthRecordSnapshot> byId;
    private final CoverageGroups groups;
    private final HeatEpisodes heats;

    private SatisfactionLookup(List<HealthRecordSnapshot> records, PetType petType) {
        this.byId = records.stream().collect(Collectors.toMap(HealthRecordSnapshot::id, Function.identity()));
        this.groups = CoverageGroups.of(records, petType);
        this.heats = HeatEpisodes.of(records, petType);
    }

    public static SatisfactionLookup of(List<HealthRecordSnapshot> records, PetType petType) {
        return new SatisfactionLookup(records, petType);
    }

    public boolean exists(Long recordId) {
        return recordId != null && byId.containsKey(recordId);
    }

    /** @return 满足它的记录 id；未满足 / 来源不存在 → 空 */
    public Optional<Long> satisfiedBy(Long sourceRecordId) {
        HealthRecordSnapshot source = sourceRecordId == null ? null : byId.get(sourceRecordId);
        if (source == null) {
            return Optional.empty();
        }
        if (source.type() == HealthRecordType.VET_VISIT) {
            return groups.vetVisitSatisfiedBy(source).map(HealthRecordSnapshot::id);
        }
        if (source.type() == HealthRecordType.MENSTRUATION) {
            List<HeatEpisodes.Episode> eps = heats.episodes();
            if (eps.isEmpty()) {
                return Optional.empty();
            }
            HealthRecordSnapshot latestFirst = eps.get(eps.size() - 1).first();
            return latestFirst.id() != source.id()
                    && HealthRecordSnapshot.compareChronologically(latestFirst, source) > 0
                    ? Optional.of(latestFirst.id()) : Optional.empty();
        }
        String group = groups.groupOf(source);
        Optional<HealthRecordSnapshot> latest = groups.latest(group);
        if (latest.isPresent() && latest.get().id() != source.id()
                && HealthRecordSnapshot.compareChronologically(latest.get(), source) > 0) {
            return Optional.of(latest.get().id());
        }
        return groups.coveredBy(group).map(HealthRecordSnapshot::id);
    }
}
