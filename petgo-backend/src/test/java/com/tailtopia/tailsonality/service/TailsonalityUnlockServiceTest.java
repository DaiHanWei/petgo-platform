package com.tailtopia.tailsonality.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.OwnedPetRef;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.purchase.domain.KeepsakeRef;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.dto.KeepsakePurchaseResponse;
import com.tailtopia.purchase.service.KeepsakePurchaseService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.tailsonality.domain.TailsonalityCode;
import com.tailtopia.tailsonality.domain.TailsonalityQuestionSet;
import com.tailtopia.tailsonality.domain.TailsonalityResult;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.3.2 Story 3.2 · AC1（L0）：加锁读 → KeepsakeRef 构造 → start；404 口径。 */
class TailsonalityUnlockServiceTest {

    private final TailsonalityResultRepository results = mock(TailsonalityResultRepository.class);
    private final PetProfileQueryService pets = mock(PetProfileQueryService.class);
    private final KeepsakePurchaseService purchases = mock(KeepsakePurchaseService.class);
    private final TailsonalityUnlockService service = new TailsonalityUnlockService(results, pets, purchases);

    private TailsonalityResult row(Instant unlockedAt) {
        TailsonalityResult r = TailsonalityResult.create("tok", 3L, 7L, TailsonalityQuestionSet.CAT, Map.of(),
                new TailsonalityCode("ENTJ", "H"), 1, Instant.EPOCH);
        ReflectionTestUtils.setField(r, "id", 42L);
        ReflectionTestUtils.setField(r, "unlockedAt", unlockedAt);
        return r;
    }

    private void ownsPet() {
        when(pets.findOwnedPet(7L)).thenReturn(Optional.of(new OwnedPetRef(3L, PetType.CAT)));
    }

    @Test
    void buildsRefFromLockedRowAndDelegates() {
        ownsPet();
        when(results.findForUpdateByPublicTokenAndPetProfileId("tok", 3L)).thenReturn(Optional.of(row(null)));
        KeepsakePurchaseResponse resp = KeepsakePurchaseResponse.unlocked("p1");
        when(purchases.start(eq(7L), any(), eq(PayChannel.PAWCOIN))).thenReturn(resp);

        assertThat(service.unlock(7L, "tok", PayChannel.PAWCOIN)).isSameAs(resp);
        ArgumentCaptor<KeepsakeRef> ref = ArgumentCaptor.forClass(KeepsakeRef.class);
        verify(purchases).start(eq(7L), ref.capture(), eq(PayChannel.PAWCOIN));
        assertThat(ref.getValue()).isEqualTo(new KeepsakeRef(KeepsakeSku.TAILSONALITY, 42L, "tok", 3L, false));
        verify(results, never()).findByPublicTokenAndPetProfileId(any(), anyLong());
    }

    @Test
    void unlockedRowIsPassedAsAlreadyUnlocked() {
        ownsPet();
        when(results.findForUpdateByPublicTokenAndPetProfileId("tok", 3L))
                .thenReturn(Optional.of(row(Instant.EPOCH)));
        service.unlock(7L, "tok", PayChannel.QRIS);
        ArgumentCaptor<KeepsakeRef> ref = ArgumentCaptor.forClass(KeepsakeRef.class);
        verify(purchases).start(eq(7L), ref.capture(), eq(PayChannel.QRIS));
        assertThat(ref.getValue().alreadyUnlocked()).isTrue();
    }

    @Test
    void unknownTokenAndNoPetAreBoth404() {
        ownsPet();
        when(results.findForUpdateByPublicTokenAndPetProfileId("x", 3L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.unlock(7L, "x", PayChannel.PAWCOIN)).isInstanceOf(AppException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        when(pets.findOwnedPet(8L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.unlock(8L, "tok", PayChannel.PAWCOIN)).isInstanceOf(AppException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        verify(purchases, never()).start(anyLong(), any(), any());
    }
}
