package com.tailtopia.pay.service;

import com.tailtopia.config.domain.PawCoinTopupTier;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.pay.dto.TopupTierDto;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * DB 后台可配充值档位（Story 9.2，AB-6B）。{@code @Primary} 覆盖内置 {@link TopupTierProvider.Default}。
 * 只出**启用**档位；金额/启停由后台运营（{@code pawcoin_topup_tiers}）。DB 无启用档位（异常）→ 回退内置默认，
 * 保证充值不因配置误删全断。{@code byId} 只认启用档位（停用档位视作非法，防旧客户端下单停用档位）。
 */
@Primary
@Component
public class DbTopupTierProvider implements TopupTierProvider {

    private final PlatformConfigService config;
    private final TopupTierProvider.Default fallback;

    public DbTopupTierProvider(PlatformConfigService config, TopupTierProvider.Default fallback) {
        this.config = config;
        this.fallback = fallback;
    }

    @Override
    public List<TopupTierDto> tiers() {
        List<PawCoinTopupTier> enabled = config.enabledTiers();
        if (enabled.isEmpty()) {
            return fallback.tiers(); // 防御：配置异常全空 → 内置默认兜底。
        }
        return enabled.stream()
                .map(t -> new TopupTierDto(t.getTierKey(), t.getAmountIdr(), t.getAmountIdr()))
                .toList();
    }

    @Override
    public TopupTierDto byId(String tierId) {
        if (tierId != null) {
            for (TopupTierDto t : tiers()) {
                if (t.id().equalsIgnoreCase(tierId)) {
                    return t;
                }
            }
        }
        throw AppException.validation("非法充值档位");
    }
}
