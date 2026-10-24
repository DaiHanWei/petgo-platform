package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.shop.dto.ShopProductForm;
import com.tailtopia.admin.shop.dto.ShopSkuForm;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.FeedingGuideEntry;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.Species;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0：商品后台维护的校验与喂量结构（Story 1.3 AC1/AC2/AC3）。 */
class AdminShopProductServiceTest {

    private AdminShopProductService service;

    @BeforeEach
    void setUp() {
        service = new AdminShopProductService(
                Mockito.mock(ShopProductRepository.class),
                Mockito.mock(ShopSkuRepository.class),
                Mockito.mock(InventoryService.class),
                new ShopTokenGenerator(),
                Mockito.mock(AdminAuditService.class));
    }

    private ShopProductForm validForm() {
        ShopProductForm f = new ShopProductForm();
        f.setName("Royal Canin Medium Adult");
        f.setBrand("Royal Canin");
        f.setCategory(ProductCategory.MAKANAN);
        f.setMainImageKey("shop/p/1/main.jpg");
        f.setSpecies(Species.DOG);
        f.setDetailHtml("<p>成分表</p>");
        f.setShelfLifeNote("18 bulan");
        f.setReturnPolicy(ReturnPolicy.NO_RETURN_AFTER_OPEN);
        return f;
    }

    // ---------- AC1 必填校验 ----------

    @Test
    @DisplayName("必填项缺任一 → 校验失败，且错误信息指出是哪一项")
    void requiredFieldsAreValidated() {
        record Case(String label, java.util.function.Consumer<ShopProductForm> breaker) { }
        List<Case> cases = List.of(
                new Case("商品名称", f -> f.setName("  ")),
                new Case("品牌", f -> f.setBrand(null)),
                new Case("品类", f -> f.setCategory(null)),
                new Case("主图", f -> f.setMainImageKey("")),
                new Case("适用物种", f -> f.setSpecies(null)),
                new Case("商品详情", f -> f.setDetailHtml(null)),
                new Case("保质期说明", f -> f.setShelfLifeNote("")),
                new Case("退货规则", f -> f.setReturnPolicy(null)));
        for (Case c : cases) {
            ShopProductForm f = validForm();
            c.breaker().accept(f);
            assertThatThrownBy(() -> service.validate(f))
                    .as("缺 %s 时应报错", c.label())
                    .isInstanceOf(AppException.class);
        }
    }

