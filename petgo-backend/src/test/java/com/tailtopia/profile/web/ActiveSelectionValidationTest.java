package com.tailtopia.profile.web;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.ActiveSelectionRequest;
import com.tailtopia.profile.dto.PetListItemResponse;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.PetListService;
import com.tailtopia.profile.service.PetResolver;
import com.tailtopia.profile.slot.PetSlotLock;
import com.tailtopia.profile.slot.PetSlotQuota;
import com.tailtopia.profile.slot.PetSlotService;
import com.tailtopia.profile.slot.PetSlotStateService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * L0：V1.4.0 multi-pet Story 2.1 · AC4 —— {@code PUT /pet-profiles/me/active-selection} 校验：
 * 数量不等 → 422；宠物数 ≤ 名额 → 409 {@code pet-selection-not-needed}；成功后返回最新列表。
 * 控制器 + 真实 {@link PetSlotStateService}，仓储 / 名额 / 锁用 mock。
 */
class ActiveSelectionValidationTest {

    private static final long OWNER = 7L;

    private final PetProfileRepository profiles = mock(PetProfileRepository.class);
    private final PetSlotService slots = mock(PetSlotService.class);
    private final PetListService list = mock(PetListService.class);
    private final PetActiveSelectionController controller = new PetActiveSelectionController(
            new PetSlotStateService(profiles, slots, mock(PetSlotLock.class), new PetResolver(profiles)), list);

    private final Jwt jwt = new Jwt("t", Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
            Map.of("alg", "none"), Map.of("sub", String.valueOf(OWNER)));

    private PetProfile a;
    private PetProfile b;
    private PetProfile c;

    @BeforeEach
    void setUp() {
        a = pet(OWNER, 10L, Instant.EPOCH);
        b = pet(OWNER, 11L, Instant.EPOCH.plusSeconds(1));
        c = pet(OWNER, 12L, Instant.EPOCH.plusSeconds(2));
        for (PetProfile p : List.of(a, b, c)) {
            when(profiles.findByIdAndOwnerId(p.getId(), OWNER)).thenReturn(Optional.of(p));
        }
    }

    private void quota(int total) {
        when(slots.quotaOf(OWNER)).thenReturn(new PetSlotQuota(total, 1, total - 1, 0, 0, 5 - total, true, null, false));
    }

    @Test
    void countNotEqualQuotaIs422() {
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of(a, b, c));
        quota(2);
        for (List<Long> ids : List.of(List.of(10L), List.of(10L, 11L, 12L), List.<Long>of())) {
            assertThatThrownBy(() -> controller.changeSelection(jwt, new ActiveSelectionRequest(ids)))
                    .isInstanceOfSatisfying(AppException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.getType()).isEqualTo(ErrorTypes.VALIDATION);
                    });
        }
        assertThatThrownBy(() -> controller.changeSelection(jwt, null)).isInstanceOf(AppException.class);
        verify(profiles, never()).saveAll(anyList());
        verify(list, never()).listMine(anyLong());
    }

    @Test
    void nullPetIdIs422() {
        java.util.List<Long> withNull = new java.util.ArrayList<>();
        withNull.add(10L);
        withNull.add(null);
        assertThatThrownBy(() -> controller.changeSelection(jwt, new ActiveSelectionRequest(withNull)))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void petsWithinQuotaIs409SelectionNotNeeded() {
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of(a, b));
        quota(2);
        assertThatThrownBy(() -> controller.changeSelection(jwt, new ActiveSelectionRequest(List.of(10L, 11L))))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getType()).isEqualTo(ErrorTypes.PET_SELECTION_NOT_NEEDED);
                    assertThat(e.getType().toString()).isEqualTo("https://petgo/errors/pet-selection-not-needed");
                });
    }

    @Test
    void foreignPetIs404BeforeCountChecks() {
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of(a, b));
        quota(2);
        when(profiles.findByIdAndOwnerId(99L, OWNER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.changeSelection(jwt, new ActiveSelectionRequest(List.of(99L))))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void successReturnsLatestList() {
        when(profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(OWNER)).thenReturn(List.of(a, b, c));
        quota(2);
        List<PetListItemResponse> latest = List.of();
        when(list.listMine(OWNER)).thenReturn(latest);

        assertThat(controller.changeSelection(jwt, new ActiveSelectionRequest(List.of(10L, 12L)))).isSameAs(latest);
        assertThat(b.isReadOnly()).isTrue();
        assertThat(c.isReadOnly()).isFalse();
    }
}
