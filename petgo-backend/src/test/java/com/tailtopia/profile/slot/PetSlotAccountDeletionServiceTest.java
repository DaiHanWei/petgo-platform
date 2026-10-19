package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.slot.domain.PetSlotRentalStatus;
import com.tailtopia.profile.slot.repository.PetSlotRentalRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** OCR #2（L0）：注销时先拿账号锁，再把该用户 ACTIVE 名额置 EXPIRED（保留行）。 */
class PetSlotAccountDeletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");

    @Test
    void locksThenExpiresAllActiveRentals() {
        PetSlotRentalRepository repo = mock(PetSlotRentalRepository.class);
        PetSlotLock lock = mock(PetSlotLock.class);
        when(repo.expireAllOfUser(7L, PetSlotRentalStatus.ACTIVE, PetSlotRentalStatus.EXPIRED, NOW)).thenReturn(2);

        int n = new PetSlotAccountDeletionService(repo, lock, Clock.fixed(NOW, ZoneOffset.UTC))
                .expireAllForAccount(7L);

        assertThat(n).isEqualTo(2);
        InOrder o = inOrder(lock, repo);
        o.verify(lock).lockUser(7L);
        o.verify(repo).expireAllOfUser(7L, PetSlotRentalStatus.ACTIVE, PetSlotRentalStatus.EXPIRED, NOW);
    }
}
