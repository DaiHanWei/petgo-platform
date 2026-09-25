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
import org.junit.jupiter.api.BeforeEach;
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

    /** 真实后台账号 id（库存流水对 admin_accounts 有 FK，不能写死 1L）。 */
    private long actor;

    @BeforeEach
    void resolveActor() {
        actor = adminActorId();
    }

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

        listing.delist(productIdOfSku(willDelist), actor);

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
        inventory.stocktake(skuId, 0L, "清仓", actor);

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
        listing.delist(productIdOfSku(bad), actor);

        CartView v = cart.clearInvalid(uid);

        assertThat(v.invalidLines()).isEmpty();
        assertThat(v.lines()).hasSize(1);
        assertThat(v.lines().getFirst().skuToken()).isEqualTo(good);
    }

    // ---------- Story 4-1：行选择（SHOP-FR-04 / AD-S6） ----------

    @Test
    @DisplayName("🔴 新加购的行默认选中 —— DEFAULT TRUE 即老版本行为")
    void newlyAddedLineIsSelectedByDefault() {
        long uid = seedUser();
        String sku = seedSku(10, 100_000L);

        CartView v = cart.add(uid, sku, 2);

        assertThat(v.lines().getFirst().selected()).isTrue();
        assertThat(v.selectedSubtotal()).isEqualTo(200_000L);
        assertThat(v.selectedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 code review #4：取消勾选过的商品再次加购 → 重新勾上")
    void reAddingAnUnselectedLineSelectsItAgain() {
        long uid = seedUser();
        String b = seedSku(10, 50_000L);
        cart.add(uid, b, 1);
        cart.setSelected(uid, b, false);

        CartView v = cart.add(uid, b, 1);

        assertThat(v.selectedSubtotal()).as("再次加购 = 要买它；原先不勾，结算报「请至少选择一件」")
                .isEqualTo(100_000L);
    }

    @Test
    @DisplayName("🔴 code review #4：立即购买 → 只勾这一件，车内其它取消勾选（结算只结它）")
    void buyNowSelectsOnlyThatLine() {
        long uid = seedUser();
        String a = seedSku(10, 100_000L);
        String b = seedSku(10, 50_000L);
        cart.add(uid, a, 1);
        cart.add(uid, b, 1);
        cart.setSelected(uid, b, false);

        CartView v = cart.add(uid, b, 1, null, null, true);

        assertThat(v.selectedSubtotal()).as("只结 B（2 件 × 50.000），A 不再被带进结算").isEqualTo(100_000L);
        assertThat(v.subtotal()).as("取消勾选不是删除，A 仍在车里").isEqualTo(200_000L);
    }

    @Test
    @DisplayName("🔴 取消勾选一行：selectedSubtotal 变小，而 subtotal / itemCount 一分不动")
    void unselectingOneLineShrinksSelectedTotalsOnly() {
        long uid = seedUser();
        String a = seedSku(10, 100_000L);
        String b = seedSku(10, 50_000L);
        cart.add(uid, a, 2);        // 200.000，2 件
        cart.add(uid, b, 3);        // 150.000，3 件

        CartView v = cart.setSelected(uid, b, false);

        assertThat(v.subtotal())
                .as("老版本 App 的底栏读的就是 subtotal，它没有勾选框可点 —— 这个数不许变")
                .isEqualTo(350_000L);
        assertThat(v.itemCount()).isEqualTo(5);
        assertThat(v.selectedSubtotal()).isEqualTo(200_000L);
        assertThat(v.selectedCount()).as("selectedCount 与 itemCount 同为件数口径").isEqualTo(2);
        assertThat(v.lines()).hasSize(2);   // 取消勾选不是删除
    }

    @Test
    @DisplayName("全不选端点：selectedCount 归零，行还在、subtotal 还在")
    void deselectAllZeroesSelectedTotals() {
        long uid = seedUser();
        cart.add(uid, seedSku(10, 100_000L), 2);
        cart.add(uid, seedSku(10, 50_000L), 3);

        CartView v = cart.setAllSelected(uid, false);

        assertThat(v.selectedSubtotal()).isZero();
        assertThat(v.selectedCount()).isZero();
        assertThat(v.subtotal()).isEqualTo(350_000L);
        assertThat(v.lines()).hasSize(2);
        assertThat(v.lines()).allSatisfy(l -> assertThat(l.selected()).isFalse());
    }

    @Test
    @DisplayName("全选端点把全部行选回来")
    void selectAllBringsEveryLineBack() {
        long uid = seedUser();
        String a = seedSku(10, 100_000L);
        cart.add(uid, a, 2);
        cart.setAllSelected(uid, false);

        CartView v = cart.setAllSelected(uid, true);

        assertThat(v.selectedCount()).isEqualTo(2);
        assertThat(v.lines().getFirst().selected()).isTrue();
    }

    @Test
    @DisplayName("🔴 全选作用于**全部**行含失效行：失效行 selected=true 但永不计入选中合计")
    void selectAllCoversInvalidLinesWithoutCountingThem() {
        long uid = seedUser();
        String good = seedSku(10, 100_000L);
        String bad = seedSku(10, 50_000L);
        cart.add(uid, good, 2);
        cart.add(uid, bad, 3);
        listing.delist(productIdOfSku(bad), actor);

        CartView v = cart.setAllSelected(uid, true);

        assertThat(v.invalidLines()).hasSize(1);
        assertThat(v.invalidLines().getFirst().selected())
                .as("把失效行排除在「全选」外，用户在商品补货后会发现自己全选过的东西没被选上")
                .isTrue();
        assertThat(v.selectedSubtotal())
                .as("但它绝不计入选中合计 —— 过滤条件是「selected && invalidReason == null」")
                .isEqualTo(200_000L);
        assertThat(v.selectedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("🔴 勾选一个已售罄的行不报错 —— 勾选与「能不能买」是两件事")
    void selectingASoldOutLineIsNotAnError() {
        long uid = seedUser();
        String sku = seedSku(10, 100_000L);
        cart.add(uid, sku, 2);
        // 卖空它（加购时有货，之后被别人买光）
        Long skuId = jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?",
                Long.class, sku);
        jdbc.update("UPDATE sku_inventory SET actual = 0 WHERE sku_id = ?", skuId);

        // 🔴 这里若抛 409（requireWithinStock 被误加到选择路径上），
        //    「等它补货我再买」这件事就没法表达了。
        CartView v = cart.setSelected(uid, sku, true);

        assertThat(v.invalidLines()).hasSize(1);
        assertThat(v.selectedSubtotal()).isZero();
    }

    @Test
    @DisplayName("对不在本人车里的 SKU 勾选 → 404（与 setQty / remove 同口径）")
    void selectingAForeignSkuIsNotFound() {
        long uid = seedUser();
        String mine = seedSku(10, 100_000L);
        String notMine = seedSku(10, 50_000L);
        cart.add(uid, mine, 1);

        assertThatThrownBy(() -> cart.setSelected(uid, notMine, false))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("购物车中没有该商品");
    }

    @Test
    @DisplayName("勾选状态落库，跨请求可见（不是内存里的临时标记）")
    void selectionIsPersisted() {
        long uid = seedUser();
        String sku = seedSku(10, 100_000L);
        cart.add(uid, sku, 2);
        cart.setSelected(uid, sku, false);

        Boolean stored = jdbc.queryForObject("""
                SELECT i.selected FROM shop_cart_items i
                  JOIN shop_skus s ON s.id = i.sku_id
                 WHERE s.public_token = ?""", Boolean.class, sku);
        assertThat(stored).isFalse();
        assertThat(cart.view(uid).lines().getFirst().selected()).isFalse();
    }

    @Test
    @DisplayName("AC1：selected 列存在、NOT NULL、DEFAULT TRUE（存量行全 TRUE 的依据）")
    void selectedColumnIsNotNullDefaultTrue() {
        var row = jdbc.queryForMap("""
                SELECT is_nullable, column_default, data_type
                  FROM information_schema.columns
                 WHERE table_name = 'shop_cart_items' AND column_name = 'selected'""");

        assertThat(row.get("is_nullable")).isEqualTo("NO");
        assertThat(String.valueOf(row.get("column_default")))
                .as("DEFAULT TRUE 是老版本兼容（SHOP-NFR-04）的全部依据")
                .containsIgnoringCase("true");
        assertThat(row.get("data_type")).isEqualTo("boolean");
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
