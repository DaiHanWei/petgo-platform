package com.tailtopia.shop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * L1（v1.4.2 shop Story 6.1 AC4 ~ AC6，D-16）：{@code /shop/banners} 与旧 {@code /shop/banner}。云端不跑，待本地验收。
 *
 * <p>表是全库共享的：本类造的 banner 权重都 ≥ 10^9，保证排在别的用例造的数据前面，断言只看本类造的 token。
 */
@TestPropertySource(properties = "media.oss.cdn-base-url=https://cdn.it.test")
class ShopBannerEndpointIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String uniq(String p) {
        return p + SEQ.incrementAndGet();
    }

    private String banner(int weight, String type, String value, String startExpr, String endExpr) {
        String token = uniq("bn");
        jdbc.update("INSERT INTO shop_banners (public_token, image_key, image_w, image_h, active, sort_weight, "
                + "target_type, target_value, start_at, end_at) VALUES (?, ?, 1600, 900, true, ?, ?, ?, "
                + startExpr + ", " + endExpr + ")", token, "shop-banner/" + token + ".jpg", weight, type, value);
        return token;
    }

    private String product(boolean active) {
        String token = uniq("pd");
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key, species, detail_html,
                        feeding_guide, shelf_life_note, return_policy, sort_weight, is_active)
                VALUES (?, 'P', 'B', 'MAKANAN', 'shop/x.jpg', 'DOG', '<p>x</p>', '[]'::jsonb, '-', 'NO_RETURN_AFTER_OPEN', 0, ?)
                """, token, active);
        return token;
    }

    private String claimCoupon(Integer total, int issued) {
        String token = uniq("cp");
        jdbc.update("""
                INSERT INTO shop_coupons (public_token, kind, name_id, name_en, amount, acquire_type,
                        issue_start_at, issue_end_at, valid_mode, valid_days, total_quantity, issued_count)
                VALUES (?, 'DISCOUNT', 'k', 'k', 5000, 'CLAIM', now() - interval '1 day', now() + interval '7 day',
                        'RELATIVE', 7, ?, ?)
                """, token, total, issued);
        return token;
    }

    private List<String> ids(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(n -> out.add(n.get("bannerId").asText()));
        return out;
    }

    @Test
    @DisplayName("8 张 active：排期外 / 下架商品 / 领完券 被剔除，CATEGORY → TOKO_HOME，≤ 6 张；游客 200；旧端点取第一张且 COUPON → NONE")
    void listFiltersAndCaps() throws Exception {
        int w = 2_000_000_000;
        String coupon = banner(w, "COUPON", claimCoupon(null, 0), "NULL", "NULL");
        String future = banner(w - 1, "NONE", null, "now() + interval '1 day'", "NULL");
        String delisted = banner(w - 2, "PRODUCT", product(false), "NULL", "NULL");
        String soldOut = banner(w - 3, "COUPON", claimCoupon(1, 1), "NULL", "NULL");
        String category = banner(w - 4, "CATEGORY", "CAMILAN", "now() - interval '1 day'", "now() + interval '1 day'");
        String onSale = banner(w - 5, "PRODUCT", product(true), "NULL", "NULL");
        String ext = banner(w - 6, "EXTERNAL", "https://tailtopia.id", "NULL", "NULL");
        String none1 = banner(w - 7, "NONE", null, "NULL", "NULL");

        JsonNode body = json.readTree(mvc.perform(get("/api/v1/shop/banners"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode items = body.get("items");
        assertThat(items.size()).isLessThanOrEqualTo(6);
        assertThat(ids(items)).startsWith(coupon, category, onSale, ext, none1)
                .doesNotContain(future, delisted, soldOut);
        assertThat(items.get(1).get("targetType").asText()).isEqualTo("TOKO_HOME");
        assertThat(items.get(1).has("targetValue")).isFalse();
        assertThat(items.get(0).get("targetType").asText()).isEqualTo("COUPON");
        assertThat(items.get(0).get("imageUrl").asText()).startsWith("https://cdn.it.test/");

        JsonNode legacy = json.readTree(mvc.perform(get("/api/v1/shop/banner"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(legacy.get("bannerId").asText()).isEqualTo(coupon);
        assertThat(legacy.get("targetType").asText()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("排期 CHECK：start >= end 被库层拒绝；target_type 接受 COUPON")
    void scheduleCheckConstraint() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> banner(0, "NONE", null, "now()", "now()"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(banner(0, "COUPON", "x", "NULL", "NULL")).isNotBlank();
    }
}
