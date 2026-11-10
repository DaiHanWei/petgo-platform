package com.tailtopia.social.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.profile.domain.PetType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约：追更宠物列表 / 追更结果的 JSON 键名（V1.4.2 social-topic · Story 1.1 · AC3.10 / AC8）——
 * {@code iFollow} / {@code iFollowOwner} 不得漂成 {@code ifollow}；后续页没有 {@code petFollowingCount} 键。
 */
class FollowedPetPageContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    @Test
    void rowHasExactlyTheContractKeys() {
        FollowedPetItem row = new FollowedPetItem(42L, "Miu", PetType.CAT, "https://cdn/a.jpg", "Aurel",
                "t".repeat(32), true, false);
        assertThat(wire(row).keySet()).isEqualTo(Set.of("petId", "name", "petType", "avatarUrl", "ownerNickname",
                "ownerToken", "iFollow", "iFollowOwner"));
        assertThat(wire(row)).containsEntry("iFollow", true).containsEntry("iFollowOwner", false)
                .containsEntry("petType", "CAT");
    }

    @Test
    void firstPageCarriesPetFollowingCount() {
        FollowedPetPage page = new FollowedPetPage(List.of(), null, false, 0L);
        assertThat(wire(page).keySet()).isEqualTo(Set.of("items", "hasMore", "petFollowingCount"));
        assertThat(((Number) wire(page).get("petFollowingCount")).longValue()).isZero();
    }

    @Test
    void laterPageOmitsPetFollowingCount() {
        FollowedPetPage page = new FollowedPetPage(List.of(), "cur", true, null);
        assertThat(wire(page).keySet()).isEqualTo(Set.of("items", "nextCursor", "hasMore"));
    }

    @Test
    void resultKeys() {
        assertThat(wire(new PetFollowResultResponse(true, 3L)).keySet())
                .isEqualTo(Set.of("iFollow", "followerCount"));
    }
}
