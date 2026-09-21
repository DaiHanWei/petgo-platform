package com.tailtopia.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.places.service.AdminPlaceQueryService;
import com.tailtopia.admin.places.service.AdminPlaceService;
import com.tailtopia.admin.places.service.PlaceMergeService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：场所表对齐（2026-09-18，规格 spec-v130-places-schema-alignment.md §8）的端到端验收。
 *
 * <p>App 与后台两套实体写同一批表 —— 单测里各自 mock 仓库都是绿的，真正会出事的是
 * 「App 写 → 后台改 → App 读」这条跨实体链路，所以这里全程走真库：
 * App 标记 → 列表 / 附近 / 详情 → 不表态评论 → 后台抽屉（态度为空不 500）→ 后台实时计数 →
 * 后台合并（详情 / 分享页 / 评论都转到保留场所）→ 后台下架（App 查不到）。
 */
@TestPropertySource(properties = "media.oss.cdn-base-url=https://cdn.align.test")
class PlaceAlignmentIntegrationTest extends ApiIntegrationTest {

    private static final String CDN = "https://cdn.align.test";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlaceMergeService mergeService;
    @Autowired
    private AdminPlaceService adminPlaces;
    @Autowired
    private AdminPlaceQueryService adminQuery;
    @Autowired
    private AdminAccountService accountService;
    @Autowired
    private AdminUserDetailsService userDetailsService;

    private AdminUserDetails ops() {
        long seq = SEQ.incrementAndGet();
        String email = "align-" + seq + "@tailtopia.test";
        accountService.createAccount(email, "对齐 " + seq, AdminRole.CUSTOM, List.of(AdminPermissions.PLACE_MANAGE), 970000L + seq);
        return userDetailsService.loadByEmail(email, false);
    }

    /** App 标记一个场所，返回 token。 */
    private String mark(long userId, String name, double lat, double lng) throws Exception {
        Map<String, Object> body = Map.of(
                "name", name,
                "type", "CAFE",
                "tags", List.of("PETS_ALLOWED_INSIDE", "OUTDOOR_SEATING"),
                "latitude", lat,
                "longitude", lng,
                "addressText", "Jl. " + name,
                "photoUrls", List.of(CDN + "/places/" + name + "/a.jpg"));
        String res = mvc.perform(post("/api/v1/places")
                        .header("Authorization", userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("token").asText();
    }

    private JsonNode detail(String token) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/places/" + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private long idOf(String token) {
        return jdbc.queryForObject("SELECT id FROM places WHERE public_token = ?", Long.class, token);
    }

    @Test
    void appWritesAdminReadsAndAdminChangesAppFollows() throws Exception {
        long alice = newUser().getId();
        String name = "Align" + SEQ.incrementAndGet();
        String a = mark(alice, name, -6.2350, 106.8100);

        // ── App 标记落在后台那套表上：城市由 PlaceCityResolver 填、类型 / 坐标 / 照片 key 按后台列 ──
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT city, place_type, lat, lng, marked_by_user_id FROM places WHERE public_token = ?", a);
        assertThat(row.get("city")).isEqualTo("Jakarta");
        assertThat(row.get("place_type")).isEqualTo("CAFE");
        assertThat(((java.math.BigDecimal) row.get("lat")).doubleValue()).isEqualTo(-6.235);
        assertThat(((Number) row.get("marked_by_user_id")).longValue()).isEqualTo(alice);
        assertThat(jdbc.queryForObject("SELECT object_key FROM place_photos WHERE place_id = ?", String.class, idOf(a)))
                .as("D5：存 key 不存 URL").isEqualTo("places/" + name + "/a.jpg");

        // ── App 读：详情照片 URL 与改动前同形（CDN 前缀 + key + 去 EXIF 缩略）；列表 / 附近都能查到 ──
        JsonNode d = detail(a);
        assertThat(d.get("photos").get(0).get("url").asText())
                .startsWith(CDN + "/places/" + name + "/a.jpg?x-oss-process=");
        String list = mvc.perform(get("/api/v1/places")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains(a);
        String nearby = mvc.perform(get("/api/v1/places").param("lat", "-6.2351").param("lng", "106.8101"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(nearby).as("按距离：NUMERIC 列上的范围粗筛").contains(a);

        // ── 非本平台桶的照片地址 → 422（D5 顺带的安全闸）──
        mvc.perform(post("/api/v1/places")
                        .header("Authorization", userBearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Evil", "type", "CAFE",
                                "tags", List.of("PET_MENU"), "latitude", -6.2, "longitude", 106.8,
                                "addressText", "Jl. X", "photoUrls", List.of("https://evil.example/x.jpg")))))
                .andExpect(status().isUnprocessableEntity());

        // ── App 发一条**不表态**的评论（attitude 可空）→ 后台抽屉照常渲染，实时计数算得到它 ──
        mvc.perform(post("/api/v1/places/" + a + "/comments")
                        .header("Authorization", userBearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"tempatnya nyaman\"}"))
                .andExpect(status().is2xxSuccessful());
        AdminUserDetails ops = ops();
        mvc.perform(get("/admin/places/" + idOf(a) + "/drawer").with(user(ops)).header("HX-Request", "true"))
                .andExpect(status().isOk());
        var counts = adminQuery.countsOf(List.of(idOf(a))).get(idOf(a));
        assertThat(counts.photos()).isEqualTo(1);
        assertThat(counts.comments()).isEqualTo(1);
        assertThat(counts.recommend() + counts.notRecommend()).as("未表态不计入态度").isZero();

        // ── 后台合并 A → B：App 的详情 / 评论 / 分享页都转到 B ──
        String b = mark(alice, name + "B", -6.2360, 106.8110);
        mergeService.merge(idOf(a), idOf(b), 1L);
        assertThat(detail(a).get("token").asText()).as("D4：详情直接返回保留场所").isEqualTo(b);
        mvc.perform(get("/api/v1/places/" + a + "/comments")).andExpect(status().isOk());
        mvc.perform(get("/place/" + a))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/place/" + b));
        String listAfterMerge = mvc.perform(get("/api/v1/places")).andReturn().getResponse().getContentAsString();
        assertThat(listAfterMerge).as("MERGED 不进列表").doesNotContain(a).contains(b);

        // ── 后台下架 B：App 查不到（与「从来没有过」同一个 404）──
        adminPlaces.delist(idOf(b), 1L);
        mvc.perform(get("/api/v1/places/" + b)).andExpect(status().isNotFound());
        // 合并目标下架 → 旧 token 也不存在（只跳一层，目标不可见即不存在）。
        mvc.perform(get("/api/v1/places/" + a)).andExpect(status().isNotFound());
        mvc.perform(get("/place/" + b)).andExpect(status().isNotFound());

        // ── 后台软删：App 同样查不到 ──
        String c = mark(alice, name + "C", -6.2370, 106.8120);
        jdbc.update("UPDATE places SET deleted_at = now() WHERE public_token = ?", c);
        mvc.perform(get("/api/v1/places/" + c)).andExpect(status().isNotFound());
        assertThat(mvc.perform(get("/api/v1/places")).andReturn().getResponse().getContentAsString()).doesNotContain(c);
    }
}
