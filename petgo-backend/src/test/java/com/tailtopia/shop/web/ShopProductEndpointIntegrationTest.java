package com.tailtopia.shop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1 集成：Story 1.1 商品与 SKU 只读查询（dev profile）。上下文启动即验 Flyway V101 +
 * {@code ddl-auto=validate}（shop_products / shop_skus 契约）。
 *
 * <p>核心断言：<b>游客无 JWT 可访问</b>（FR-93A）· 未知 token → 404 非 403（防枚举）·
 * 非法 category → 422 · 未上架商品不可见 · {@code feeding_guide} JSONB 原样往返 ·
 * SKU 退货规则继承 · <b>响应体不含自增 id</b>（NFR-3）· 非法枚举值被 DB CHECK 拒绝。
 *
 * <p>⚠️ 需真实 PostgreSQL + Redis（{@code ApiIntegrationTest} 无 Testcontainers）。
 */
class ShopProductEndpointIntegrationTest extends ApiIntegrationTest {

    private static final String BASE = "/api/v1/shop/products";

    @Autowired
    private JdbcTemplate jdbc;

    /** 直接 SQL 造数：本 Story 无写接口（写入属 1.3 后台）。 */
    private String seedProduct(String token, String category, boolean active, String feedingGuide) {
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, feeding_guide, shelf_life_note, return_policy,
                        sort_weight, is_active)
                VALUES (?, ?, ?, ?, ?, 'DOG', '<p>x</p>', ?::jsonb, '18 bulan',
                        'NO_RETURN_AFTER_OPEN', 10, ?)
                """, token, "Produk " + token, "BrandX", category, "shop/" + token + "/main.jpg",
                feedingGuide, active);
        return token;
    }

    private void seedSku(String productToken, String skuToken, long price, String returnPolicy) {
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price, net_weight_g,
                        return_policy)
                SELECT ?, id, '3 kg', ?, 3000, ? FROM shop_products WHERE public_token = ?
                """, skuToken, price, returnPolicy, productToken);
    }

    private String uniq(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }

    /** Story 1.2：给指定 SKU 建库存行。 */
    private void seedInventory(String skuToken, long actual, long locked) {
        jdbc.update("""
                INSERT INTO sku_inventory (sku_id, actual, locked)
                SELECT id, ?, ? FROM shop_skus WHERE public_token = ?
                """, actual, locked, skuToken);
    }

    @Test
    @DisplayName("🔴 游客无 JWT 可访问列表与详情（FR-93A）")
    void guestCanBrowse() throws Exception {
        String token = seedProduct(uniq("g"), "MAKANAN", true, "[]");
        seedSku(token, uniq("s"), 285_000L, null);

        // 不带 Authorization 头
        mvc.perform(get(BASE)).andExpect(status().isOk());
        mvc.perform(get(BASE + "/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(token));
    }

    @Test
    @DisplayName("🔴 响应体不含自增 id —— 只暴露 publicToken（NFR-3）")
    void responseNeverExposesInternalId() throws Exception {
        String token = seedProduct(uniq("n"), "MAKANAN", true, "[]");
        seedSku(token, uniq("s"), 285_000L, null);

        String body = mvc.perform(get(BASE + "/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.skus[0].id").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"id\"");
    }

    @Test
    @DisplayName("🔴 未知 token → 404 而非 403（防枚举探测）")
    void unknownTokenIs404() throws Exception {
        mvc.perform(get(BASE + "/definitelyNotARealToken")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("未上架商品不可见 —— 详情 404、列表不含")
    void inactiveProductHidden() throws Exception {
        String token = seedProduct(uniq("i"), "MAKANAN", false, "[]");

        mvc.perform(get(BASE + "/" + token)).andExpect(status().isNotFound());
        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.token=='" + token + "')]").isEmpty());
    }

    @Test
    @DisplayName("非法 category → 422，不静默返回全量")
    void illegalCategoryIsRejected() throws Exception {
        mvc.perform(get(BASE).param("category", "NOT_A_CATEGORY"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("category 筛选生效")
    void categoryFilterWorks() throws Exception {
        String makanan = seedProduct(uniq("m"), "MAKANAN", true, "[]");
        String camilan = seedProduct(uniq("c"), "CAMILAN", true, "[]");

        mvc.perform(get(BASE).param("category", "CAMILAN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.token=='" + camilan + "')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.token=='" + makanan + "')]").isEmpty());
    }

    @Test
    @DisplayName("🔴 feeding_guide JSONB 结构化数组原样往返（FR-109 唯一计算依据）")
    void feedingGuideRoundTrip() throws Exception {
        String guide = """
                [{"weightMinKg":1,"weightMaxKg":5,"gramsPerDay":60},
                 {"weightMinKg":5,"weightMaxKg":10,"gramsPerDay":110},
                 {"weightMinKg":10,"weightMaxKg":25,"gramsPerDay":210}]""";
        String token = seedProduct(uniq("f"), "MAKANAN", true, guide);

        mvc.perform(get(BASE + "/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedingGuide.length()").value(3))
                .andExpect(jsonPath("$.feedingGuide[1].weightMinKg").value(5))
                .andExpect(jsonPath("$.feedingGuide[1].weightMaxKg").value(10))
                .andExpect(jsonPath("$.feedingGuide[1].gramsPerDay").value(110));
    }

    @Test
    @DisplayName("SKU 退货规则：为空继承商品级，有值则覆盖（FR-94A）")
    void skuReturnPolicyInheritance() throws Exception {
        String token = seedProduct(uniq("r"), "MAKANAN", true, "[]");
        seedSku(token, uniq("s"), 165_000L, null);                 // 继承 → NO_RETURN_AFTER_OPEN
        seedSku(token, uniq("s"), 285_000L, "RETURNABLE");         // 覆盖

        mvc.perform(get(BASE + "/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnPolicy").value("NO_RETURN_AFTER_OPEN"))
                .andExpect(jsonPath("$.skus[0].returnPolicy").value("NO_RETURN_AFTER_OPEN"))
                .andExpect(jsonPath("$.skus[1].returnPolicy").value("RETURNABLE"));
    }

    @Test
    @DisplayName("库存三态（Story 1.2）：售罄 / 低库存含真实剩余数 / 充足不给数字")
    void stockStatusThreeStates() throws Exception {
        String token = seedProduct(uniq("st"), "MAKANAN", true, "[]");
        String sOut = uniq("s");
        String sLow = uniq("s");
        String sIn = uniq("s");
        seedSku(token, sOut, 100_000L, null);
        seedSku(token, sLow, 200_000L, null);
        seedSku(token, sIn, 300_000L, null);
        seedInventory(sOut, 0, 0);      // 售罄
        seedInventory(sLow, 3, 0);      // 低库存（默认阈值 5）
        seedInventory(sIn, 50, 0);      // 充足

        mvc.perform(get(BASE + "/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skus[0].stockStatus").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.skus[0].remaining").doesNotExist())
                .andExpect(jsonPath("$.skus[1].stockStatus").value("LOW_STOCK"))
                // 🔴 真实剩余数，不虚构
                .andExpect(jsonPath("$.skus[1].remaining").value(3))
                .andExpect(jsonPath("$.skus[2].stockStatus").value("IN_STOCK"))
                .andExpect(jsonPath("$.skus[2].remaining").doesNotExist());
    }

    @Test
    @DisplayName("🔴 售罄商品不自动下架 —— 列表与详情仍可见（FR-95）")
    void outOfStockProductStaysVisible() throws Exception {
        String token = seedProduct(uniq("os"), "MAKANAN", true, "[]");
        String sku = uniq("s");
        seedSku(token, sku, 100_000L, null);
        seedInventory(sku, 0, 0);

        mvc.perform(get(BASE + "/" + token)).andExpect(status().isOk());
        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.token=='" + token + "')]").isNotEmpty());
    }

    @Test
    @DisplayName("无库存行的 SKU 视为售罄，不报错")
    void skuWithoutInventoryRowIsOutOfStock() throws Exception {
        String token = seedProduct(uniq("ni"), "MAKANAN", true, "[]");
        seedSku(token, uniq("s"), 100_000L, null);   // 故意不建库存行

        mvc.perform(get(BASE + "/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skus[0].stockStatus").value("OUT_OF_STOCK"));
    }

    @Test
    @DisplayName("🔴 DB CHECK 生效：非法枚举值被拒（含已砍的「换」）")
    void dbCheckRejectsIllegalEnum() {
        // 换货已砍（C-13）：数据库层字面拒绝任何非三值的 return_policy
        assertThat(insertFails("EXCHANGEABLE")).isTrue();
        assertThat(insertFails("RETURNABLE_AND_EXCHANGEABLE")).isTrue();
    }

    private boolean insertFails(String returnPolicy) {
        try {
            jdbc.update("""
                    INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                            species, detail_html, shelf_life_note, return_policy, is_active)
                    VALUES (?, 'x', 'y', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', ?, true)
                    """, uniq("bad"), returnPolicy);
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
