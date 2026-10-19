package com.tailtopia.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Array;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.4.0 multi-pet Story 4.4 · AC4（L1，需 Docker postgres + redis）：发布关联 A、B 的 Diary → {@code POST_PUBLISHED}
 * 流水 {@code pet_ids = {A, B}}；他人点赞 → {@code LIKE_RECEIVED} 流水 {@code pet_ids = {A, B}}（经 {@code content_pets}）。
 */
class GrowthPostPublishedPetIdsIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private boolean dropped;
    private final List<Long> extraPets = new ArrayList<>();
    private long owner;
    private long petA;
    private long petB;

    @BeforeEach
    void setUpPets() {
        dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
        owner = newUser().getId();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        petA = MultiPetDbFixture.insertPet(jdbc, owner, "Aaa", "CAT", t0);
        petB = MultiPetDbFixture.insertPet(jdbc, owner, "Bbb", "DOG", t0.plusSeconds(3600));
        extraPets.add(petA);
        extraPets.add(petB);
    }

    @AfterEach
    void restore() {
        MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
    }

    private static void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
    }

    private int count(long userId, String rule) {
        return jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE user_id = ? AND rule_code = ?",
                Integer.class, userId, rule);
    }

    private List<Long> petIds(long userId, String rule) {
        return jdbc.queryForObject("SELECT pet_ids FROM growth_ledger WHERE user_id = ? AND rule_code = ? "
                + "ORDER BY id DESC LIMIT 1", (rs, i) -> {
                    Array a = rs.getArray(1);
                    return a == null ? null : Arrays.stream((Long[]) a.getArray()).toList();
                }, userId, rule);
    }

    @Test
    void diaryWithTwoPetsWritesBothPetsOnPublishAndLike() throws Exception {
        String resp = mvc.perform(post("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"GROWTH_MOMENT\",\"petIds\":[" + petB + "," + petA
                                + "],\"text\":\"berdua\",\"visibility\":\"PUBLIC\","
                                + "\"imageUrls\":[\"https://cdn.test/berdua.jpg\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long postId = json.readTree(resp).get("id").asLong();

        await(() -> count(owner, "POST_PUBLISHED") == 1);
        assertThat(count(owner, "POST_PUBLISHED")).isEqualTo(1);
        assertThat(petIds(owner, "POST_PUBLISHED")).containsExactly(petA, petB); // 宠物创建顺序

        long liker = newUser().getId();
        mvc.perform(post("/api/v1/content-posts/" + postId + "/like")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(liker)))
                .andExpect(status().isOk());
        await(() -> count(owner, "LIKE_RECEIVED") == 1);
        assertThat(count(owner, "LIKE_RECEIVED")).isEqualTo(1);
        assertThat(petIds(owner, "LIKE_RECEIVED")).containsExactly(petA, petB);
    }
}
