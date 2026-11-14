package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.IdCard;
import com.tailtopia.profile.dto.CreateIdCardRequest;
import com.tailtopia.profile.dto.IdCardCreateResponse;
import com.tailtopia.profile.dto.IdCardResponse;
import com.tailtopia.profile.repository.IdCardHdPurchaseRepository;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * L0：V1.5.0 pet-species Story 1.3 · AC3 —— 建卡归一：快照 pet_type 存大类、pet_species 存细分类型；
 * 卡号物种码 / 编号沿用按大类；不一致 422；V2「物种必填」= species 或 petType 至少一个。
 */
class IdCardServiceSpeciesTest {

    private final PetProfileRepository profiles = mock(PetProfileRepository.class);
    private final SerialAllocationService serialAllocation = mock(SerialAllocationService.class);
    private final CardNumberService cardNumbers = mock(CardNumberService.class);
    private final IdCardHdPurchaseRepository hdPurchases = mock(IdCardHdPurchaseRepository.class);
    private final IdCardRepository idCards = mock(IdCardRepository.class);
    private final IdCardService service = new IdCardService(profiles, serialAllocation, cardNumbers, hdPurchases,
            idCards, new PetWriteGuard(profiles));

    @BeforeEach
    void setUp() {
        when(serialAllocation.allocate()).thenReturn(11L);
        when(cardNumbers.allocateCardNo(any(), any(), any())).thenReturn("TT600324000001");
        when(cardNumbers.allocatePassportNo(any())).thenReturn("TT00P2600001");
        when(idCards.findNumberSourceCard(anyLong(), any(), any(), any())).thenReturn(Optional.empty());
        when(idCards.save(any())).thenAnswer(inv -> {
            IdCard c = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(c, "id", 5L);
            return c;
        });
    }

    private static CreateIdCardRequest req(String petType, String species) {
        return new CreateIdCardRequest("Momo", petType, "Lop", LocalDate.of(2024, 3, 10), "FEMALE", null, null,
                null, null, null, null, "PASSPORT", null, null, null, species);
    }

    private IdCard saved() {
        ArgumentCaptor<IdCard> c = ArgumentCaptor.forClass(IdCard.class);
        verify(idCards).save(c.capture());
        return c.getValue();
    }

    @Test
    void rabbitSnapshotsGroupAndSpecies() {
        IdCardCreateResponse resp = service.createCardV2(7L, req(null, "RABBIT"));
        IdCard c = saved();
        assertThat(c.getPetType()).isEqualTo("OTHER");
        assertThat(c.getPetSpecies()).isEqualTo("RABBIT");
        assertThat(resp.card().species()).isEqualTo("RABBIT");
        assertThat(resp.card().petType()).isEqualTo("OTHER");
        verify(cardNumbers).allocateCardNo(any(), eq("FEMALE"), eq("OTHER"));
        verify(cardNumbers).allocatePassportNo("OTHER");
        verify(idCards).findNumberSourceCard(eq(7L), any(), eq("FEMALE"), eq("OTHER"));
    }

    @Test
    void legacyPetTypeOnlySnapshotsSame() {
        service.createCardV2(7L, req("CAT", null));
        IdCard c = saved();
        assertThat(c.getPetType()).isEqualTo("CAT");
        assertThat(c.getPetSpecies()).isEqualTo("CAT");
        verify(cardNumbers).allocatePassportNo("CAT");
    }

    @Test
    void mismatchRejected() {
        assertThatThrownBy(() -> service.createCardV2(7L, req("DOG", "CAT")))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("宠物类型与细分类型不一致");
    }

    @Test
    void strictRequiresOneOfSpeciesOrPetType() {
        assertThatThrownBy(() -> service.createCardV2(7L, req(null, null)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("物种必填");
    }

    @Test
    void legacyEndpointWithoutTypeKeepsNullSnapshot() {
        service.createCard(7L, req(null, null));
        IdCard c = saved();
        assertThat(c.getPetType()).isNull();
        assertThat(c.getPetSpecies()).isNull();
    }

    @Test
    void oldCardResponseHasNoSpecies() {
        IdCard old = IdCard.snapshot(7L, 1L, "Momo", "CAT", null, null, null, null, "FEMALE", null, null,
                null, null, null, null, "KTP", null, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(old, "id", 3L);
        assertThat(IdCardResponse.from(old).species()).isNull();
        assertThat(IdCardResponse.from(old).petType()).isEqualTo("CAT");
    }

    @Test
    void normalizeSpeciesTable() {
        assertThat(IdCardService.normalizeSpecies(null, "CIVET"))
                .isEqualTo(new IdCardService.CardSpecies("OTHER", "CIVET"));
        assertThat(IdCardService.normalizeSpecies("OTHER", "CIVET"))
                .isEqualTo(new IdCardService.CardSpecies("OTHER", "CIVET"));
        assertThat(IdCardService.normalizeSpecies("DOG", null)).isEqualTo(new IdCardService.CardSpecies("DOG", "DOG"));
        assertThat(IdCardService.normalizeSpecies(null, null)).isEqualTo(new IdCardService.CardSpecies(null, null));
    }
}
