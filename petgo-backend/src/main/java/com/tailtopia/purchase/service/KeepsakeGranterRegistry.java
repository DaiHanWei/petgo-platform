package com.tailtopia.purchase.service;

import com.tailtopia.purchase.domain.KeepsakeGranter;
import com.tailtopia.purchase.domain.KeepsakeSku;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 按 SKU 收集 {@link KeepsakeGranter}（V1.3.2 Story 3.1 · AC4.2）。
 *
 * <p>🔴 构造时校验三个 SKU <b>各恰好一个</b>实现：缺失或重复 → {@link IllegalStateException}，Spring 上下文启动失败。
 * 缺一个的后果是某类到账永远发不出去（静默 ORPHAN_PAID），重复则发放口不确定 —— 两者都不能等到线上才发现。
 * 真实实现由 Story 3.2（Tailsonality）/ 3.4（护照快照）/ 3.5（登机牌）交付。
 */
@Component
public class KeepsakeGranterRegistry {

    private final Map<KeepsakeSku, KeepsakeGranter> bySku;

    public KeepsakeGranterRegistry(List<KeepsakeGranter> granters) {
        Map<KeepsakeSku, KeepsakeGranter> m = new EnumMap<>(KeepsakeSku.class);
        for (KeepsakeGranter g : granters) {
            KeepsakeGranter prev = m.put(g.sku(), g);
            if (prev != null) {
                throw new IllegalStateException("duplicate KeepsakeGranter for " + g.sku());
            }
        }
        for (KeepsakeSku sku : KeepsakeSku.values()) {
            if (!m.containsKey(sku)) {
                throw new IllegalStateException("missing KeepsakeGranter for " + sku);
            }
        }
        this.bySku = Collections.unmodifiableMap(m);
    }

    public KeepsakeGranter forSku(KeepsakeSku sku) {
        return bySku.get(sku);
    }
}
