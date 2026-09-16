package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：售罄数 SQL 的文本护栏（Story 5-3 · SHOP-FR-28 · AD-S12）。
 *
 * <p>⚠️ <b>这是一条弱护栏，它证明不了结果对，只证明「没人把老写法抄回来」。</b>
 * 口径正确性的唯一实证是 L1 的真库计数（A/B/C/D 四类规格，见
 * {@code Epic8ChainIntegrationTest}）—— {@code COALESCE} 与 LEFT JOIN 在 NULL 上的行为
 * 不能靠 mock 复现。<b>两条都要，别用一条顶替另一条。</b>
 *
 * <p>🎯 <b>变异靶子</b>：把 {@code is_active = true} 加回去 → {@link #doesNotFilterByListingStatus()}
 * 必须变红；把 {@code LEFT JOIN} 改回 {@code JOIN} → {@link #usesLeftJoinFromSkus()} 必须变红。
 *
 * <p>纯字符串断言，不起 Spring 上下文、不连 DB。
 */
class ShopFinanceDashboardSqlTest {

    private static final String SQL =
            ShopFinanceDashboardService.OUT_OF_STOCK_SKU_COUNT_SQL.toLowerCase(Locale.ROOT);

    @Test
    @DisplayName("🎯 不再按上架状态过滤 —— 未上架商品的规格断货，运营照样要知道")
    void doesNotFilterByListingStatus() {
        // shop_products.is_active 建列时 DEFAULT FALSE，所以「新建但未上架」的商品
        // 其规格会被旧写法全部排除 —— 上架当天才发现是空的。
        assertThat(SQL)
                .as("🎯 把 is_active = true 加回去，这条必须红")
                .doesNotContain("is_active");
        // 口径里已无商品维度，也就不该再 JOIN 商品表。
        assertThat(SQL).doesNotContain("shop_products");
    }

    @Test
    @DisplayName("🎯 以 shop_skus 为驱动表 + LEFT JOIN —— 覆盖「从未建过库存行」的规格")
    void usesLeftJoinFromSkus() {
        assertThat(SQL)
                .as("🎯 改回 JOIN，这条必须红：内连接会把没有库存行的规格整条漏掉，"
                        + "而「从来没进过货」恰恰是最极端的售罄")
                .contains("left join sku_inventory");
        assertThat(SQL)
                .as("方向不能反：FROM sku_inventory LEFT JOIN shop_skus 等于说「库存行可以没有 SKU」，照样漏")
                .contains("from shop_skus");
        // 驱动表必须排在 LEFT JOIN 之前。
        assertThat(SQL.indexOf("from shop_skus"))
                .isLessThan(SQL.indexOf("left join sku_inventory"));
    }

    @Test
    @DisplayName("🔴 两个 COALESCE 都在 —— 少一个这次修改就等于什么都没做")
    void bothCoalescesArePresent() {
        // LEFT JOIN 无匹配时 actual 与 locked **都是 NULL**，而 NULL - NULL <= 0
        // 在 SQL 里求值为 NULL（不是 true），整行会被 WHERE 过滤掉。
        // 这是本 story 最容易写错的一行。
        assertThat(SQL).contains("coalesce(i.actual, 0)");
        assertThat(SQL).contains("coalesce(i.locked, 0)");
        assertThat(SQL).doesNotContain("i.actual - i.locked")
                .as("裸减法会让「没有库存行」的规格落回 NULL，一条都统计不到");
    }

    @Test
    @DisplayName("判据是「可售 ≤ 0」，不是 「= 0」")
    void treatsNegativeAvailableAsOutOfStock() {
        // actual - locked 为负理论上被库级约束挡着，但读数侧按 <= 防御 ——
        // 真出现负数时它同样是「卖不了」，漏掉才是错的。
        assertThat(SQL).contains("<= 0");
    }

    @Test
    @DisplayName("🔴 没有顺手加料 —— 口径是 SHOP-FR-28 白纸黑字定的")
    void noExtraFiltersSneakedIn() {
        // 加「只数有价格的」「只数 30 天内有销量的」这类过滤，就是又造一个对不上的数。
        assertThat(SQL)
                .doesNotContain("cost_price")
                .doesNotContain("shop_order_lines")
                .doesNotContain("created_at")
                .doesNotContain("limit");
    }
}
