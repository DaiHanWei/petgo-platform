package com.tailtopia.admin.config.web;

import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.starterpack.domain.StarterPackTier;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 后台模板用的礼包只读视图（V1.5.0 gift-pack Story 5-1）：配置卡三档参考价、运营弹窗 / 系统通知「已停售」提示
 * （模板经 {@code ${@starterPackAdminView...}} 读，宿主控制器不用各自塞 model）。
 */
@Component("starterPackAdminView")
public class StarterPackAdminView {

    /** 一档：档位号、当前价、单买合计（按当前单价）。 */
    public record TierRow(int tier, long price, long separateTotal) {
    }

    private final PlatformConfigService platformConfig;

    public StarterPackAdminView(PlatformConfigService platformConfig) {
        this.platformConfig = platformConfig;
    }

    /** 礼包是否已停售（{@code starter_pack_enabled = false}）。 */
    public boolean disabled() {
        return !platformConfig.pricing().isStarterPackEnabled();
    }

    public List<TierRow> tiers() {
        PricingConfig p = platformConfig.pricing();
        return java.util.Arrays.stream(StarterPackTier.values())
                .map(t -> new TierRow(t.number(), t.price(p), t.separateTotal(p)))
                .toList();
    }
}
