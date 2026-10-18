package com.tailtopia.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1（需 Docker，云端不跑）：V1.4.0 ④ Story 2.1 · AC5 —— 场所收藏（下架 / 合并口径 P-6）。 */
class PlaceCollectIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PlaceRepository places;
    @Autowired
    private JdbcTemplate jdbc;

    static Place newPlace(PlaceRepository places, User creator) {
        return places.save(Place.mark(UUID.randomUUID().toString().replace("-", ""),
                "Kopi " + UUID.randomUUID().toString().substring(0, 6), PlaceType.CAFE,
                List.of(PlaceTag.PETS_ALLOWED_INSIDE), -6.2351, 106.8101, "Jl. Test", null, creator.getId(),
                "Jakarta"));
    }

    private void act(boolean collect, User u, String token, int expect) throws Exception {
        String url = "/api/v1/places/" + token + "/collect";
        mvc.perform((collect ? post(url) : delete(url)).header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().is(expect));
    }

    private void expectCollected(User u, String token, boolean value) throws Exception {
        mvc.perform(get("/api/v1/places/" + token).header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collected").value(value));
    }

    private long rows(User u, long placeId) {
        return jdbc.queryForObject("SELECT count(*) FROM place_collections WHERE user_id = ? AND place_id = ?",
                Long.class, u.getId(), placeId);
    }

    @Test
    @DisplayName("收藏 / 取消后详情 collected 随之变化；游客 false")
    void collectAndUncollect() throws Exception {
        User u = newUser();
        Place p = newPlace(places, u);
        act(true, u, p.getPublicToken(), 200);
        expectCollected(u, p.getPublicToken(), true);
        mvc.perform(get("/api/v1/places/" + p.getPublicToken())).andExpect(jsonPath("$.collected").value(false));
        act(false, u, p.getPublicToken(), 200);
        expectCollected(u, p.getPublicToken(), false);
    }

    @Test
    @DisplayName("下架场所：POST 404，DELETE 照样删掉本人那一行")
    void delisted() throws Exception {
        User u = newUser();
        Place p = newPlace(places, u);
        act(true, u, p.getPublicToken(), 200);
        jdbc.update("UPDATE places SET status = 'DELISTED' WHERE id = ?", p.getId());
        act(true, u, p.getPublicToken(), 404);
        act(false, u, p.getPublicToken(), 200);
        assertThat(rows(u, p.getId())).isZero();
    }

    @Test
    @DisplayName("合并：Y 并入 X 后用 Y token 收藏落在 X；合并前收藏的 Y → X 详情已收藏，取消删两行")
    void merged() throws Exception {
        User u = newUser();
        User v = newUser();
        Place x = newPlace(places, u);
        Place y = newPlace(places, u);
        act(true, u, y.getPublicToken(), 200); // 合并前收藏 Y
        jdbc.update("UPDATE places SET status = 'MERGED', merged_into_id = ? WHERE id = ?", x.getId(), y.getId());

        act(true, v, y.getPublicToken(), 200);
        assertThat(rows(v, x.getId())).isEqualTo(1);
        assertThat(rows(v, y.getId())).isZero();

        expectCollected(u, x.getPublicToken(), true);
        act(true, u, x.getPublicToken(), 200); // 已覆盖 → 不新增
        assertThat(rows(u, x.getId())).isZero();
        act(false, u, x.getPublicToken(), 200);
        assertThat(rows(u, x.getId()) + rows(u, y.getId())).isZero();
        expectCollected(u, x.getPublicToken(), false);
    }

    @Test
    @DisplayName("Story 2.2 code-review：已软删的 MERGED 场所单独成条，移除它不连带删保留场所那一条")
    void softDeletedMergedIsItsOwnItem() throws Exception {
        User u = newUser();
        Place a = newPlace(places, u);
        Place b = newPlace(places, u);
        act(true, u, a.getPublicToken(), 200);
        act(true, u, b.getPublicToken(), 200);
        jdbc.update("UPDATE places SET status = 'MERGED', merged_into_id = ?, deleted_at = now() WHERE id = ?",
                b.getId(), a.getId());
        act(false, u, a.getPublicToken(), 200);
        assertThat(rows(u, a.getId())).isZero();
        assertThat(rows(u, b.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Story 2.2 code-review：合并链 A→B→C，列表把 A 的行显示成 B，拿 B 的 token 能删掉 A 的行")
    void mergeChainEntryIsRemovable() throws Exception {
        User u = newUser();
        Place a = newPlace(places, u);
        Place b = newPlace(places, u);
        Place c = newPlace(places, u);
        act(true, u, a.getPublicToken(), 200);
        jdbc.update("UPDATE places SET status = 'MERGED', merged_into_id = ? WHERE id = ?", b.getId(), a.getId());
        jdbc.update("UPDATE places SET status = 'MERGED', merged_into_id = ? WHERE id = ?", c.getId(), b.getId());
        act(false, u, b.getPublicToken(), 200);
        assertThat(rows(u, a.getId())).isZero();
    }
}
