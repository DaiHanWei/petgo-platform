package com.tailtopia.admin.seed.web;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.seed.dto.PetOption;
import com.tailtopia.admin.seed.service.AdminSeedImageService;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

/** L0（V1.4.0 multi-pet Story 1.2 · X30）：种子帖宠物下拉列出账号<b>全部</b>宠物（按创建时间）。 */
class AdminSeedAssistControllerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void petOptionsListAllPetsOfTheAuthor() {
        PetProfileRepository pets = mock(PetProfileRepository.class);
        when(pets.findAllByOwnerIdOrderByCreatedAtAscIdAsc(5L)).thenReturn(List.of(
                pet(5L, 1L, PetType.CAT, T0), pet(5L, 2L, PetType.DOG, T0.plusSeconds(60))));
        AdminSeedAssistController controller = new AdminSeedAssistController(mock(AdminSeedImageService.class), pets);
        ExtendedModelMap model = new ExtendedModelMap();

        controller.petOptions(5L, model);

        List<PetOption> options = (List<PetOption>) model.get("petOptions");
        assertThat(options).extracting(PetOption::id).containsExactly(1L, 2L);
        assertThat(options).extracting(PetOption::petType).containsExactly("CAT", "DOG");

        controller.petOptions(null, model);
        assertThat((List<PetOption>) model.get("petOptions")).isEmpty();
    }
}
