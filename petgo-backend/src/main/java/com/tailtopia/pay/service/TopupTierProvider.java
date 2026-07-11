package com.tailtopia.pay.service;

import com.tailtopia.pay.domain.TopupTier;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 充值档位来源（Story 1.3）。接口 + 内置实现——<b>Story 9.2</b> 后台可配时换 DB 实现，其余代码不动。
 */
public interface TopupTierProvider {

    /** 全部可选档位。 */
    List<TopupTier> tiers();

    /** 按对外 id 取档位；非法 id → {@link AppException#validation}（422）。 */
    TopupTier byId(String tierId);

    /** 内置默认实现：4 档固定枚举（10k/25k/50k/100k）。 */
    @Component
    class Default implements TopupTierProvider {

        @Override
        public List<TopupTier> tiers() {
            return List.of(TopupTier.values());
        }

        @Override
        public TopupTier byId(String tierId) {
            if (tierId != null) {
                for (TopupTier t : TopupTier.values()) {
                    if (t.getId().equalsIgnoreCase(tierId)) {
                        return t;
                    }
                }
            }
            throw AppException.validation("非法充值档位");
        }
    }
}
