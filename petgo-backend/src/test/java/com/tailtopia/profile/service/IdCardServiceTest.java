package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.IdCardDataResponse;
import com.tailtopia.profile.repository.IdCardHdPurchaseRepository;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * L0：身份证数据服务逻辑面（Story 6.1，FR-49A）——惰性分配 / 幂等 / 老用户「尚未生成」标志 / 无档案 404。
 * 号池并发 / 回收 / 从 1 自增等 DB 行为在 {@code SerialAllocationIntegrationTest}（L1）验。
 */
class IdCardServiceTest {

    private final PetProfileRepository profiles = mock(PetProfileRepository.class);
    private final SerialAllocationService serialAllocation = mock(SerialAllocationService.class);
    private final IdCardHdPurchaseRepository hdPurchases = mock(IdCardHdPurchaseRepository.class);
    private final IdCardRepository idCards = mock(IdCardRepository.class);
    private final IdCardService service =
            new IdCardService(profiles, serialAllocation, hdPurchases, idCards);

    private static PetProfile pet() {
        return PetProfile.create(7L, PetType.CAT, "Momo", null, "英短", LocalDate.of(2022, 1, 1), "乖", "TOK");
    }

    @Test
    void getMyIdCardOldUserWithoutSerialReturnsNotGenerated() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(pet()));

        IdCardDataResponse res = service.getMyIdCard(7L);

        assertThat(res.generated()).isFalse();
        assertThat(res.serialId()).isNull();
        assertThat(res.name()).isEqualTo("Momo");
        assertThat(res.petType()).isEqualTo("CAT");
    }

    @Test
    void getMyIdCardWithSerialReturnsGeneratedWithSerial() {
        PetProfile p = pet();
        p.assignSerial(42L);
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(p));

        IdCardDataResponse res = service.getMyIdCard(7L);

        assertThat(res.generated()).isTrue();
        assertThat(res.serialId()).isEqualTo(42L);
    }

    @Test
    void getMyIdCardNoProfile404() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyIdCard(7L)).isInstanceOf(AppException.class);
    }

    @Test
    void getMyIdCardNeverAllocatesSerial() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(pet()));

        service.getMyIdCard(7L);

        // 惰性分配红线：只读端点绝不分配号（保老用户「尚未生成」引导态持续可见）。
        verify(serialAllocation, never()).allocate();
        verify(profiles, never()).save(any());
    }

    @Test
    void generateAssignsSerialWhenAbsentAndPersists() {
        PetProfile p = pet();
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(p));
        when(serialAllocation.allocate()).thenReturn(1L);

        IdCardDataResponse res = service.generateSerial(7L);

        verify(serialAllocation).allocate();
        verify(profiles).save(p);
        assertThat(p.getSerialId()).isEqualTo(1L);
        assertThat(res.generated()).isTrue();
        assertThat(res.serialId()).isEqualTo(1L);
    }

    @Test
    void generateIsIdempotentWhenSerialAlreadyAssigned() {
        PetProfile p = pet();
        p.assignSerial(99L);
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.of(p));

        IdCardDataResponse res = service.generateSerial(7L);

        // 幂等：已有号不重复分配、不换号、不再落库（避免 pet_serial_seq 空耗）。
        verify(serialAllocation, never()).allocate();
        verify(profiles, never()).save(any());
        assertThat(res.serialId()).isEqualTo(99L);
    }

    @Test
    void generateNoProfile404() {
        when(profiles.findByOwnerId(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateSerial(7L)).isInstanceOf(AppException.class);
        verify(serialAllocation, never()).allocate();
    }
}
