package com.tailtopia.profile.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1：宠物推荐排除已追更 + 近 14 天点赞排序（V1.4.2 social-topic Story 1.3 · AC6 / AC7 · D-17）。
 *
 * <p>库是多个集成测试共用的，别的测试造的宠物也可能入池 —— 断言只看本测试造的那几只
 * （存在 / 不存在 / 相对顺序），不断言整页内容。
 */
class PetRecommendationIntegrationTest extends ApiIntegrationTest {

    private static final String URL = "/api/v1/me/pet-recommendations";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ContentPostRepository posts;

    private boolean dropped;
    private final List<Long> extraPets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
    }

    @AfterEach
    void restore() {
        MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
    }

    /** 一只能入池的宠物：有头像、3 条公开 Diary（近期）。返回 [petId, 最新那条帖 id]。 */
    private long[] recommendablePet(User owner, String name, Instant postedAt) {
        long petId = MultiPetDbFixture.insertPet(jdbc, owner.getId(), name, "CAT", Instant.now());
        extraPets.add(petId);
        jdbc.update("UPDATE pet_profiles SET avatar_url = ? WHERE id = ?",
                "https://cdn.test/avatar-" + petId + ".jpg", petId);
        long last = 0;
        for (int i = 0; i < 3; i++) {
            ContentPost p = posts.save(ContentPost.publish(owner.getId(), ContentType.GROWTH_MOMENT, petId,
                    name + i, List.of(), null));
            jdbc.update("INSERT INTO content_pets (post_id, pet_profile_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    p.getId(), petId);
            jdbc.update("UPDATE content_posts SET created_at = ? WHERE id = ?",
                    Timestamp.from(postedAt), p.getId());
            last = p.getId();
        }
        return new long[] {petId, last};
    }

    private void like(long postId, Instant at) {
        User liker = newUser();
        jdbc.update("INSERT INTO content_likes (post_id, user_id, created_at) VALUES (?, ?, ?)",
                postId, liker.getId(), Timestamp.from(at));
    }

    /** 翻完集合页，收集所有返回过的 petId（同时验证游标连续：不重复）。 */
    private List<Long> allPages(User viewer) throws Exception {
        List<Long> seen = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 50; guard++) {
            var req = get(URL).header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())).param("limit", "10");
            if (cursor != null) {
                req = req.param("cursor", cursor);
            }
            JsonNode page = json.readTree(mvc.perform(req).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            page.get("items").forEach(n -> seen.add(n.get("petId").asLong()));
            if (!page.get("hasMore").asBoolean()) {
                break;
            }
            cursor = page.get("nextCursor").asText();
        }
        return seen;
    }

    @Test
    void followedPetDisappearsFromRowAndCollectionPage() throws Exception {
        User viewer = newUser();
        User owner = newUser();
        Instant recent = Instant.now().minus(Duration.ofHours(2));
        long followed = recommendablePet(owner, "Mochi", recent)[0];
        long other = recommendablePet(newUser(), "Kopi", recent)[0];

        assertThat(allPages(viewer)).contains(followed, other);

        jdbc.update("INSERT INTO pet_follows (follower_id, pet_profile_id, created_at) VALUES (?, ?, now())",
                viewer.getId(), followed);

        // 首页横滑行（第一页）与集合页（翻到底）都不再出现。
        JsonNode first = json.readTree(mvc.perform(get(URL)
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        first.get("items").forEach(n -> assertThat(n.get("petId").asLong()).isNotEqualTo(followed));

        List<Long> all = allPages(viewer);
        assertThat(all).doesNotContain(followed).contains(other);
        // 排除之后游标仍连续：翻页不重复。
        assertThat(all).doesNotHaveDuplicates();
        Set<Long> distinct = new HashSet<>(all);
        assertThat(distinct).hasSameSizeAs(all);
    }

    @Test
    void recentLikesOutrankStaleCumulativeLikesOnSameDay() {
        // AC7.4：同一天更新；老宠物累计点赞多但近 14 天 0 赞，新宠物近 14 天 3 赞 → 新宠物在前。
        // 新宠物先建（id 更小）—— 若排序还在数累计点赞，pet_id DESC 也帮不了它，用例才有区分度。
        Instant sameMoment = Instant.now().minus(Duration.ofHours(1));
        long[] fresh = recommendablePet(newUser(), "Fresh", sameMoment);
        long[] stale = recommendablePet(newUser(), "Stale", sameMoment);
        Instant old = Instant.now().minus(Duration.ofDays(30));
        for (int i = 0; i < 5; i++) {
            like(stale[1], old);
        }
        for (int i = 0; i < 3; i++) {
            like(fresh[1], Instant.now().minus(Duration.ofDays(1)));
        }

        List<Object[]> rows = posts.findRecommendablePets(Instant.now().minus(Duration.ofDays(14)), 3, 10_000,
                false, null, null, null);
        List<Long> order = new ArrayList<>();
        for (Object[] r : rows) {
            long id = ((Number) r[0]).longValue();
            if (id == fresh[0] || id == stale[0]) {
                order.add(id);
            }
        }
        assertThat(order).containsExactly(fresh[0], stale[0]);
    }
}
