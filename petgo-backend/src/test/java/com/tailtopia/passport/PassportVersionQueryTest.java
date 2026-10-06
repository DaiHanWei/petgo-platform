package com.tailtopia.passport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.passport.domain.FrozenStamp;
import com.tailtopia.passport.domain.PassportSnapshot;
import com.tailtopia.passport.repository.PassportSnapshotRepository;
import com.tailtopia.passport.service.PassportVersionQuery;
import com.tailtopia.passport.service.PlaceSetHash;
import com.tailtopia.place.domain.PlaceAvailability;
import com.tailtopia.place.domain.PlaceStamp;
import com.tailtopia.place.domain.PlaceStampRef;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.service.PlaceIdentityQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * V1.3.2 Story 3.4 · AC5（L0）：版本 = 场所集合。次数变化不算新版本、新章 → false、合并后仍判当前、下架不影响。
 */
class PassportVersionQueryTest {

    private static final long PET = 70L;

    private final PassportSnapshotRepository snapshots = mock(PassportSnapshotRepository.class);
    private final PlaceIdentityQuery places = mock(PlaceIdentityQuery.class);
    private final Map<Long, Long> merged = new HashMap<>();
    private final PassportVersionQuery q = new PassportVersionQuery(snapshots, new PlaceSetHash(places));

    @BeforeEach
    void setUp() {
        when(places.resolveFinal(anyCollection())).thenAnswer(inv -> {
            Map<Long, Long> out = new HashMap<>();
            for (Object o : (java.util.Collection<?>) inv.getArgument(0)) {
                long id = (Long) o;
                out.put(id, merged.getOrDefault(id, id));
            }
            return out;
        });
    }

    static PlaceStampRef ref(long placeId, long visits, PlaceAvailability availability) {
        return new PlaceStampRef(placeId, new PlaceStamp("t" + placeId, "P" + placeId, PlaceType.CAFE, availability,
                LocalDate.of(2026, 9, 1), visits, null, null), LocalDate.of(2026, 9, 1));
    }

    static PassportSnapshot paid(long... placeIds) {
        List<FrozenStamp> fs = java.util.Arrays.stream(placeIds)
                .mapToObj(id -> new FrozenStamp(id, "t" + id, "P" + id, "CAFE", LocalDate.of(2026, 9, 1), 1))
                .toList();
        PassportSnapshot s = PassportSnapshot.freeze("s".repeat(32), PET, fs, "x".repeat(64), Instant.EPOCH);
        ReflectionTestUtils.setField(s, "paidAt", Instant.EPOCH);
        return s;
    }

    private void paidVersions(PassportSnapshot... s) {
        when(snapshots.findByPetProfileIdAndPaidAtIsNotNullOrderByPaidAtDescIdDesc(PET)).thenReturn(List.of(s));
    }

    @Test
    void noStampsIsLockedWithPaidCount() {
        paidVersions(paid(1));
        assertThat(q.stateOf(PET, List.of())).isEqualTo(new PassportVersionQuery.VersionState(false, 1));
    }

    @Test
    void visitCountChangeIsSameVersion() {
        paidVersions(paid(1, 2));
        assertThat(q.stateOf(PET, List.of(ref(1, 5, PlaceAvailability.ACTIVE), ref(2, 9, PlaceAvailability.ACTIVE)))
                .currentVersionUnlocked()).isTrue();
    }

    @Test
    void newStampRelocksTheWholePassport() {
        paidVersions(paid(1, 2));
        var state = q.stateOf(PET, List.of(ref(1, 1, PlaceAvailability.ACTIVE), ref(2, 1, PlaceAvailability.ACTIVE),
                ref(3, 1, PlaceAvailability.ACTIVE)));
        assertThat(state.currentVersionUnlocked()).isFalse();
        assertThat(state.purchasedVersionCount()).isEqualTo(1);
    }

    @Test
    void mergeKeepsBoughtVersionCurrent() {
        paidVersions(paid(1, 2)); // 买 {A, B}
        merged.put(2L, 1L);       // B 并入 A → 当前章集合 {A}
        assertThat(q.stateOf(PET, List.of(ref(1, 3, PlaceAvailability.ACTIVE))).currentVersionUnlocked()).isTrue();
    }

    @Test
    void delistedPlaceStillCounts() {
        paidVersions(paid(1, 2));
        assertThat(q.stateOf(PET, List.of(ref(1, 1, PlaceAvailability.ACTIVE), ref(2, 1, PlaceAvailability.UNAVAILABLE)))
                .currentVersionUnlocked()).isTrue();
    }

    @Test
    void anyOlderPaidVersionMatching() {
        paidVersions(paid(1, 2, 3), paid(1, 2));
        assertThat(q.stateOf(PET, List.of(ref(1, 1, PlaceAvailability.ACTIVE), ref(2, 1, PlaceAvailability.ACTIVE)))
                .currentVersionUnlocked()).isTrue();
    }

    @Test
    void frozenStampJsonRoundTrips() {
        FrozenStamp f = new FrozenStamp(7L, "tok", "Kopi", "CAFE", LocalDate.of(2026, 9, 2), 3);
        Map<String, Object> m = new HashMap<>(f.toJson());
        m.put("placeId", 7); // JSON 反序列化常见为 Integer
        m.put("visitCount", 3);
        assertThat(FrozenStamp.fromJson(m)).isEqualTo(f);
        assertThat(f.toJson()).containsEntry("firstVisitDate", "2026-09-02");
    }
}
