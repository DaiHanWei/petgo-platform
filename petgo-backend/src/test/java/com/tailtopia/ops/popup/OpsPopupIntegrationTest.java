package com.tailtopia.ops.popup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.account.service.AccountDeletionService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.ops.popup.domain.OpsPopup;
import com.tailtopia.ops.popup.domain.OpsPopupAudience;
import com.tailtopia.ops.popup.domain.OpsPopupFrequency;
import com.tailtopia.ops.popup.repository.OpsPopupRepository;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1（需 Postgres + Redis，云端不跑）：运营弹窗读接口与曝光（V1.3.4 Story 5.1 · AC6）。
 *
 * <p>上下文启动即验 Flyway {@code V20261012_1106} + {@code ddl-auto=validate}（AC1.8）。
 * 数据由 {@link OpsPopup#create} + 仓储 / JdbcTemplate 预置（本主题无写接口，D-2）。
 * {@code @BeforeEach} 清 {@code ops_popups}（曝光级联删；该表只有本测试类写入）。
 */
class OpsPopupIntegrationTest extends ApiIntegrationTest {

    private static final String CANDIDATES = "/api/v1/ops-popups/candidates";

    @Autowired
    private OpsPopupRepository popups;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AccountDeletionRepository deletions;
    @Autowired
    private AccountDeletionService accountDeletion;
    /** 全局 BannedVetFilter 查不到兽医行直接 401 —— VET token 必须对应真实 ACTIVE 兽医。 */
    @Autowired
    private VetTestSupport vets;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ops_popups");
    }

    private OpsPopup save(OpsPopupAudience audience, OpsPopupFrequency frequency, int priority,
            OpsTargetType targetType, String targetValue, boolean active) {
        OpsPopup p = OpsPopup.create("internal-" + SEQ.incrementAndGet(),
                Instant.now().minus(Duration.ofHours(1)), null, priority, audience, 7, frequency,
                "https://cdn.example/popup/id.jpg", null, "Judul", "Title", "Isi", "Body", "Lihat", "See",
                targetType, targetValue);
        if (active) {
            p.activate();
        }
        return popups.save(p);
    }

    private OpsPopup saveActive(OpsPopupAudience audience, OpsPopupFrequency frequency) {
        return save(audience, frequency, 0, OpsTargetType.NONE, null, true);
    }

    private List<String> tokens(String bearer) throws Exception {
        var req = get(CANDIDATES);
        if (bearer != null) {
            req = req.header("Authorization", bearer);
        }
        String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode items = json.readTree(body).get("items");
        List<String> out = new ArrayList<>();
        items.forEach(n -> out.add(n.get("token").asText()));
        return out;
    }

    private void impression(String token, String bearer) throws Exception {
        mvc.perform(post("/api/v1/ops-popups/" + token + "/impressions").header("Authorization", bearer))
                .andExpect(status().isNoContent());
    }

    private void registeredAgo(User u, String interval) {
        jdbc.update("UPDATE users SET created_at = now() - CAST(? AS interval) WHERE id = ?", interval, u.getId());
    }

    private String seedProduct(boolean active) {
        String token = "opsp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, feeding_guide, shelf_life_note, return_policy,
                        sort_weight, is_active)
                VALUES (?, ?, 'BrandX', 'MAKANAN', ?, 'DOG', '<p>x</p>', '[]'::jsonb, '18 bulan',
                        'NO_RETURN_AFTER_OPEN', 10, ?)
                """, token, "Produk " + token, "shop/" + token + "/main.jpg", active);
        return token;
    }

    @Test
    @DisplayName("响应形状：只露 token，不露 id / name / priority / audience / 时间窗；空列表是 200 items=[]")
    void responseShape() throws Exception {
        mvc.perform(get(CANDIDATES)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());

        OpsPopup p = saveActive(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        mvc.perform(get(CANDIDATES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].token").value(p.getPublicToken()))
                .andExpect(jsonPath("$.items[0].frequency").value("ONCE"))
                .andExpect(jsonPath("$.items[0].imageUrlId").value("https://cdn.example/popup/id.jpg"))
                .andExpect(jsonPath("$.items[0].imageUrlEn").doesNotExist())
                .andExpect(jsonPath("$.items[0].buttonTextId").value("Lihat"))
                .andExpect(jsonPath("$.items[0].target.type").value("NONE"))
                .andExpect(jsonPath("$.items[0].target.value").doesNotExist())
                .andExpect(jsonPath("$.items[0].id").doesNotExist())
                .andExpect(jsonPath("$.items[0].name").doesNotExist())
                .andExpect(jsonPath("$.items[0].priority").doesNotExist())
                .andExpect(jsonPath("$.items[0].audience").doesNotExist())
                .andExpect(jsonPath("$.items[0].newUserDays").doesNotExist())
                .andExpect(jsonPath("$.items[0].status").doesNotExist())
                .andExpect(jsonPath("$.items[0].startsAt").doesNotExist())
                .andExpect(jsonPath("$.items[0].endsAt").doesNotExist());
    }

    @Test
    @DisplayName("游客只见 ALL，且不受登录用户曝光影响")
    void guestSeesOnlyAllAndIgnoresImpressions() throws Exception {
        OpsPopup all = saveActive(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        saveActive(OpsPopupAudience.NEW, OpsPopupFrequency.ONCE);
        saveActive(OpsPopupAudience.OLD, OpsPopupFrequency.ONCE);

        assertThat(tokens(null)).containsExactly(all.getPublicToken());

        User u = newUser();
        impression(all.getPublicToken(), userBearer(u.getId()));
        assertThat(tokens(null)).containsExactly(all.getPublicToken());
    }

    @Test
    @DisplayName("NEW / OLD 边界：注册 ≤ N 天为 NEW，> N 天为 OLD")
    void newOldBoundary() throws Exception {
        OpsPopup newOnly = saveActive(OpsPopupAudience.NEW, OpsPopupFrequency.ONCE);
        OpsPopup oldOnly = saveActive(OpsPopupAudience.OLD, OpsPopupFrequency.ONCE);

        User fresh = newUser();
        registeredAgo(fresh, "6 days 23 hours");
        assertThat(tokens(userBearer(fresh.getId()))).containsExactly(newOnly.getPublicToken());

        User veteran = newUser();
        registeredAgo(veteran, "7 days 1 minute");
        assertThat(tokens(userBearer(veteran.getId()))).containsExactly(oldOnly.getPublicToken());
    }

    @Test
    @DisplayName("停用 / 未开始 / 已结束不下发；ends_at IS NULL 长期有效")
    void statusAndWindow() throws Exception {
        save(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, 0, OpsTargetType.NONE, null, false);
        OpsPopup notStarted = saveActive(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        jdbc.update("UPDATE ops_popups SET starts_at = now() + interval '1 hour' WHERE id = ?", notStarted.getId());
        OpsPopup ended = saveActive(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        jdbc.update("UPDATE ops_popups SET starts_at = now() - interval '2 hours', ends_at = now() - interval '1 hour'"
                + " WHERE id = ?", ended.getId());
        OpsPopup endless = saveActive(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        OpsPopup bounded = saveActive(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        jdbc.update("UPDATE ops_popups SET ends_at = now() + interval '1 day' WHERE id = ?", bounded.getId());

        assertThat(tokens(null)).containsExactlyInAnyOrder(endless.getPublicToken(), bounded.getPublicToken());
    }

    @Test
    @DisplayName("商品下架即消失；EXTERNAL 非 http(s) 不下发")
    void targetAvailability() throws Exception {
        String product = seedProduct(true);
        OpsPopup productPopup = save(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, 0, OpsTargetType.PRODUCT,
                product, true);
        save(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, 0, OpsTargetType.EXTERNAL, "ftp://x.id/a", true);

        assertThat(tokens(null)).containsExactly(productPopup.getPublicToken());

        jdbc.update("UPDATE shop_products SET is_active = false WHERE public_token = ?", product);
        assertThat(tokens(null)).isEmpty();
    }

    @Test
    @DisplayName("排序 priority DESC, created_at DESC, id DESC")
    void ordering() throws Exception {
        OpsPopup low = save(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, 1, OpsTargetType.NONE, null, true);
        OpsPopup highOld = save(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, 5, OpsTargetType.NONE, null, true);
        OpsPopup highNew = save(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE, 5, OpsTargetType.NONE, null, true);
        jdbc.update("UPDATE ops_popups SET created_at = now() - interval '1 day' WHERE id = ?", highOld.getId());

        assertThat(tokens(null)).containsExactly(highNew.getPublicToken(), highOld.getPublicToken(),
                low.getPublicToken());
    }

    @Test
    @DisplayName("ONCE 上报后不再下发；编辑后频次不重置")
    void onceAfterImpressionAndEditKeepsFrequency() throws Exception {
        OpsPopup once = saveActive(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        User u = newUser();
        String bearer = userBearer(u.getId());
        assertThat(tokens(bearer)).containsExactly(once.getPublicToken());

        impression(once.getPublicToken(), bearer);
        assertThat(tokens(bearer)).isEmpty();

        jdbc.update("UPDATE ops_popups SET title_id = 'Judul baru', updated_at = now() WHERE id = ?", once.getId());
        assertThat(tokens(bearer)).as("编辑不换 id，频次不重置").isEmpty();
    }

    @Test
    @DisplayName("DAILY：当天 WIB 内不再下发，昨天 WIB 的曝光不算")
    void dailyWithinWibDay() throws Exception {
        OpsPopup daily = saveActive(OpsPopupAudience.ALL, OpsPopupFrequency.DAILY);
        User u = newUser();
        String bearer = userBearer(u.getId());

        impression(daily.getPublicToken(), bearer);
        assertThat(tokens(bearer)).isEmpty();

        // 把曝光挪到「昨天 WIB 23:59」—— 当前 WIB 日内已无曝光，恢复下发。
        jdbc.update("""
                UPDATE ops_popup_impressions
                   SET shown_at = (date_trunc('day', now() AT TIME ZONE 'Asia/Jakarta') - interval '1 minute')
                                  AT TIME ZONE 'Asia/Jakarta'
                 WHERE user_id = ?
                """, u.getId());
        assertThat(tokens(bearer)).containsExactly(daily.getPublicToken());
    }

    @Test
    @DisplayName("VET token 按游客处理")
    void vetTreatedAsGuest() throws Exception {
        OpsPopup all = saveActive(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        saveActive(OpsPopupAudience.OLD, OpsPopupFrequency.ONCE);
        saveActive(OpsPopupAudience.NEW, OpsPopupFrequency.ONCE);

        assertThat(tokens(vetBearer(vets.newActiveVet("ops-popup-it").getId()))).containsExactly(all.getPublicToken());
    }

    @Test
    @DisplayName("曝光：未知 token 404；游客 401；VET 403")
    void impressionErrors() throws Exception {
        OpsPopup p = saveActive(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        User u = newUser();

        mvc.perform(post("/api/v1/ops-popups/nope/impressions").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/ops-popups/" + p.getPublicToken() + "/impressions"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/ops-popups/" + p.getPublicToken() + "/impressions")
                        .header("Authorization", vetBearer(vets.newActiveVet("ops-popup-it").getId())))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops_popup_impressions", Long.class)).isZero();
    }

    @Test
    @DisplayName("已注销用户的残留 token 上报曝光 → 404，不写行")
    void deletedUserImpressionIs404() throws Exception {
        OpsPopup p = saveActive(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        User u = newUser();
        AccountDeletion d = deletions.save(AccountDeletion.request(u.getId()));
        accountDeletion.execute(d.getId());

        mvc.perform(post("/api/v1/ops-popups/" + p.getPublicToken() + "/impressions")
                        .header("Authorization", userBearer(u.getId())))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops_popup_impressions WHERE user_id = ?", Long.class,
                u.getId())).isZero();
    }

    @Test
    @DisplayName("曝光不去重：同一用户重复上报照插")
    void impressionsNotDeduplicated() throws Exception {
        OpsPopup p = saveActive(OpsPopupAudience.ALL, OpsPopupFrequency.DAILY);
        User u = newUser();
        impression(p.getPublicToken(), userBearer(u.getId()));
        impression(p.getPublicToken(), userBearer(u.getId()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops_popup_impressions WHERE user_id = ?", Long.class,
                u.getId())).isEqualTo(2L);
    }

    @Test
    @DisplayName("注销后该用户曝光行被物理删除（users 行只匿名化，CASCADE 不触发）")
    void accountDeletionPurgesImpressions() throws Exception {
        OpsPopup p = saveActive(OpsPopupAudience.ALL, OpsPopupFrequency.ONCE);
        User u = newUser();
        impression(p.getPublicToken(), userBearer(u.getId()));

        AccountDeletion d = deletions.save(AccountDeletion.request(u.getId()));
        accountDeletion.execute(d.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops_popup_impressions WHERE user_id = ?", Long.class,
                u.getId())).isZero();
        assertThat(users.findById(u.getId())).as("users 行仍在（匿名化）").isPresent();
    }
}
