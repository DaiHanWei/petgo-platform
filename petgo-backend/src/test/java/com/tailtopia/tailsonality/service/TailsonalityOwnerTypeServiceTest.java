package com.tailtopia.tailsonality.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.tailsonality.domain.TailsonalityOwnerType;
import com.tailtopia.tailsonality.repository.TailsonalityOwnerTypeRepository;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.3.2 Story 2.5 · AC1 / AC2（L0，mock 仓库）。 */
class TailsonalityOwnerTypeServiceTest {

    private final TailsonalityOwnerTypeRepository repo = mock(TailsonalityOwnerTypeRepository.class);
    private final TailsonalityOwnerTypeService service = new TailsonalityOwnerTypeService(repo);

    @Test
    void getReturnsNullWhenUnset() {
        when(repo.findByUserId(7L)).thenReturn(Optional.empty());
        assertThat(service.get(7L).typeCode()).isNull();
    }

    @Test
    void getReturnsStoredType() {
        TailsonalityOwnerType t = org.springframework.beans.BeanUtils.instantiateClass(TailsonalityOwnerType.class);
        ReflectionTestUtils.setField(t, "typeCode", "INFP");
        when(repo.findByUserId(7L)).thenReturn(Optional.of(t));
        assertThat(service.get(7L).typeCode()).isEqualTo("INFP");
    }

    @Test
    void setUpsertsAndEchoes() {
        assertThat(service.set(7L, "ENTJ").typeCode()).isEqualTo("ENTJ");
        verify(repo).upsert(7L, "ENTJ");
    }

    @Test
    void accountDeletionRemovesOwnerType() {
        TailsonalityDeletionService deletion =
                new TailsonalityDeletionService(mock(TailsonalityResultRepository.class), repo,
                        mock(com.tailtopia.tailsonality.repository.TailsonalityBadgeRepository.class));
        deletion.deleteOwnerTypeByUserId(7L);
        verify(repo).deleteByUserId(7L);
    }
}
