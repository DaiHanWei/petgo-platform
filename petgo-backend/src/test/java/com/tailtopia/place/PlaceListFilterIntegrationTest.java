package com.tailtopia.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 Docker postgres）：场所列表类型 / 标签筛选（Story 1.11 · AC2 / AC3 / AC5）。
 *
 * <p>原生 JSONB 包含查询只有真库能证：类型「或」、标签「且」、两维「且」，
 * 筛选在 200 截断<b>之前</b>生效，距离分支与其回落都带筛选。
 *
 * <p>⚠️ 共享测试库：断言只落在本类造的 token 上（按 token 取交集），不断言全局条数。
 * 距离分支用一个远离其它测试数据的坐标（北纬 60°，芬兰附近）隔离。
 */
class PlaceListFilterIntegrationTest extends ApiIntegrationTest {

    private static final double LAT = 60.1000;
    private static final double LNG = 24.9000;

    @Autowired
    private JdbcTemplate jdbc;

    private String insert(String type, String tagsJson, double lat, double lng, String createdOffset) {
        String token = "flt" + Long.toString(SEQ.incrementAndGet(), 36);
        jdbc.update("INSERT INTO places (public_token, name, place_type, tags, city, address_text, lat, lng, "
                        + "marked_by_user_id, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, CAST(? AS jsonb), 'Helsinki', 'Jl. Test', ?, ?, ?, 'ACTIVE', "
                        + "now() + CAST(? AS interval), now())",
                token, "Filter " + token, type, tagsJson, lat, lng, newUser().getId(), createdOffset);
        return token;
    }

    private List<String> tokens(MockHttpServletRequestBuilder req, String expectSortMode) throws Exception {
        JsonNode body = json.readTree(mvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(body.get("sortMode").asText()).isEqualTo(expectSortMode);
        List<String> out = new ArrayList<>();
        body.get("items").forEach(n -> out.add(n.get("token").asText()));
        return out;
    }

    @Test
    void typesAreOrTagsAreAndAcrossBothBranches() throws Exception {
        String cafeBoth = insert("CAFE", "[\"OUTDOOR_SEATING\",\"PET_MENU\",\"LEASH_REQUIRED\"]", LAT, LNG, "0 second");
        String parkBoth = insert("PARK", "[\"OUTDOOR_SEATING\",\"PET_MENU\"]", LAT + 0.001, LNG, "0 second");
        String cafeOne = insert("CAFE", "[\"OUTDOOR_SEATING\"]", LAT + 0.002, LNG, "0 second");
        String mallBoth = insert("MALL", "[\"OUTDOOR_SEATING\",\"PET_MENU\"]", LAT + 0.003, LNG, "0 second");
        List<String> mine = List.of(cafeBoth, parkBoth, cafeOne, mallBoth);

        for (boolean distance : new boolean[] {true, false}) {
            MockHttpServletRequestBuilder req = get("/api/v1/places")
                    .param("type", "CAFE").param("type", "PARK")
                    .param("tag", "OUTDOOR_SEATING").param("tag", "PET_MENU");
            if (distance) {
                req = req.param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG));
            }
            List<String> got = tokens(req, distance ? "distance" : "recent");
            assertThat(got.stream().filter(mine::contains).toList())
                    .as(distance ? "距离分支" : "按最新分支")
                    .containsExactlyInAnyOrder(cafeBoth, parkBoth);
        }
    }

    /** AC3：201 个更新的不匹配场所压不掉一个更早的匹配场所（先筛再截 200）。 */
    @Test
    void filterAppliesBeforeTheTruncation() throws Exception {
        String match = insert("PET_SERVICE", "[\"PET_PLAY_AREA\",\"LARGE_DOG_FRIENDLY\"]", LAT, LNG, "-1 day");
        List<String> noise = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            noise.add(insert("PET_SERVICE", "[\"PET_PLAY_AREA\"]", LAT, LNG, "1 day"));
        }
        // 🔴 这 201 条 created_at 在未来，会占住共享库「按最新」的前 200 名、挤掉别的用例的场所 —— 用完必删。
        try {
            assertFilterBeforeTruncation(match);
        } finally {
            for (String t : noise) {
                jdbc.update("DELETE FROM places WHERE public_token = ?", t);
            }
        }
    }

    private void assertFilterBeforeTruncation(String match) throws Exception {
        List<String> recent = tokens(get("/api/v1/places")
                .param("type", "PET_SERVICE").param("tag", "PET_PLAY_AREA").param("tag", "LARGE_DOG_FRIENDLY"),
                "recent");
        assertThat(recent).contains(match);
        List<String> near = tokens(get("/api/v1/places")
                .param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG))
                .param("type", "PET_SERVICE").param("tag", "PET_PLAY_AREA").param("tag", "LARGE_DOG_FRIENDLY"),
                "distance");
        assertThat(near).contains(match);
    }

    /** AC5：距离分支粗筛为空 → 回落按最新也带筛选。 */
    @Test
    void distanceFallbackKeepsTheFilter() throws Exception {
        String hotel = insert("HOTEL", "[\"PETS_ALLOWED_INSIDE\"]", LAT, LNG, "0 second");
        String cafe = insert("CAFE", "[\"PETS_ALLOWED_INSIDE\"]", LAT, LNG, "0 second");
        // 南极附近：半径内一个场所都没有 → 回落。
        List<String> got = tokens(get("/api/v1/places").param("lat", "-80").param("lng", "0")
                .param("type", "HOTEL"), "recent");
        assertThat(got).contains(hotel).doesNotContain(cafe);
    }

    @Test
    void illegalValueIs422ProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/places").param("type", "BAR"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/places").param("tag", "PET_MENU").param("tag", "PET_MENU")
                        .param("tag", "PET_MENU").param("tag", "PET_MENU").param("tag", "PET_MENU")
                        .param("tag", "PET_MENU").param("tag", "PET_MENU"))
                .andExpect(status().isUnprocessableEntity());
    }
}
