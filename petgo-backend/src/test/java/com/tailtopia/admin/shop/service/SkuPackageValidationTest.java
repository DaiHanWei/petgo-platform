package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.shop.dto.ShopSkuForm;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0（V1.4.2 shop-admin Story 4.2 · AC1 / AC2）：保存规格时包裹四项必填正整数，同事务写入并带重量体积审计。 */
class SkuPackageValidationTest {

    private static ShopSkuForm form(Integer w, Integer l, Integer wd, Integer h) {
        ShopSkuForm f = new ShopSkuForm();
        f.setSpecName("4 kg");
        f.setPrice(182_000L);
        f.setPackageWeightG(w);
        f.setPackageLengthCm(l);
        f.setPackageWidthCm(wd);
        f.setPackageHeightCm(h);
        return f;
    }

    private final AdminShopProductService service = new AdminShopProductService(
            Mockito.mock(ShopProductRepository.class), Mockito.mock(ShopSkuRepository.class),
            Mockito.mock(InventoryService.class), new ShopTokenGenerator(), Mockito.mock(AdminAuditService.class));

    @Test
    void allFourRequired() {
        for (ShopSkuForm f : new ShopSkuForm[] {form(null, 30, 20, 8), form(2150, null, 20, 8),
                form(2150, 30, null, 8), form(2150, 30, 20, null), form(null, null, null, null)}) {
            assertThatThrownBy(() -> service.validateSku(f)).isInstanceOfSatisfying(AppException.class,
                    e -> assertThat(e.getMessageCode()).isEqualTo("admin.err.v142.sku.packageRequired"));
        }
    }

    @Test
    void mustBePositive() {
        assertThatThrownBy(() -> service.validateSku(form(0, 30, 20, 8))).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.validateSku(form(2150, 30, -1, 8))).isInstanceOf(AppException.class);
        assertThatCode(() -> service.validateSku(form(2150, 30, 20, 8))).doesNotThrowAnyException();
    }

    @Test
    void newSkuGetsPackageInSameSaveAndAuditCarriesDimensions() {
        ShopProductRepository products = Mockito.mock(ShopProductRepository.class);
        ShopSkuRepository skus = Mockito.mock(ShopSkuRepository.class);
        AdminAuditService audit = Mockito.mock(AdminAuditService.class);
        AdminShopProductService svc = new AdminShopProductService(products, skus,
                Mockito.mock(InventoryService.class), new ShopTokenGenerator(), audit);
        Mockito.when(products.findById(7L)).thenReturn(Optional.of(Mockito.mock(ShopProduct.class)));
        Mockito.when(skus.save(Mockito.any())).thenAnswer(inv -> {
            ShopSku s = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(s, "id", 99L);
            return s;
        });

        ShopSku sku = svc.upsertSku(7L, form(2150, 30, 20, 8), 1L);

        assertThat(sku.isSellable()).isTrue();
        assertThat(sku.getPackageWeightG()).isEqualTo(2150);
        Mockito.verify(audit).record(Mockito.eq(1L), Mockito.eq(AuditActions.SHOP_SKU_UPSERTED), Mockito.eq("SHOP_SKU"),
                Mockito.eq(sku.getPublicToken()), Mockito.eq("package weight_g=2150 length_cm=30 width_cm=20 height_cm=8"));
    }
}
