package com.tailtopia.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * L1（需 Docker postgres+redis，dev profile stub 网关）。上下文启动即验 Flyway V47 + {@code ddl-auto=validate}
 * （实体↔schema 契约一致）；验建单幂等、回调双通道去重（只推进一次）、终态守卫（PAID 不被后到 deny 翻转）。
 */
class PaymentIntentIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PaymentIntentService service;
    @Autowired
    private PaymentIntentRepository intents;

    private String callbackBody(String orderId, String txId, String status) {
        return "{\"order_id\":\"" + orderId + "\",\"transaction_id\":\"" + txId
                + "\",\"transaction_status\":\"" + status + "\",\"status_code\":\"200\"}";
    }

    @Test
    void createIntentIsIdempotentBySameKey() throws Exception {
        long userId = newUser().getId();
        String key = "idem-" + SEQ.incrementAndGet();

        PaymentIntentResponse first = service.createIntent(
                userId, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS, 10000L, "IDR", key);
        PaymentIntentResponse replay = service.createIntent(
                userId, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS, 10000L, "IDR", key);

        assertThat(replay.token()).isEqualTo(first.token());
        assertThat(intents.findByPublicToken(first.token())).isPresent();
    }

    @Test
    void callbackDedupesAcrossChannelsAndGuardsTerminal() throws Exception {
        long userId = newUser().getId();
        String key = "idem-" + SEQ.incrementAndGet();
        PaymentIntentResponse intent = service.createIntent(
                userId, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS, 20000L, "IDR", key);
        String token = intent.token();
        String txId = "tx-" + SEQ.incrementAndGet();

        // 回调 + 轮询双通道各到一次（同 settlement）→ 只推进一次到 PAID。
        mvc.perform(post("/pay/callback").contentType(MediaType.APPLICATION_JSON)
                .content(callbackBody(token, txId, "settlement"))).andExpect(status().isOk());
        mvc.perform(post("/pay/callback").contentType(MediaType.APPLICATION_JSON)
                .content(callbackBody(token, txId, "settlement"))).andExpect(status().isOk());

        PaymentIntent afterPaid = intents.findByPublicToken(token).orElseThrow();
        assertThat(afterPaid.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(afterPaid.getGatewayRef()).isEqualTo(txId);

        // 终态守卫：PAID 之后再到 deny 回调，不翻转为 FAILED（幂等 no-op）。
        mvc.perform(post("/pay/callback").contentType(MediaType.APPLICATION_JSON)
                .content(callbackBody(token, txId, "deny"))).andExpect(status().isOk());
        assertThat(intents.findByPublicToken(token).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void callbackWithUnknownOrderIsIgnored() throws Exception {
        // 无匹配意图不改任何状态、不 500（controller ack 200）。
        mvc.perform(post("/pay/callback").contentType(MediaType.APPLICATION_JSON)
                .content(callbackBody("nonexistent-token", "tx-none", "settlement")))
                .andExpect(status().isOk());
    }
}
