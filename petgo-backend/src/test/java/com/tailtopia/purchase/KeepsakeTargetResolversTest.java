package com.tailtopia.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.passport.domain.BoardingPassUnlock;
import com.tailtopia.passport.domain.FrozenStamp;
import com.tailtopia.passport.domain.PassportSnapshot;
import com.tailtopia.passport.repository.BoardingPassUnlockRepository;
import com.tailtopia.passport.repository.PassportSnapshotRepository;
import com.tailtopia.passport.service.BoardingPassTargetResolver;
import com.tailtopia.passport.service.PassportSnapshotTargetResolver;
import com.tailtopia.place.service.PlaceIdentityQuery;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.domain.KeepsakeTargetResolver;
import com.tailtopia.tailsonality.domain.TailsonalityCode;
import com.tailtopia.tailsonality.domain.TailsonalityQuestionSet;
import com.tailtopia.tailsonality.domain.TailsonalityResult;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import com.tailtopia.tailsonality.service.TailsonalityTargetResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.3.2 Story 3.6 · AC3.6（L0）：三个 SKU 的「查看」目标解析各一个实现，sku / kind 覆盖齐全。 */
class KeepsakeTargetResolversTest {

    @Test
    void tailsonalityResultToken() {
        TailsonalityResultRepository repo = mock(TailsonalityResultRepository.class);
        TailsonalityResult r = TailsonalityResult.create("res", 1L, 2L, TailsonalityQuestionSet.CAT, Map.of(),
                new TailsonalityCode("ENTJ", "H"), 1, Instant.EPOCH);
        when(repo.findById(5L)).thenReturn(Optional.of(r));
        var resolver = new TailsonalityTargetResolver(repo);
        assertThat(resolver.targetToken(5L)).contains("res");
        assertThat(resolver.targetToken(6L)).isEmpty();
    }

    @Test
    void passportSnapshotOnlyWhenPaid() {
        PassportSnapshotRepository repo = mock(PassportSnapshotRepository.class);
        PassportSnapshot s = PassportSnapshot.freeze("snap", 1L,
                List.of(new FrozenStamp(1L, "t", "n", "CAFE", LocalDate.EPOCH, 1)), "h".repeat(64), Instant.EPOCH);
        when(repo.findById(5L)).thenReturn(Optional.of(s));
        var resolver = new PassportSnapshotTargetResolver(repo);
        assertThat(resolver.targetToken(5L)).as("未付").isEmpty();
        ReflectionTestUtils.setField(s, "paidAt", Instant.EPOCH);
        assertThat(resolver.targetToken(5L)).contains("snap");
    }

    @Test
    void boardingPassFollowsMergeToFinalPlaceToken() {
        BoardingPassUnlockRepository repo = mock(BoardingPassUnlockRepository.class);
        PlaceIdentityQuery places = mock(PlaceIdentityQuery.class);
        BoardingPassUnlock row = org.springframework.beans.BeanUtils.instantiateClass(BoardingPassUnlock.class);
        ReflectionTestUtils.setField(row, "placeId", 9L);
        when(repo.findById(5L)).thenReturn(Optional.of(row));
        when(places.resolveFinal(anyCollection())).thenReturn(Map.of(9L, 1L));
        when(places.tokenOf(1L)).thenReturn(Optional.of("keep-token"));
        var resolver = new BoardingPassTargetResolver(repo, places);
        assertThat(resolver.targetToken(5L)).contains("keep-token");
        assertThat(resolver.targetToken(6L)).isEmpty();
    }

    @Test
    void everySkuHasOneResolverWithDistinctKinds() {
        List<KeepsakeTargetResolver> all = List.of(new TailsonalityTargetResolver(mock(TailsonalityResultRepository.class)),
                new PassportSnapshotTargetResolver(mock(PassportSnapshotRepository.class)),
                new BoardingPassTargetResolver(mock(BoardingPassUnlockRepository.class), mock(PlaceIdentityQuery.class)));
        assertThat(all.stream().map(KeepsakeTargetResolver::sku)).containsExactlyInAnyOrder(KeepsakeSku.values());
        assertThat(all.stream().map(KeepsakeTargetResolver::targetKind))
                .containsExactly("TAILSONALITY_RESULT", "PASSPORT_SNAPSHOT", "BOARDING_PASS");
    }
}
