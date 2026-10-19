package com.tailtopia.social.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约：关注 / 粉丝列表行的 JSON 键名（V1.3.4 follow · Story 2.2 · AC1.2）——
 * 与 App {@code FollowListItem.fromJson} 逐字一致；{@code iFollow} 不得漂成 {@code ifollow}。
 */
class FollowListContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    @Test
    void rowHasExactlyTheContractKeys() {
        FollowListItem row = new FollowListItem(7L, "t".repeat(32), "Rina", "https://cdn/a.jpg", false,
                true, false, false, false);
        assertThat(wire(row).keySet()).isEqualTo(Set.of("userId", "userToken", "nickname", "avatarUrl",
                "deleted", "iFollow", "followsMe", "blockedByMe", "self"));
        assertThat(wire(row)).containsEntry("iFollow", true).containsEntry("followsMe", false);
    }

    @Test
    void deletedRowOmitsIdentityKeys() {
        FollowListItem row = new FollowListItem(7L, null, null, null, true, false, false, false, false);
        assertThat(wire(row).keySet()).isEqualTo(Set.of("userId", "deleted", "iFollow", "followsMe",
                "blockedByMe", "self"));
    }

    @Test
    void pageEnvelope() {
        FollowListPage page = new FollowListPage(List.of(), null, false);
        assertThat(wire(page).keySet()).isEqualTo(Set.of("items", "hasMore"));
    }

    /** V1.4.0 ① Story 3.1：带等级的行多 level / tier / frame；无等级（9 参构造）三者省略（见上两例的键集）。 */
    @Test
    void growthFieldsOnRow() {
        com.tailtopia.auth.dto.AuthorView author = new com.tailtopia.auth.dto.AuthorView(7L, "Rina", null, false,
                List.of()).withGrowth(new com.tailtopia.growth.domain.GrowthBadge(15,
                        com.tailtopia.growth.domain.GrowthTier.STAR, com.tailtopia.growth.domain.FrameChoice.ANIMATED));
        FollowListItem row = new FollowListItem(7L, "t".repeat(32), "Rina", null, false, false, false, false, false,
                author);
        assertThat(wire(row)).containsEntry("level", 15).containsEntry("tier", "STAR")
                .containsEntry("frame", "ANIMATED");
        assertThat(wire(new FollowListItem(7L, null, null, null, false, false, false, false, false)))
                .doesNotContainKeys("level", "tier", "frame");
    }
}
