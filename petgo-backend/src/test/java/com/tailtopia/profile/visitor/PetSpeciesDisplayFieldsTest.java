package com.tailtopia.profile.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSpecies;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.IdCardDataResponse;
import com.tailtopia.profile.dto.PetView;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * L0：V1.5.0 pet-species Story 1.3 · AC1 —— 兔子宠物各显示返回体 {@code species = RABBIT}、{@code petType = OTHER}；
 * 旧形态便捷构造不带 species（JSON 末尾追加、null 省略）。
 */
class PetSpeciesDisplayFieldsTest {

    private static PetProfile rabbit() {
        PetProfile p = PetProfile.create(7L, PetSpecies.RABBIT, "Momo", null, "Lop", LocalDate.of(2022, 1, 1), null,
                "TOK");
        org.springframework.test.util.ReflectionTestUtils.setField(p, "id", 10L);
        return p;
    }

    @Test
    void visitorAndPublicPet() {
        VisitorProfileResponse v = VisitorProfileResponse.of(rabbit(), "Aurel");
        assertThat(v.species()).isEqualTo("RABBIT");
        assertThat(v.petType()).isEqualTo(PetType.OTHER);
        PublicProfilePetResponse pub = PublicProfilePetResponse.of(rabbit(), 3, null);
        assertThat(pub.species()).isEqualTo("RABBIT");
        assertThat(pub.petType()).isEqualTo(PetType.OTHER);
    }

    @Test
    void idCardPrefill() {
        IdCardDataResponse d = IdCardDataResponse.from(rabbit(), false);
        assertThat(d.species()).isEqualTo("RABBIT");
        assertThat(d.petType()).isEqualTo("OTHER");
    }

    @Test
    void petViewLegacyConstructorDerivesSpeciesFromGroup() {
        PetView legacy = new PetView(1L, 2L, "n", null, PetType.DOG, null, null);
        assertThat(legacy.species()).isEqualTo(PetSpecies.DOG);
    }

    @Test
    void jsonOmitsNullSpeciesAndKeepsPetType() throws Exception {
        ObjectMapper om = new ObjectMapper().findAndRegisterModules()
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        String legacy = om.writeValueAsString(new com.tailtopia.social.dto.FollowedPetItem(
                1L, "Miu", PetType.CAT, null, null, null, false, false));
        assertThat(legacy).contains("\"petType\":\"CAT\"").doesNotContain("species");
        String withSpecies = om.writeValueAsString(new com.tailtopia.social.dto.FollowedPetItem(
                1L, "Miu", PetType.OTHER, null, null, null, false, false, "RABBIT"));
        assertThat(withSpecies).contains("\"petType\":\"OTHER\"").contains("\"species\":\"RABBIT\"");
    }
}
