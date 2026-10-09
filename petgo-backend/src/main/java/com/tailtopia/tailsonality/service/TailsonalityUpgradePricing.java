package com.tailtopia.tailsonality.service;

import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.purchase.domain.KeepsakePurchase;
import com.tailtopia.purchase.domain.KeepsakePurchaseStatus;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.repository.KeepsakePurchaseRepository;
import com.tailtopia.tailsonality.domain.TailsonalityResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 完整解读的补差价（2026-10-09 产品决策）：已单独买过配型的结果，再买完整解读只付「结果价 − 已付配型价」。
 *
 * <p>减的是<b>该结果实际成交的配型价</b>（{@code keepsake_purchases.price_idr}），不是当前配置价 —— 两次购买之间运营
 * 改过价也按用户真付的钱算。找不到已付配型购买行（理论上不会：match_unlocked_at 只由发放置位）时退回当前配型价。
 * 差价下限 {@link #MIN_PRICE}（= 后台 {@code AdminConfigService.MIN_UNLOCK_PRICE}、DB CHECK 同值）：收款渠道不接更小的单，
 * 后台也校验了配型价须比结果价至少低这么多。
 *
 * <p>App 展示（结果页 GET）与扣费（解锁发起）同用本类，展示价与成交价同源。
 */
@Component
public class TailsonalityUpgradePricing {

    static final long MIN_PRICE = 100;

    private final KeepsakePurchaseRepository purchases;
    private final PlatformConfigService platformConfig;

    public TailsonalityUpgradePricing(KeepsakePurchaseRepository purchases, PlatformConfigService platformConfig) {
        this.purchases = purchases;
        this.platformConfig = platformConfig;
    }

    /** 完整解读已解锁，或没单独买过配型 → null（按原价）；否则补差价。 */
    public Long upgradePriceOrNull(TailsonalityResult row) {
        if (row.getUnlockedAt() != null || row.getMatchUnlockedAt() == null) {
            return null;
        }
        PricingConfig pricing = platformConfig.pricing();
        long paidForMatch = purchases.findFirstBySkuAndRefIdAndStatusInOrderByIdDesc(KeepsakeSku.TS_MATCH,
                        row.getId(), List.of(KeepsakePurchaseStatus.PAID))
                .map(KeepsakePurchase::getPriceIdr)
                .orElse(pricing.getTailsonalityMatchUnlockPrice());
        return Math.max(pricing.getTailsonalityUnlockPrice() - paidForMatch, MIN_PRICE);
    }
}
