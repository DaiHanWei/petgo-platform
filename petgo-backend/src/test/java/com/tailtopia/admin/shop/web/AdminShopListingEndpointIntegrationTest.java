package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shop.service.AdminShopListingService;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.TestPropertySource;

/**
 * L1 集成：上下架、精选排序与 SKU 上限（Story 1.5，AB-10D）。
 *
 * <p>🔒 最要紧的一条是 <b>AC2 / SPEC-7</b>：下架有 {@code locked > 0} 的商品，
 * {@code actual} 与 {@code locked} <b>一个数都不许动</b>。
 *
 * <p>⚠️ 需真实 PostgreSQL + Redis。
 *
 * <p>🔴 <b>本类独占一个低上限（3）并在每例前把所有商品置为未上架。</b>
 * 原因：SKU 上限是<b>全局</b>计数，而集成测试共享同一个库——其他 story 的用例会往里塞几百个在售
 * SKU，用默认上限 30 时本类的每一条都会被「超过上限」挡住，且失败信息与被测逻辑毫无关系。
 * 精确的边界语义（29/30/31）已由 {@code AdminShopListingServiceTest} 用 mock 在 L0 覆盖；
 * 本类只需证明<b>机制</b>在真库上端到端成立，不需要复刻数字 30。
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=3")
class AdminShopListingEndpointIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminShopListingService listing;
    @Autowired
    private SkuInventoryRepository inventory;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AdminAccountRepository adminAccounts;

    private static final long ACTOR = 1L;

    /**
     * 🔴 每例前把全库商品置为未上架，让在售 SKU 计数从 0 起算。
     * 集成测试共享同一个库，其他 story 的用例会留下几百个在售 SKU；不清零则本类的上限断言无意义。
     * 安全性：各测试类都自己 seed 自己的数据并只断言自己的 token，surefire 亦不并行跑测试类。
     */
    @BeforeEach
    void resetListingState() {
        jdbc.update("UPDATE shop_products SET is_active = false");
    }

    private Authentication staffWith(String... permissionCodes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "lst-" + n + "@tailtopia.test", "上下架测试账号", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF, Set.of(permissionCodes));
        return new TestingAuthenticationToken(principal, null,
                new ArrayList<>(principal.getAuthorities()));
    }

    /** 造商品（默认未上架）+ 一个 SKU + 指定库存，返回 {productId, skuId}。 */
    private long[] seedProduct(int sortWeight, long actual, long locked) {
        String pToken = "ls" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active, sort_weight)
                VALUES (?, ?, 'y', 'MAKANAN', 'k', 'DOG', '<p/>', 'n',
                        'NO_RETURN_AFTER_OPEN', false, ?)
                """, pToken, "商品" + pToken, sortWeight);
        Long productId = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "lk" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', 285000)
                """, sToken, productId);
        Long skuId = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, ?)",
                skuId, actual, locked);
        return new long[] {productId, skuId};
    }

    private String publicToken(long productId) {
        return jdbc.queryForObject(
                "SELECT public_token FROM shop_products WHERE id = ?", String.class, productId);
    }

    // ---------- 🔒 AC2 / SPEC-7：下架只改可见性 ----------

    @Test
    @DisplayName("🔒 下架有 locked>0 的商品：成功，且 actual/locked 一个数都不动")
    void delistNeverTouchesLockedInventory() {
        long[] ids = seedProduct(0, 10, 7);
        listing.list(ids[0], ACTOR);

        listing.delist(ids[0], ACTOR);

        var row = inventory.findBySkuId(ids[1]).orElseThrow();
        assertThat(row.getActual()).as("下架不得改 actual").isEqualTo(10L);
        assertThat(row.getLocked()).as("下架不得改 locked——那是已卖给用户的货").isEqualTo(7L);
        assertThat(row.available()).isEqualTo(3L);
    }

    @Test
    @DisplayName("🔒 下架不产生任何库存流水（不得触发入库/报损/盘点）")
    void delistProducesNoInventoryMovement() {
        long[] ids = seedProduct(0, 10, 7);
        listing.list(ids[0], ACTOR);
        Integer before = jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movements WHERE sku_id = ?", Integer.class, ids[1]);

        listing.delist(ids[0], ACTOR);

        Integer after = jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movements WHERE sku_id = ?", Integer.class, ids[1]);
        assertThat(after).isEqualTo(before);
    }

    // ---------- AC1：对外可见性 ----------

    @Test
    @DisplayName("上架 → 对外列表与详情可见；下架 → 两者都查不到")
    void listingControlsPublicVisibility() throws Exception {
        long[] ids = seedProduct(0, 5, 0);
        String token = publicToken(ids[0]);

        // 未上架：详情 404、列表不含
        mvc.perform(get("/api/v1/shop/products/" + token)).andExpect(status().isNotFound());

        listing.list(ids[0], ACTOR);
        mvc.perform(get("/api/v1/shop/products/" + token)).andExpect(status().isOk());
        assertThat(mvc.perform(get("/api/v1/shop/products")).andReturn()
                .getResponse().getContentAsString()).contains(token);

        listing.delist(ids[0], ACTOR);
        mvc.perform(get("/api/v1/shop/products/" + token)).andExpect(status().isNotFound());
        assertThat(mvc.perform(get("/api/v1/shop/products")).andReturn()
                .getResponse().getContentAsString()).doesNotContain(token);
    }

    // ---------- AC3：权重影响对外顺序（写入口由 Story 1.3 编辑表单提供） ----------

    @Test
    @DisplayName("AC3：排序权重决定对外列表顺序（权重大的在前）")
    void sortWeightDrivesPublicOrder() throws Exception {
        long[] low = seedProduct(1, 5, 0);
        long[] high = seedProduct(99, 5, 0);
        listing.list(low[0], ACTOR);
        listing.list(high[0], ACTOR);

        String body = mvc.perform(get("/api/v1/shop/products"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body.indexOf(publicToken(high[0])))
                .as("权重 99 应排在权重 1 之前")
                .isLessThan(body.indexOf(publicToken(low[0])));
    }

    // ---------- AC5：权限 ----------

    @Test
    @DisplayName("🔒 已登录但无 shop.product_edit → 上架端点 403")
    void listingRequiresProductEdit() throws Exception {
        long[] ids = seedProduct(0, 5, 0);

        mvc.perform(post("/admin/shop/products/" + ids[0] + "/list")
                        .with(authentication(staffWith(AdminPermissions.SHOP_PRODUCT_VIEW)))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT is_active FROM shop_products WHERE id = ?",
                Boolean.class, ids[0])).isFalse();
    }

    @Test
    @DisplayName("有 shop.product_edit → 上架成功，302 + notice flash")
    void listingSucceedsWithProductEdit() throws Exception {
        long[] ids = seedProduct(0, 5, 0);

        mvc.perform(post("/admin/shop/products/" + ids[0] + "/list")
                        .with(authentication(staffWith(AdminPermissions.SHOP_PRODUCT_EDIT)))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("notice"));

        assertThat(jdbc.queryForObject("SELECT is_active FROM shop_products WHERE id = ?",
                Boolean.class, ids[0])).isTrue();
    }

    @Test
    @DisplayName("🔴 上架失败（超上限）→ 302 回列表页 + error flash，不给运营裸 JSON")
    void listingFailureRedirectsWithFlashNotJson() throws Exception {
        // 把上限占满：造 cap 个已上架 SKU
        int cap = listing.skuCap();
        long already = listing.activeSkuCount();
        for (long i = already; i < cap; i++) {
            long[] filler = seedProduct(0, 1, 0);
            listing.list(filler[0], ACTOR);
        }
        assertThat(listing.activeSkuCount()).isEqualTo(cap);

        long[] overflow = seedProduct(0, 1, 0);
        mvc.perform(post("/admin/shop/products/" + overflow[0] + "/list")
                        .with(authentication(staffWith(AdminPermissions.SHOP_PRODUCT_EDIT)))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));

        assertThat(jdbc.queryForObject("SELECT is_active FROM shop_products WHERE id = ?",
                Boolean.class, overflow[0])).as("超限时一件也不该上架").isFalse();
    }

    @Test
    @DisplayName("🔴 已超上限时下架仍可用（不得出现「超限了反而下架不掉」）")
    void delistWorksEvenWhenOverCap() {
        int cap = listing.skuCap();
        long already = listing.activeSkuCount();
        long[] victim = null;
        for (long i = already; i < cap; i++) {
            victim = seedProduct(0, 1, 0);
            listing.list(victim[0], ACTOR);
        }
        assertThat(listing.atOrOverCap()).isTrue();
        assertThat(victim).isNotNull();

        listing.delist(victim[0], ACTOR);

        assertThat(jdbc.queryForObject("SELECT is_active FROM shop_products WHERE id = ?",
                Boolean.class, victim[0])).isFalse();
    }
}
