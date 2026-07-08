package com.tailtopia.moderation.violation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.moderation.read.ViolationType;
import com.tailtopia.moderation.violation.repository.ViolationCountRepository;
import org.junit.jupiter.api.Test;

/**
 * L0（story 9 §5.2 / AC-9）：累加/清理收口 —— record 只做原子 UPSERT（传枚举 name）、无任何下游副作用；
 * deleteByAccount 只删聚合行。无 DB（mock repo）。
 */
class ViolationCountServiceTest {

    private final ViolationCountRepository counts = mock(ViolationCountRepository.class);
    private final ViolationCountService service = new ViolationCountService(counts);

    @Test
    void recordDoesAtomicUpsertWithEnumNameAndNoSideEffects() {
        service.record(42L, ViolationType.POST);

        verify(counts).upsertIncrement(42L, "POST");
        // AC-9：仅记录不处置 —— record 除 UPSERT 外对仓储无其它交互（无读回、无删）。
        verifyNoMoreInteractions(counts);
    }

    @Test
    void recordPassesCorrectTypeName() {
        service.record(7L, ViolationType.AVATAR);
        verify(counts).upsertIncrement(7L, "AVATAR");
    }

    @Test
    void deleteByAccountDelegatesToRepo() {
        when(counts.deleteByAccountId(9L)).thenReturn(3);

        int removed = service.deleteByAccount(9L);

        assertThat(removed).isEqualTo(3);
        verify(counts).deleteByAccountId(9L);
        verifyNoMoreInteractions(counts);
    }
}
