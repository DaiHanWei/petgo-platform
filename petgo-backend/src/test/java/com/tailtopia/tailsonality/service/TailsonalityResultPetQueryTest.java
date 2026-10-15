package com.tailtopia.tailsonality.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.tailsonality.domain.TailsonalityCode;
import com.tailtopia.tailsonality.domain.TailsonalityQuestionSet;
import com.tailtopia.tailsonality.domain.TailsonalityResult;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.3.4 Story 4.2 · AC4.2（L0）：结果 → 所属宠物；结果已删 → empty。 */
class TailsonalityResultPetQueryTest {

    private final TailsonalityResultRepository results = mock(TailsonalityResultRepository.class);
    private final TailsonalityResultPetQuery query = new TailsonalityResultPetQuery(results);

    @Test
    void returnsOwningPetOfTheResult() {
        TailsonalityResult r = TailsonalityResult.create("tok", 9L, 7L, TailsonalityQuestionSet.CAT, Map.of(),
                new TailsonalityCode("ENTJ", "H"), 1, Instant.EPOCH);
        ReflectionTestUtils.setField(r, "id", 42L);
        when(results.findById(42L)).thenReturn(Optional.of(r));

        assertThat(query.petProfileIdOf(42L)).contains(9L);
    }

    @Test
    void missingResultIsEmpty() {
        when(results.findById(42L)).thenReturn(Optional.empty());

        assertThat(query.petProfileIdOf(42L)).isEmpty();
    }
}
