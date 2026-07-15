package com.tailtopia.shared.pay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * L0：桩网关出款（Story 4.6）。确定性 ref + 同步 COMPLETED；rawMeta **绝不含 PII**（账号/户名）。
 */
class StubPaymentGatewayDisburseTest {

    private final StubPaymentGateway gateway = new StubPaymentGateway(new PayProperties());

    @Test
    void disburse_deterministicRef_completed_noPii() {
        DisburseResult res = gateway.disburse(new DisburseRequest(
                "tok-abc", 47500, "IDR", "OVO", "1234567890", "Budi Santoso"));

        assertThat(res.disbursementRef()).isEqualTo("stub-payout-tok-abc");
        assertThat(res.isCompleted()).isTrue();
        // rawMeta 绝不回显 PII（账号/户名）
        assertThat(res.rawMeta().toString()).doesNotContain("1234567890").doesNotContain("Budi");
    }
}
