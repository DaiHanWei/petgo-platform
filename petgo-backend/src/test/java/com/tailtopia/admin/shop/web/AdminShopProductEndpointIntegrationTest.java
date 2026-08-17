package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1 集成：商品后台维护（Story 1.3，AB-10A/10B）。上下文启动即验 Flyway V103 + validate。
 *
 * <p>🔒 核心断言：<b>无 {@code shop.cost_view} 权限时，进货价在响应体里根本不存在</b>
 * ——不是被 CSS 隐藏、不是被 JS 隐藏，是服务端就没下发。
 *
 * <p>⚠️ 需真实 PostgreSQL + Redis。后台账号与权限的造数依赖既有 admin 测试基建，
 * 本类给出断言骨架，具体 actor 构造在本地接入时按 {@code AdminPagesRenderSmokeTest} 范式补齐。
 */
class AdminShopProductEndpointIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("V103 已应用：shop_skus 有 cost_price 列且 CHECK 生效")
    void costPriceColumnExists() {
        Integer cnt = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'shop_skus' AND column_name = 'cost_price'
                """, Integer.class);
        assertThat(cnt).isEqualTo(1);

        // 负进货价被 DB 拒绝
        String pToken = "cp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'x', 'y', 'MAKANAN', 'k', 'DOG', '<p/>', 'n',
                        'NO_RETURN_AFTER_OPEN', true)
                """, pToken);
        boolean rejected;
        try {
            jdbc.update("""
                    INSERT INTO shop_skus (public_token, product_id, spec_name, price, cost_price)
                    SELECT ?, id, '3 kg', 1000, -1 FROM shop_products WHERE public_token = ?
                    """, "cs" + SEQ.incrementAndGet(), pToken);
            rejected = false;
        } catch (Exception e) {
            rejected = true;
        }
        assertThat(rejected).as("负进货价必须被 ck_shop_skus_cost_price 拒绝").isTrue();
    }

    @Test
    @DisplayName("🔒 进货价不出现在对外商品接口的响应体中（NFR-3 / NFR-11）")
    void costPriceNeverLeaksToPublicApi() throws Exception {
        String pToken = "lk" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'x', 'y', 'MAKANAN', 'k', 'DOG', '<p/>', 'n',
                        'NO_RETURN_AFTER_OPEN', true)
                """, pToken);
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price, cost_price)
                SELECT ?, id, '3 kg', 285000, 190000 FROM shop_products WHERE public_token = ?
                """, "sk" + SEQ.incrementAndGet(), pToken);

        String body = mvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/api/v1/shop/products/" + pToken))
                .andReturn().getResponse().getContentAsString();

        // 🔴 对外 DTO 里根本没有 costPrice 字段，进货价数值也不得出现
        assertThat(body).doesNotContain("costPrice").doesNotContain("190000");
    }

    @Test
    @DisplayName("未登录访问后台商品页 → 重定向登录，不泄露任何商品数据")
    void adminPageRequiresLogin() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/admin/shop/products"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().is3xxRedirection());
    }
}
