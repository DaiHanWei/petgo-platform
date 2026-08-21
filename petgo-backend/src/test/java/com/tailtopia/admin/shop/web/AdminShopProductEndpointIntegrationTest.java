package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MvcResult;

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
    @Autowired
    private AdminAccountRepository adminAccounts;

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

    // =======================================================================
    // 🔴 Story 1.3 遗留的 L1（2026-08-18 补）
    //
    // 这条 story 当年在自己的 Completion Notes 里写「actor 构造在本地接入时按
    // AdminPagesRenderSmokeTest 范式补齐」，于是 AC1 / AC3 / AC4 / AC5 的 [L1] 全部空着。
    // Story 1.4 补自己的 L1 时已经发现**基建一直是齐的**（AdminUserDetails 六参构造器 +
    // TestingAuthenticationToken），并把这句判断写进了 AdminShopInventoryEndpointIntegrationTest：
    // 「只是没做，不是做不了。」—— 本节把 1.3 欠的这几条补上。
    //
    // 🔒 为什么非补不可：AC4 的进货价门控是**字段级**的（同一个页面对不同权限下发不同字段），
    //    L0 只能验 service 不接收进货价；「控制器有没有真的在响应体里把它拿掉」
    //    只有走完整安全过滤链的 L1 才看得见。
    // =======================================================================

    /**
     * 造一个带指定权限码的 STAFF 登录态（照 {@code AdminShopInventoryEndpointIntegrationTest} 范式）。
     *
     * <p>⚠️ 实体存的是 SUPER_ADMIN，但 principal 显式声明为 {@link AdminAccountType#STAFF} ——
     * 否则 {@code hasRole('SUPER_ADMIN')} 隐式全权会让**所有权限断言变成恒真**（假绿）。
     */
    private Authentication staffWith(String... permissionCodes) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "prod-" + n + "@tailtopia.test", "商品测试账号", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF, Set.of(permissionCodes));
        return new TestingAuthenticationToken(principal, null,
                new ArrayList<>(principal.getAuthorities()));
    }

    /** 一份 FR-94 必填项齐全的表单参数（调用方可覆盖/删除某项来构造违规用例）。 */
    private static org.springframework.util.MultiValueMap<String, String> validForm(String name) {
        var f = new org.springframework.util.LinkedMultiValueMap<String, String>();
        f.add("name", name);
        f.add("brand", "Royal Canin");
        f.add("category", "MAKANAN");
        f.add("mainImageKey", "shop/p/" + name + ".jpg");
        f.add("species", "DOG");
        f.add("detailHtml", "<p>成犬全价粮</p>");
        f.add("shelfLifeNote", "开封后 30 天内食用完毕");
        f.add("returnPolicy", "NO_RETURN_AFTER_OPEN");
        f.add("sortWeight", "10");
        // 体重区间行编辑器（AC2：结构化，非自由文本）
        f.addAll("feedWeightMinKg", java.util.List.of("1", "10"));
        f.addAll("feedWeightMaxKg", java.util.List.of("10", "25"));
        f.addAll("feedGramsPerDay", java.util.List.of("120", "260"));
        return f;
    }

    /** 走真实端点建一个商品，返回 product id（顺带断言它确实建成了）。 */
    private long createProductViaEndpoint(Authentication editor, String name) throws Exception {
        MvcResult r = mvc.perform(post("/admin/shop/products")
                        .with(authentication(editor)).with(csrf()).params(validForm(name)))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String url = r.getResponse().getRedirectedUrl();
        assertThat(url).as("建商品失败会重定向回 /new —— 这里必须落到详情页").isNotNull();
        assertThat(url).doesNotEndWith("/new");
        return Long.parseLong(url.substring(url.lastIndexOf('/') + 1));
    }

    // ---------- AC1 [L1]：端到端建商品 ----------

    @Test
    @DisplayName("AC1 [L1] 有 shop.product_edit → 表单一路到库，FR-94 必填项逐个落对了列")
    void createProductEndToEnd() throws Exception {
        String name = "L1 测试粮 " + SEQ.incrementAndGet();
        long id = createProductViaEndpoint(staffWith(AdminPermissions.SHOP_PRODUCT_EDIT), name);

        var row = jdbc.queryForMap("""
                SELECT name, brand, category, main_image_key, species, detail_html,
                       shelf_life_note, return_policy, sort_weight, public_token, is_active
                  FROM shop_products WHERE id = ?
                """, id);
        assertThat(row.get("name")).isEqualTo(name);
        assertThat(row.get("brand")).isEqualTo("Royal Canin");
        assertThat(row.get("category")).isEqualTo("MAKANAN");
        assertThat(row.get("species")).isEqualTo("DOG");
        assertThat(row.get("shelf_life_note")).isEqualTo("开封后 30 天内食用完毕");
        assertThat(row.get("return_policy")).isEqualTo("NO_RETURN_AFTER_OPEN");
        assertThat((String) row.get("public_token"))
                .as("🔴 对外标识必须是不可枚举 token，不得外露自增 id")
                .isNotBlank().isNotEqualTo(String.valueOf(id));

        // AC2：喂量结构化落库（FR-109 的唯一计算依据，落成自由文本即整条复购机制失效）
        String guide = jdbc.queryForObject(
                "SELECT feeding_guide::text FROM shop_products WHERE id = ?", String.class, id);
        assertThat(guide).contains("120").contains("260").contains("25");
    }

    @Test
    @DisplayName("🔴 AC1 [L1] 缺必填项 → 回表单页 + error flash，绝不吐 RFC 9457 裸 JSON，且一行都不落库")
    void createProductWithMissingRequiredFieldRedirectsWithError() throws Exception {
        Integer before = jdbc.queryForObject("SELECT count(*) FROM shop_products", Integer.class);

        var bad = validForm("缺保质期说明 " + SEQ.incrementAndGet());
        bad.remove("shelfLifeNote");

        mvc.perform(post("/admin/shop/products")
                        .with(authentication(staffWith(AdminPermissions.SHOP_PRODUCT_EDIT)))
                        .with(csrf()).params(bad))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/shop/products/new"))
                .andExpect(flash().attributeExists("error"));

        Integer after = jdbc.queryForObject("SELECT count(*) FROM shop_products", Integer.class);
        assertThat(after).as("校验失败还落了库 = 脏数据").isEqualTo(before);
    }

    @Test
    @DisplayName("🔒 AC1 [L1] 已登录但只有 shop.product_view → 建商品 403（403 而不是 302，是不同分支）")
    void createProductWithoutEditPermissionIsForbidden() throws Exception {
        mvc.perform(post("/admin/shop/products")
                        .with(authentication(staffWith(AdminPermissions.SHOP_PRODUCT_VIEW)))
                        .with(csrf()).params(validForm("越权建商品 " + SEQ.incrementAndGet())))
                .andExpect(status().isForbidden());
    }

    // ---------- AC3 [L1]：建 SKU 后库存行存在且为 0 ----------

    @Test
    @DisplayName("AC3 [L1] 新建 SKU → 自动建库存行且 actual=0（缺这行会让下单锁定静默判成售罄）")
    void newSkuAutoCreatesZeroInventoryRow() throws Exception {
        Authentication editor = staffWith(AdminPermissions.SHOP_PRODUCT_EDIT);
        long productId = createProductViaEndpoint(editor, "带规格的粮 " + SEQ.incrementAndGet());

        mvc.perform(post("/admin/shop/products/" + productId + "/skus")
                        .with(authentication(editor)).with(csrf())
                        .param("specName", "3 kg")
                        .param("price", "285000")
                        .param("netWeightG", "3000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/shop/products/" + productId))
                .andExpect(flash().attributeExists("notice"));

        var inv = jdbc.queryForMap("""
                SELECT i.actual, i.locked
                  FROM sku_inventory i JOIN shop_skus k ON k.id = i.sku_id
                 WHERE k.product_id = ?
                """, productId);
        assertThat(((Number) inv.get("actual")).longValue())
                .as("🔴 新 SKU 的库存行必须存在且为 0 —— 无行时 1.2 的 lock 影响 0 行 = 静默售罄")
                .isZero();
        assertThat(((Number) inv.get("locked")).longValue()).isZero();

        // 价格以最小币种单位整型存储（NFR-9：IDR 无小数，禁 DECIMAL/double）
        Long price = jdbc.queryForObject(
                "SELECT price FROM shop_skus WHERE product_id = ?", Long.class, productId);
        assertThat(price).isEqualTo(285_000L);
    }

    @Test
    @DisplayName("🔴 浏览器形态（空的 id 隐藏域）也必须是【新建】而不是更新 —— 两次提交出两个规格")
    void emptyHiddenIdStillCreatesInsteadOfUpdating() throws Exception {
        Authentication editor = staffWith(AdminPermissions.SHOP_PRODUCT_EDIT);
        long productId = createProductViaEndpoint(editor, "两个规格 " + SEQ.incrementAndGet());

        // 模板里是 <input type="hidden" name="id"/> —— 浏览器提交的是 id=""（空串，不是缺席）
        for (String spec : new String[] {"3 kg", "10 kg"}) {
            mvc.perform(post("/admin/shop/products/" + productId + "/skus")
                            .with(authentication(editor)).with(csrf())
                            .param("id", "")
                            .param("specName", spec).param("price", "285000")
                            .param("netWeightG", "3000"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attributeExists("notice"));
        }

        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM shop_skus WHERE product_id = ?", Integer.class, productId);
        assertThat(n).as("🔴 第二次提交把第一个规格改掉了，而不是新建第二个").isEqualTo(2);
    }

    // ---------- AC4 [L1]：无权限账号的响应体里根本没有进货价 ----------

    /** 建商品 + 一个 SKU，并把进货价设成一个**在页面上一眼认得出**的值，返回 productId。 */
    private long seedProductWithCost(long costPrice) throws Exception {
        Authentication superEditor = staffWith(AdminPermissions.SHOP_PRODUCT_EDIT,
                AdminPermissions.SHOP_COST_EDIT);
        long productId = createProductViaEndpoint(superEditor, "带进货价 " + SEQ.incrementAndGet());
        mvc.perform(post("/admin/shop/products/" + productId + "/skus")
                        .with(authentication(superEditor)).with(csrf())
                        .param("specName", "3 kg").param("price", "285000")
                        .param("netWeightG", "3000")
                        .param("costPrice", String.valueOf(costPrice)))
                .andExpect(status().is3xxRedirection());
        return productId;
    }

    @Test
    @DisplayName("🔒 AC4 [L1] 无 shop.cost_view → 进货价【在服务端就不下发】，不是模板 th:if 隐藏")
    void costPriceAbsentFromResponseWithoutPermission() throws Exception {
        long cost = 190_777L;                 // 刻意选一个不会被别的数字撞上的值
        long productId = seedProductWithCost(cost);

        MvcResult noCost = mvc.perform(get("/admin/shop/products/" + productId)
                        .with(authentication(staffWith(AdminPermissions.SHOP_PRODUCT_VIEW))))
                .andExpect(status().isOk())
                .andReturn();

        // 🔴 两层都要断言：model 里没这个 key（结构性门控）+ 响应体里没这个数（真的没下发）
        assertThat(noCost.getModelAndView().getModel())
                .as("🔒 costBySku 根本不该进 model —— 进了 model 再靠 th:if 隐藏可被查看源码绕过")
                .doesNotContainKey("costBySku");
        assertThat(noCost.getResponse().getContentAsString())
                .as("🔒 进货价数值出现在 HTML 里 = 已泄露（NFR-11）")
                .doesNotContain(String.valueOf(cost));

        // 反向对照：有权限时它必须真的在 —— 否则上面那条 doesNotContain 是恒真的废断言
        MvcResult withCost = mvc.perform(get("/admin/shop/products/" + productId)
                        .with(authentication(staffWith(AdminPermissions.SHOP_PRODUCT_VIEW,
                                AdminPermissions.SHOP_COST_VIEW))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(withCost.getModelAndView().getModel()).containsKey("costBySku");
        assertThat(withCost.getResponse().getContentAsString()).contains(String.valueOf(cost));
    }

    @Test
    @DisplayName("🔒 AC4 [L1] 有 product_edit 但无 cost_edit → 表单里提交的进货价被服务端丢弃")
    void submittedCostPriceIsDiscardedWithoutCostEdit() throws Exception {
        Authentication editorNoCost = staffWith(AdminPermissions.SHOP_PRODUCT_EDIT);
        long productId = createProductViaEndpoint(editorNoCost, "越权改价 " + SEQ.incrementAndGet());

        mvc.perform(post("/admin/shop/products/" + productId + "/skus")
                        .with(authentication(editorNoCost)).with(csrf())
                        .param("specName", "3 kg").param("price", "285000")
                        .param("netWeightG", "3000")
                        .param("costPrice", "123456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("notice"));   // SKU 本身照存

        Long cost = jdbc.queryForObject(
                "SELECT cost_price FROM shop_skus WHERE product_id = ?", Long.class, productId);
        assertThat(cost).as("🔒 无 shop.cost_edit 却把进货价写进去了 = 权限位形同虚设").isNull();
    }

    // ---------- AC5 [L1]：审计真的落库，且不含进货价数值 ----------

    @Test
    @DisplayName("🔒 AC5 [L1] 建商品/建 SKU/改进货价各落一条审计，且详情里【没有】进货价数值")
    void auditRowsPersistedWithoutCostValue() throws Exception {
        long cost = 178_999L;
        long productId = seedProductWithCost(cost);
        String token = jdbc.queryForObject(
                "SELECT public_token FROM shop_products WHERE id = ?", String.class, productId);

        Integer created = jdbc.queryForObject("""
                SELECT count(*) FROM admin_audit_logs
                 WHERE action_type = 'SHOP_PRODUCT_CREATED' AND target_id = ?
                """, Integer.class, token);
        assertThat(created).as("SHOP_PRODUCT_CREATED 应已落库").isEqualTo(1);

        for (String action : new String[] {"SHOP_SKU_UPSERTED", "SHOP_PRODUCT_COST_UPDATED"}) {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM admin_audit_logs WHERE action_type = ?",
                    Integer.class, action);
            assertThat(n).as(action + " 应已落库").isPositive();
        }

        // 🔴 审计日志页的可见范围与 shop.cost_view 不同 —— 数值写进 summary 等于绕过权限位
        Integer leaked = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_logs WHERE summary LIKE ?",
                Integer.class, "%" + cost + "%");
        assertThat(leaked).as("审计详情不得出现进货价数值").isZero();
    }
}
