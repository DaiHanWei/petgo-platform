package com.tailtopia.shop.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.shop.service.AdminShopListingService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.service.InventoryMovementService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** L1：购物车（Story 3.1，FR-96）。 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class CartIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private CartService cart;
    @Autowired
    private InventoryMovementService inventory;
    @Autowired
    private AdminShopListingService listing;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "cart" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "cart" + n);
    }

    /** 造一个已上架、有库存的 SKU，返回 skuToken。 */
    private String seedSku(long stock, long price) {
        String pToken = "cp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n',
                        'NO_RETURN_AFTER_OPEN', true)
                """, pToken);
        Long productId = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "cs" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', ?)
                """, sToken, productId, price);
        Long skuId = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)",
                skuId, stock);
        return sToken;
    }

    private long productIdOfSku(String skuToken) {
        return jdbc.queryForObject(
                "SELECT product_id FROM shop_skus WHERE public_token = ?", Long.class, skuToken);
    }

    // ---------- 基本操作 ----------

    @Test
    @DisplayName("加购同一 SKU 累加数量，不新增行")
    void addingSameSkuAccumulates() {
        long uid = seedUser();
        String sku = seedSku(10, 285_000L);

        cart.add(uid, sku, 2);
        CartView v = cart.add(uid, sku, 3);

        assertThat(v.lines()).hasSize(1);
        assertThat(v.lines().getFirst().qty()).isEqualTo(5);
        assertThat(v.subtotal()).isEqualTo(5 * 285_000L);
    }

    @Test
    @DisplayName("🔴 itemCount 是【件数】不是种类数（角标要跟用户脑子里的『买了几件』对上）")
    void itemCountIsUnitsNotDistinctSkus() {
        long uid = seedUser();
        cart.add(uid, seedSku(10, 1000L), 3);
        cart.add(uid, seedSku(10, 2000L), 4);

        CartView v = cart.view(uid);
        assertThat(v.lines()).hasSize(2);
        assertThat(v.itemCount()).as("3 + 4 = 7 件，不是 2 种").isEqualTo(7);
    }

    @Test
    @DisplayName("🔴 超出可售库存 → 明确报错，不静默截断（截断会让用户以为加进去了）")
    void beyondStockIsRejectedLoudly() {
        long uid = seedUser();
        String sku = seedSku(3, 1000L);

        assertThatThrownBy(() -> cart.add(uid, sku, 4))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("最多可购买 3 件");

        assertThat(cart.view(uid).lines()).isEmpty();
    }

    @Test
    @DisplayName("累加后超库存也要被拦（2 + 2 > 3）")
    void accumulationRespectsStock() {
        long uid = seedUser();
        String sku = seedSku(3, 1000L);
        cart.add(uid, sku, 2);

        assertThatThrownBy(() -> cart.add(uid, sku, 2)).isInstanceOf(AppException.class);
        assertThat(cart.view(uid).lines().getFirst().qty()).isEqualTo(2);
    }

    @Test
    @DisplayName("改数量为 0 = 删除（前端减号减到 0 就是删）")
    void settingQtyToZeroRemoves() {
        long uid = seedUser();
        String sku = seedSku(10, 1000L);
        cart.add(uid, sku, 2);

        CartView v = cart.setQty(uid, sku, 0);
        assertThat(v.lines()).isEmpty();
        assertThat(v.itemCount()).isZero();
    }

    // ---------- 🔴 失效商品单独成组 ----------

    @Test
    @DisplayName("🔴 商品下架 → 该行进失效组，不参与合计、不计入件数，但【不消失】")
    void delistedLineMovesToInvalidGroupWithoutVanishing() {
        long uid = seedUser();
        String good = seedSku(10, 1000L);
        String willDelist = seedSku(10, 2000L);
        cart.add(uid, good, 2);
        cart.add(uid, willDelist, 3);

        listing.delist(productIdOfSku(willDelist), ACTOR);

        CartView v = cart.view(uid);
        assertThat(v.lines()).hasSize(1);
        assertThat(v.invalidLines()).hasSize(1);
        assertThat(v.invalidLines().getFirst().invalidReason())
                .isEqualTo(CartView.REASON_DELISTED);
        assertThat(v.subtotal()).as("失效行不参与合计").isEqualTo(2 * 1000L);
        assertThat(v.itemCount()).as("失效行不计入角标").isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 售罄与下架是【两个不同的失效原因】——前者暂时后者永久，给同一句话会让用户做错决定")
    void outOfStockAndDelistedAreDistinctReasons() {
        long uid = seedUser();
        String sku = seedSku(5, 1000L);
        cart.add(uid, sku, 2);

        // 盘点归零 → 售罄（商品仍在架上）
        long skuId = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sku);
        inventory.stocktake(skuId, 0L, "清仓", ACTOR);

        CartView v = cart.view(uid);
        assertThat(v.invalidLines()).hasSize(1);
        assertThat(v.invalidLines().getFirst().invalidReason())
                .isEqualTo(CartView.REASON_OUT_OF_STOCK);
    }

    @Test
    @DisplayName("清空失效商品：只删失效行，有效行一条不动")
    void clearInvalidRemovesOnlyInvalidLines() {
        long uid = seedUser();
        String good = seedSku(10, 1000L);
        String bad = seedSku(10, 2000L);
        cart.add(uid, good, 2);
        cart.add(uid, bad, 3);
        listing.delist(productIdOfSku(bad), ACTOR);

        CartView v = cart.clearInvalid(uid);

        assertThat(v.invalidLines()).isEmpty();
        assertThat(v.lines()).hasSize(1);
        assertThat(v.lines().getFirst().skuToken()).isEqualTo(good);
    }

    // ---------- 🔴 游客无购物车 ----------

    @Test
    @DisplayName("🔒 游客访问购物车 → 401（FR-96：加购是漏斗上第一个需要身份的动作）")
    void guestHasNoCart() throws Exception {
        mvc.perform(get("/api/v1/me/cart"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("🔴 单店模型：购物车表没有任何店铺/卖家分组列")
    void noShopGroupingColumns() {
        Integer cols = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name IN ('shop_carts','shop_cart_items')
                   AND column_name IN ('shop_id','seller_id','merchant_id','store_id')
                """, Integer.class);
        assertThat(cols)
                .as("照搬 Shopee 的多店铺结构会让购物车/结算/订单三处都多出一层永远只有一个元素的嵌套")
                .isZero();
    }
}
