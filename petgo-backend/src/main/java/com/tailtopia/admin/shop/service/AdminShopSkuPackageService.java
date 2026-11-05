package com.tailtopia.admin.shop.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.repository.ShopSkuRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SKU 包裹参数后台写服务（v1.4.2 shop Story 1.1，D-10 / S-2）。
 *
 * <p>本 story 只给 service，不加 Controller / 页面（后台线接）。四项要么全部为正整数，
 * 要么全部为空（清空 = 回到「暂不可售」）。审计复用 {@link AuditActions#SHOP_SKU_UPSERTED}。
 */
@Service
public class AdminShopSkuPackageService {

    private final ShopSkuRepository skus;
    private final AdminAuditService audit;

    public AdminShopSkuPackageService(ShopSkuRepository skus, AdminAuditService audit) {
        this.skus = skus;
        this.audit = audit;
    }

    @Transactional
    public ShopSku updatePackage(String skuToken, Integer weightG, Integer lengthCm,
            Integer widthCm, Integer heightCm, long actorAdminId) {
        ShopSku sku = skus.findByPublicToken(skuToken)
                .orElseThrow(() -> AppException.notFound("规格不存在")
                        .code("admin.err.product.skuNotFound"));
        try {
            sku.applyPackage(weightG, lengthCm, widthCm, heightCm);
        } catch (IllegalArgumentException e) {
            throw AppException.validation("包裹重量与长宽高须全部填写正整数，或全部留空")
                    .code("admin.err.product.packageInvalid");
        }
        ShopSku saved = skus.save(sku);
        audit.record(actorAdminId, AuditActions.SHOP_SKU_UPSERTED, "SHOP_SKU", skuToken,
                saved.isSellable()
                        ? "package weight_g=%d length_cm=%d width_cm=%d height_cm=%d"
                                .formatted(weightG, lengthCm, widthCm, heightCm)
                        : "package cleared");
        return saved;
    }
}
