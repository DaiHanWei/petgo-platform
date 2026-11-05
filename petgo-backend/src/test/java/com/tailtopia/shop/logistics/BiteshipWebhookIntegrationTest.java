package com.tailtopia.shop.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.shared.logistics.biteship.BiteshipStubScript;
import com.tailtopia.shop.logistics.repository.BiteshipWebhookEventRepository;
import com.tailtopia.shop.order.domain.DeliverySource;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShippedSource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** L1：v1.4.2 shop Story 5.1 —— Webhook 路径密钥、落表、回查推进、伪造载荷、重试与在途回查。🔴 云端不跑。 */
@TestPropertySource(properties = {"petgo.shop.sku-cap=500", "petgo.shop.biteship.mode=stub",
        "petgo.shop.biteship.webhook-secret=it-secret"})
class BiteshipWebhookIntegrationTest extends BiteshipIntegrationSupport {

    @Autowired
    private BiteshipShipmentService waybills;
    @Autowired
    private BiteshipTrackingService tracking;
    @Autowired
    private BiteshipWebhookRetryJob retryJob;
    @Autowired
    private BiteshipActivePollJob pollJob;
    @Autowired
    private BiteshipStubScript stub;
    @Autowired
    private ShipmentRepository shipments;
    @Autowired
    private BiteshipWebhookEventRepository inbox;

    private Shipment waybill(ShopOrder o) {
        waybills.createWaybill(o.getId(), ACTOR);
        return shipments.findByShopOrderIdOrderByIdAsc(o.getId()).get(0);
    }

    private void send(String secret, String body, int expect) throws Exception {
        mvc.perform(post("/api/v1/webhooks/biteship/" + secret).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().is(expect));
    }

    private void awaitOrder(String token, ShopOrderStatus st) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline) && reload(token).getStatus() != st) {
            Thread.sleep(100);
        }
        assertThat(reload(token).getStatus()).isEqualTo(st);
    }

    @Test
    @DisplayName("错误密钥 404；无 order_id 200 不落表")
    void secretAndProbe() throws Exception {
        long before = inbox.count();
        send("wrong", "{\"order_id\":\"x\"}", 404);
        send("it-secret", "{\"event\":\"order.status\"}", 200);
        assertThat(inbox.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("stub 推进 picked 后 Webhook → 订单 SHIPPED；delivered → DELIVERED / BITESHIP；重复 Webhook 无副作用")
    void pickedThenDelivered() throws Exception {
        ShopOrder o = paidBiteshipOrder(seedUser());
        Shipment s = waybill(o);
        stub.setStatus(s.getBiteshipOrderId(), "picked");
        send("it-secret", "{\"event\":\"order.status\",\"order_id\":\"" + s.getBiteshipOrderId()
                + "\",\"status\":\"picked\"}", 200);
        awaitOrder(o.getPublicToken(), ShopOrderStatus.SHIPPED);
        assertThat(reload(o.getPublicToken()).getShippedSource()).isEqualTo(ShippedSource.BITESHIP_PICKED);
        Integer notified = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'SHOP_ORDER_SHIPPED'", Integer.class, o.getUserId());
        assertThat(notified).isPositive();

        stub.setStatus(s.getBiteshipOrderId(), "delivered");
        send("it-secret", "{\"order_id\":\"" + s.getBiteshipOrderId() + "\"}", 200);
        awaitOrder(o.getPublicToken(), ShopOrderStatus.DELIVERED);
        assertThat(reload(o.getPublicToken()).getDeliverySource()).isEqualTo(DeliverySource.BITESHIP);

        send("it-secret", "{\"order_id\":\"" + s.getBiteshipOrderId() + "\"}", 200);
        Thread.sleep(500);
        assertThat(reload(o.getPublicToken()).getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("🔴 伪造载荷（delivered）但回查仍 confirmed → 订单不变")
    void forgedPayload() throws Exception {
        ShopOrder o = paidBiteshipOrder(seedUser());
        Shipment s = waybill(o);
        send("it-secret", "{\"order_id\":\"" + s.getBiteshipOrderId() + "\",\"status\":\"delivered\"}", 200);
        Thread.sleep(1000);
        assertThat(reload(o.getPublicToken()).getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
    }

    @Test
    @DisplayName("getOrder 失败留表；重试任务处理后 processed_at 写入；在途回查推进状态")
    void retryAndPoll() throws Exception {
        ShopOrder o = paidBiteshipOrder(seedUser());
        Shipment s = waybill(o);
        stub.setStatus(s.getBiteshipOrderId(), "picked");
        stub.failNextGetOrder();
        send("it-secret", "{\"order_id\":\"" + s.getBiteshipOrderId() + "\"}", 200);
        Thread.sleep(1000);
        jdbc.update("UPDATE biteship_webhook_events SET received_at = now() - INTERVAL '2 minutes' "
                + "WHERE biteship_order_id = ?", s.getBiteshipOrderId());
        retryJob.run();
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM biteship_webhook_events "
                + "WHERE biteship_order_id = ? AND processed_at IS NULL", Integer.class, s.getBiteshipOrderId());
        assertThat(pending).isZero();
        assertThat(reload(o.getPublicToken()).getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);

        stub.setStatus(s.getBiteshipOrderId(), "delivered");
        pollJob.run();
        assertThat(reload(o.getPublicToken()).getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);
    }
}
