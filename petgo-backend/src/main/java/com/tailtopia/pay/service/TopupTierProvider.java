package com.tailtopia.pay.service;

import com.tailtopia.pay.domain.TopupTier;
import com.tailtopia.pay.dto.TopupTierDto;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 充值档位来源（Story 1.3）。返回值类型 {@link TopupTierDto}（id/amount/coins）——
 * <b>Story 9.2</b> 提供 DB 实现（{@link DbTopupTierProvider}，{@code @Primary}）后台可配；
 * 本内置 {@link Default} 退为回退默认（DB 档位为空时兜底）。
 */
public interface TopupTierProvider {

    /** 全部可选（启用）档位。 */
    List<TopupTierDto> tiers();

    /** 按对外 id 取档位；非法 id → {@link AppException#validation}（422）。 */
    TopupTierDto byId(String tierId);

    /** 内置默认实现：4 档固定枚举（10k/25k/50k/100k）。DB 无档位时回退。 */
    @Component
    class Default implements TopupTierProvider {

        @Override
        public List<TopupTierDto> tiers() {
            return List.of(TopupTier.values()).stream().map(TopupTierDto::from).toList();
        }

        @Override
        public TopupTierDto byId(String tierId) {
            if (tierId != null) {
                for (TopupTier t : TopupTier.values()) {
                    if (t.getId().equalsIgnoreCase(tierId)) {
                        return TopupTierDto.from(t);
                    }
                }
            }
            throw AppException.validation("非法充值档位");
        }
    }
}
