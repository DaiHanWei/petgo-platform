package com.tailtopia.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.shop.dto.ShopProductForm;
import com.tailtopia.admin.shop.dto.ShopSkuForm;
import com.tailtopia.admin.shop.service.AdminShopListingService;
import com.tailtopia.admin.shop.service.AdminShopProductService;
import com.tailtopia.shop.domain.AgeStage;
import com.tailtopia.shop.domain.BodySize;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.domain.Species;
import com.tailtopia.shop.service.InventoryMovementService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：Epic 1 全链路联调（Story 1.8）。
 *
 * <p>🔴 <b>本类存在的理由：前七条 story 各自绿灯 ≠ Epic 1 是个可验收的交付。</b>
 * 它走的是运营真实会走的那条路——<b>后台建商品 → 配 SKU 与价格 → 登记采购入库 → 上架 → App 可见</b>，
 * 全部经真实 service，不用 JDBC 抄近路（抄近路就测不到各段之间的接缝，而接缝正是本类要看的东西）。
 *
 * <p>覆盖三段反向链路：库存改 0 → App 显示售罄；下架 → App 查不到；两者互不影响。
 *
 * <p>⚠️ 需真实 PostgreSQL + Redis。App 侧的模拟器走查属 L2，不在本类范围。
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=200")
class Epic1ChainIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminShopProductService products;
    @Autowired
    private AdminShopListingService listing;
    @Autowired
    private InventoryMovementService inventory;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    /**
     * 上限是全局计数，共享测试库里已有几百个在售 SKU。本类关注的是<b>链路是否通</b>，
     * 不是上限语义（那由 Story 1.5 的用例覆盖），故抬高上限并清零在售态，让链路断言不受污染。
     */
    @BeforeEach
    void resetListingState() {
        jdbc.update("UPDATE shop_products SET is_active = false");
    }

    private ShopProductForm productForm(String name) {
        ShopProductForm f = new ShopProductForm();
        f.setName(name);
        f.setBrand("Royal Canin");
        f.setCategory(ProductCategory.MAKANAN);
        f.setMainImageKey("shop/" + name + "/main.jpg");
        f.setSpecies(Species.DOG);
        f.setBodySize(BodySize.MEDIUM);
        f.setAgeStage(AgeStage.ADULT);
        f.setDetailHtml("<p>komposisi</p>");
        f.setShelfLifeNote("18 bulan");
        f.setReturnPolicy(ReturnPolicy.NO_RETURN_AFTER_OPEN);
        f.setSortWeight(50);
        // Makanan 必须有喂量结构（FR-109 的命脉）
        f.getFeedWeightMinKg().add(5);
        f.getFeedWeightMaxKg().add(10);
        f.getFeedGramsPerDay().add(110);
        return f;
    }

    private ShopSkuForm skuForm(String spec, long price) {
        ShopSkuForm f = new ShopSkuForm();
        f.setSpecName(spec);
        f.setPrice(price);
        f.setNetWeightG(3000L);
        return f;
    }

    private String publicList() throws Exception {
        return mvc.perform(get("/api/v1/shop/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ---------- 正向：后台录入 → 上架 → App 可见 ----------

    @Test
    @DisplayName("🔗 全链路：后台建商品 → 配 SKU → 采购入库 → 上架 → App 列表与详情可见且数据一致")
    void fullChainFromAdminEntryToAppVisibility() throws Exception {
        // ① 后台建商品（默认未上架）
        ShopProduct p = products.create(productForm("chain" + SEQ.incrementAndGet()), ACTOR);
        assertThat(p.isActive()).as("新建商品默认未上架").isFalse();

        // 未上架 → App 查不到
        assertThat(publicList()).doesNotContain(p.getPublicToken());
        mvc.perform(get("/api/v1/shop/products/" + p.getPublicToken()))
                .andExpect(status().isNotFound());

        // ② 配 SKU 与价格
        ShopSku sku = products.upsertSku(p.getId(), skuForm("3 kg", 285_000L), ACTOR);

        // ③ 登记采购入库（此时才有可售库存）
        inventory.receivePurchase(sku.getId(), 12L, "PO-CHAIN", "供应商A", 190_000L,
                LocalDate.now(), ACTOR);

        // ④ 上架
        listing.list(p.getId(), ACTOR);

        // ⑤ App 列表可见
        assertThat(publicList()).contains(p.getPublicToken());

        // ⑥ App 详情数据与后台录入一致
        String detail = mvc.perform(get("/api/v1/shop/products/" + p.getPublicToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(detail)
                .contains("\"brand\":\"Royal Canin\"")
                .contains("\"specName\":\"3 kg\"")
                .contains("\"price\":285000")
                .contains("\"shelfLifeNote\":\"18 bulan\"")
                .contains("\"returnPolicy\":\"NO_RETURN_AFTER_OPEN\"")
                .contains("\"stockStatus\":\"IN_STOCK\"");

        // 🔒 进货价绝不出现在对外接口（NFR-11）——整条链路上最容易泄的一处
        assertThat(detail).doesNotContain("190000").doesNotContain("costPrice");
    }

    // ---------- 反向一：库存归零 → 售罄 ----------

    @Test
    @DisplayName("🔗 后台把库存改到 0 → App 详情显示 OUT_OF_STOCK，但商品仍可见（售罄不下架）")
    void stockToZeroShowsOutOfStockWithoutDelisting() throws Exception {
        ShopProduct p = products.create(productForm("zero" + SEQ.incrementAndGet()), ACTOR);
        ShopSku sku = products.upsertSku(p.getId(), skuForm("3 kg", 285_000L), ACTOR);
        inventory.receivePurchase(sku.getId(), 5L, "PO-Z", "A", 100_000L, LocalDate.now(), ACTOR);
        listing.list(p.getId(), ACTOR);

        // 走真实的盘点入口把库存归零（不是 UPDATE 抄近路）
        inventory.stocktake(sku.getId(), 0L, "全部损毁", ACTOR);

        String detail = mvc.perform(get("/api/v1/shop/products/" + p.getPublicToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("\"stockStatus\":\"OUT_OF_STOCK\"");

        // 🔴 售罄不下架（Story 1.2 口径）：商品仍在列表里，保留复购提醒与外部落点
        assertThat(publicList()).contains(p.getPublicToken());
    }

    @Test
    @DisplayName("🔗 库存降到低位 → App 详情返回 LOW_STOCK 且 remaining 是真实剩余数（FR-95）")
    void lowStockExposesRealRemaining() throws Exception {
        ShopProduct p = products.create(productForm("low" + SEQ.incrementAndGet()), ACTOR);
        ShopSku sku = products.upsertSku(p.getId(), skuForm("3 kg", 285_000L), ACTOR);
        inventory.receivePurchase(sku.getId(), 4L, "PO-L", "A", 100_000L, LocalDate.now(), ACTOR);
        listing.list(p.getId(), ACTOR);

        String detail = mvc.perform(get("/api/v1/shop/products/" + p.getPublicToken()))
                .andReturn().getResponse().getContentAsString();

        assertThat(detail).contains("\"stockStatus\":\"LOW_STOCK\"");
        // App 的 `Sisa {n}` 直接取这个数 —— 后端给错，前端再守也没用
        assertThat(detail).contains("\"remaining\":4");
    }

    // ---------- 反向二：下架 → 不可见 ----------

    @Test
    @DisplayName("🔗 后台下架 → App 列表与详情都查不到，且库存一个数都没动（SPEC-7 口径）")
    void delistHidesFromAppWithoutTouchingInventory() throws Exception {
        ShopProduct p = products.create(productForm("delist" + SEQ.incrementAndGet()), ACTOR);
        ShopSku sku = products.upsertSku(p.getId(), skuForm("3 kg", 285_000L), ACTOR);
        inventory.receivePurchase(sku.getId(), 9L, "PO-D", "A", 100_000L, LocalDate.now(), ACTOR);
        listing.list(p.getId(), ACTOR);
        assertThat(publicList()).contains(p.getPublicToken());

        listing.delist(p.getId(), ACTOR);

        assertThat(publicList()).doesNotContain(p.getPublicToken());
        mvc.perform(get("/api/v1/shop/products/" + p.getPublicToken()))
                .andExpect(status().isNotFound());

        // 下架只改可见性：库存原封不动
        Long actual = jdbc.queryForObject(
                "SELECT actual FROM sku_inventory WHERE sku_id = ?", Long.class, sku.getId());
        assertThat(actual).isEqualTo(9L);
    }

    // ---------- 游客态贯穿全链路 ----------

    @Test
    @DisplayName("🔒 上述全部对外读取均以游客身份完成 —— 全链路无一处要求登录（FR-93A）")
    void entireReadPathIsGuestAccessible() throws Exception {
        ShopProduct p = products.create(productForm("guest" + SEQ.incrementAndGet()), ACTOR);
        ShopSku sku = products.upsertSku(p.getId(), skuForm("3 kg", 285_000L), ACTOR);
        inventory.receivePurchase(sku.getId(), 3L, "PO-G", "A", 100_000L, LocalDate.now(), ACTOR);
        listing.list(p.getId(), ACTOR);

        // 本类所有 mvc.perform 都不带任何 authentication —— 能 200 即证明游客可达
        mvc.perform(get("/api/v1/shop/products")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/shop/products?category=MAKANAN")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/shop/products/" + p.getPublicToken())).andExpect(status().isOk());
    }
}
