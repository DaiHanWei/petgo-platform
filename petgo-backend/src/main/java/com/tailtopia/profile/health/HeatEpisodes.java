package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetType;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发情周期分组（V1.5.0 Story 1.2，PRD FR-171、词表 Heat episode）。纯函数。
 *
 * <p>按 event date（同日 created_at、id）升序：第一条开一个周期；与<b>本周期首条</b>相差 &lt; 10 天（CAT）/
 * &lt; 21 天（DOG 及其他物种）的归入；否则开新周期（不链式延长）。
 */
public final class HeatEpisodes {

    /** 一个周期：首条 + 全部成员（含首条，时间升序）。 */
    public record Episode(HealthRecordSnapshot first, List<HealthRecordSnapshot> members) {

        public LocalDate firstDay() {
            return first.eventDate();
        }
    }

    private final List<Episode> episodes;
    private final Map<Long, Episode> byRecordId;

    private HeatEpisodes(List<Episode> episodes) {
        this.episodes = List.copyOf(episodes);
        Map<Long, Episode> map = new LinkedHashMap<>();
        for (Episode e : episodes) {
            for (HealthRecordSnapshot m : e.members()) {
                map.put(m.id(), e);
            }
        }
        this.byRecordId = map;
    }

    /** 周期窗口：CAT 10 天，其余 21 天。 */
    public static int windowDays(PetType petType) {
        return petType == PetType.CAT ? 10 : 21;
    }

    /** 从任意记录集合里取 MENSTRUATION 记录分周期（其它类型忽略）。 */
    public static HeatEpisodes of(List<HealthRecordSnapshot> records, PetType petType) {
        int window = windowDays(petType);
        List<HealthRecordSnapshot> heats = records.stream()
                .filter(r -> r.type() == HealthRecordType.MENSTRUATION && r.eventDate() != null)
                .sorted(HealthRecordSnapshot::compareChronologically)
                .toList();
        List<Episode> out = new ArrayList<>();
        HealthRecordSnapshot first = null;
        List<HealthRecordSnapshot> members = null;
        for (HealthRecordSnapshot r : heats) {
            if (first != null && ChronoUnit.DAYS.between(first.eventDate(), r.eventDate()) < window) {
                members.add(r);
                continue;
            }
            if (first != null) {
                out.add(new Episode(first, List.copyOf(members)));
            }
            first = r;
            members = new ArrayList<>(List.of(r));
        }
        if (first != null) {
            out.add(new Episode(first, List.copyOf(members)));
        }
        return new HeatEpisodes(out);
    }

    /** 全部周期（时间升序）。 */
    public List<Episode> episodes() {
        return episodes;
    }

    public Episode episodeOf(long recordId) {
        return byRecordId.get(recordId);
    }

    public boolean isFirst(long recordId) {
        Episode e = byRecordId.get(recordId);
        return e != null && e.first().id() == recordId;
    }

    /** 首日 ≤ {@code day} 的各周期首日（升序）。 */
    public List<LocalDate> firstDaysUpTo(LocalDate day) {
        return episodes.stream().map(Episode::firstDay).filter(d -> !d.isAfter(day))
                .sorted(Comparator.naturalOrder()).toList();
    }
}
