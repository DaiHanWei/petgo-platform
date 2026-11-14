package com.tailtopia.entitlement;

import com.tailtopia.entitlement.domain.CreditSource;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.service.EntitlementCreditService;
import com.tailtopia.entitlement.service.GrantCommand;
import org.springframework.transaction.support.TransactionTemplate;

/** L1 测试辅助（V1.5.0 gift-pack Story 4-3）：经唯一发放口给用户发次数（PACK 来源，独立事务提交）。 */
public final class PackCreditTestSupport {

    private PackCreditTestSupport() {
    }

    public static void grant(TransactionTemplate tx, EntitlementCreditService credits, long userId, CreditType type,
            int quantity, String key) {
        tx.executeWithoutResult(s -> credits.grant(new GrantCommand(userId, type, quantity, CreditSource.PACK, "1",
                "TEST:" + key, null, null, null, null)));
    }
}
