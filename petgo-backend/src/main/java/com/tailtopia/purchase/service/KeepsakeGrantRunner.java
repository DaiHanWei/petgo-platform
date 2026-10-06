package com.tailtopia.purchase.service;

import com.tailtopia.purchase.domain.GrantOutcome;
import com.tailtopia.purchase.domain.KeepsakePurchase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 调发放口的统一收口（到账与 PawCoin 两条路径共用）。
 *
 * <p>发放在调用方事务里同步执行（不开保存点：Boot 默认的 {@code JpaTransactionManager} + Hibernate 不支持
 * {@code PROPAGATION_NESTED}，开了反而每次都抛）。发放口契约是<b>不抛异常</b>；万一违约，这里吞掉、记 error（只记 id）
 * 并返回 null，由调用方决定收尾。
 *
 * <p>⚠️ 已知局限：发放口若在内部经 {@code @Transactional} 代理抛出，外层事务可能已被标 rollback-only，吞掉也救不回来。
 * 所以发放口实现必须真正做到「不抛」—— 预期内的失败一律用 {@link GrantOutcome} 表达。
 */
@Component
public class KeepsakeGrantRunner {

    private static final Logger log = LoggerFactory.getLogger(KeepsakeGrantRunner.class);

    private final KeepsakeGranterRegistry granters;

    public KeepsakeGrantRunner(KeepsakeGranterRegistry granters) {
        this.granters = granters;
    }

    /** 发放；发放口抛异常 → 记 error 并返回 null，<b>不向上抛</b>。 */
    public GrantOutcome grantOrNull(KeepsakePurchase purchase) {
        try {
            return granters.forSku(purchase.getSku()).grant(purchase.getRefId(), purchase.getId());
        } catch (RuntimeException e) {
            log.error("keepsake grant failed purchaseId={} sku={}", purchase.getId(), purchase.getSku(), e);
            return null;
        }
    }
}