    @Test
    @DisplayName("商品名超 60 字符 → 拒绝")
    void nameLengthCapped() {
        ShopProductForm f = validForm();
        f.setName("x".repeat(61));
        assertThatThrownBy(() -> service.validate(f)).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("图集超 8 张 → 拒绝")
    void galleryCapped() {
        ShopProductForm f = validForm();
        f.setGalleryKeysRaw(String.join("\n", java.util.Collections.nCopies(9, "k.jpg")));
        assertThatThrownBy(() -> service.validate(f)).isInstanceOf(AppException.class);
    }

    // ---------- AC2 喂量结构 ----------

    @Test
    @DisplayName("喂量：正常 3 段区间通过")
    void feedingGuideValid() {
        assertThatCode(() -> service.validateFeedingGuide(List.of(
                new FeedingGuideEntry(1, 5, 60),
                new FeedingGuideEntry(5, 10, 110),
                new FeedingGuideEntry(10, 25, 210)))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("喂量：非正数 → 拒绝")
    void feedingGuideRejectsNonPositive() {
        assertThatThrownBy(() -> service.validateFeedingGuide(
                List.of(new FeedingGuideEntry(0, 5, 60)))).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.validateFeedingGuide(
                List.of(new FeedingGuideEntry(1, 5, 0)))).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("喂量：下限 >= 上限 → 拒绝")
    void feedingGuideRejectsInvertedRange() {
        assertThatThrownBy(() -> service.validateFeedingGuide(
                List.of(new FeedingGuideEntry(10, 5, 60)))).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.validateFeedingGuide(
                List.of(new FeedingGuideEntry(5, 5, 60)))).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("喂量：区间重叠 → 拒绝（乱序提交也能查出）")
    void feedingGuideRejectsOverlap() {
        assertThatThrownBy(() -> service.validateFeedingGuide(List.of(
                new FeedingGuideEntry(5, 12, 110),
                new FeedingGuideEntry(1, 8, 60)))).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("🔴 喂量为空不阻断 —— DEP-6 未交付，强制必填会卡死商品录入")
    void emptyFeedingGuideDoesNotBlock() {
        assertThatCode(() -> service.validateFeedingGuide(List.of())).doesNotThrowAnyException();
        ShopProductForm f = validForm();   // Makanan 且无喂量
        assertThatCode(() -> service.validate(f)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("🔴 但 Makanan 留空必须给出显著警告 —— FR-109 能否成立的唯一提醒点")
    void makananWithoutGuideWarns() {
        assertThat(service.needsFeedingGuideWarning(ProductCategory.MAKANAN, List.of())).isTrue();
        assertThat(service.needsFeedingGuideWarning(ProductCategory.MAKANAN, null)).isTrue();
        // 非 Makanan 或已填 → 无警告
        assertThat(service.needsFeedingGuideWarning(ProductCategory.CAMILAN, List.of())).isFalse();
        assertThat(service.needsFeedingGuideWarning(ProductCategory.MAKANAN,
                List.of(new FeedingGuideEntry(1, 5, 60)))).isFalse();
    }

    @Test
    @DisplayName("表单把三个平行数组组装成结构化数组，空行跳过")
    void formAssemblesFeedingGuide() {
        ShopProductForm f = validForm();
        f.setFeedWeightMinKg(java.util.Arrays.asList(1, null, 5));
        f.setFeedWeightMaxKg(java.util.Arrays.asList(5, null, 10));
        f.setFeedGramsPerDay(java.util.Arrays.asList(60, null, 110));
        assertThat(f.feedingGuide()).containsExactly(
                new FeedingGuideEntry(1, 5, 60),
                new FeedingGuideEntry(5, 10, 110));
    }

    // ---------- AC3 SKU ----------

    @Test
    @DisplayName("SKU：规格名必填、价格非负、净含量为正")
    void skuValidation() {
        ShopSkuForm f = new ShopSkuForm();
        f.setPrice(1000L);
        assertThatThrownBy(() -> service.validateSku(f)).isInstanceOf(AppException.class);
        f.setSpecName("3 kg");
        assertThatCode(() -> service.validateSku(f)).doesNotThrowAnyException();
        f.setPrice(-1L);
        assertThatThrownBy(() -> service.validateSku(f)).isInstanceOf(AppException.class);
        f.setPrice(1000L);
        f.setNetWeightG(0L);
        assertThatThrownBy(() -> service.validateSku(f)).isInstanceOf(AppException.class);
    }

    // ---------- 批发价（门店进货价，商家平台契约第 2 节） ----------

    @Test
    @DisplayName("批发价：可空，负数拒绝并挂 admin.err.product.wholesaleNegative")
    void wholesalePriceValidation() {
        ShopSkuForm f = new ShopSkuForm();
        f.setSpecName("3 kg");
        f.setPrice(1000L);
        assertThatCode(() -> service.validateSku(f)).doesNotThrowAnyException();
        f.setWholesalePrice(0L);
        assertThatCode(() -> service.validateSku(f)).doesNotThrowAnyException();
        f.setWholesalePrice(-1L);
        assertThatThrownBy(() -> service.validateSku(f))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getMessageCode()).isEqualTo("admin.err.product.wholesaleNegative"));
    }

    @Test
    @DisplayName("批发价变化才额外写一条 SHOP_SKU_WHOLESALE_UPDATED，摘要 old=… new=…；不变不写")
    void wholesaleChangeIsAuditedOnlyWhenChanged() {
        ShopProductRepository products = Mockito.mock(ShopProductRepository.class);
        ShopSkuRepository skus = Mockito.mock(ShopSkuRepository.class);
        AdminAuditService audit = Mockito.mock(AdminAuditService.class);
        AdminShopProductService svc = new AdminShopProductService(products, skus,
                Mockito.mock(InventoryService.class), new ShopTokenGenerator(), audit);
        Mockito.when(products.findById(7L)).thenReturn(
                java.util.Optional.of(Mockito.mock(com.tailtopia.shop.domain.ShopProduct.class)));
        com.tailtopia.shop.domain.ShopSku existing = com.tailtopia.shop.domain.ShopSku.create(
                "tok", 7L, "3 kg", 1000L, null, null);
        existing.applyWholesalePrice(800L);
        Mockito.when(skus.findById(11L)).thenReturn(java.util.Optional.of(existing));

        ShopSkuForm f = new ShopSkuForm();
        f.setId(11L);
        f.setSpecName("3 kg");
        f.setPrice(1000L);
        f.setWholesalePrice(800L);
        svc.upsertSku(7L, f, 1L);
        Mockito.verify(audit, Mockito.never()).record(Mockito.anyLong(),
                Mockito.eq(com.tailtopia.admin.audit.service.AuditActions.SHOP_SKU_WHOLESALE_UPDATED),
                Mockito.any(), Mockito.any(), Mockito.any());

        f.setWholesalePrice(750L);
        svc.upsertSku(7L, f, 1L);
        assertThat(existing.getWholesalePrice()).isEqualTo(750L);
        Mockito.verify(audit).record(1L,
                com.tailtopia.admin.audit.service.AuditActions.SHOP_SKU_WHOLESALE_UPDATED,
                "SHOP_SKU", "tok", "old=800 new=750");
    }
}
