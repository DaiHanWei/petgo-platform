package com.tailtopia.shop.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.logistics.biteship.BiteshipProperties;
import com.tailtopia.shop.logistics.domain.BiteshipWebhookEvent;
import com.tailtopia.shop.logistics.event.BiteshipWebhookReceivedEvent;
import com.tailtopia.shop.logistics.repository.BiteshipWebhookEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** L0：v1.4.2 shop Story 5.1 AC6 —— Webhook 路径密钥、体积 / 解析校验、白名单落表与事件发布。 */
class BiteshipWebhookControllerTest {

    private static final String SECRET = "wh-s3cret-xyz";

    private final BiteshipProperties props = new BiteshipProperties();
    private final BiteshipWebhookEventRepository inbox = mock(BiteshipWebhookEventRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
    private BiteshipWebhookController controller;

    @BeforeEach
    void setUp() {
        props.setWebhookSecret(SECRET);
        controller = new BiteshipWebhookController(props, inbox, events, txm,
                Clock.fixed(LogisticsFixtures.NOW, ZoneOffset.UTC));
        when(inbox.save(any(BiteshipWebhookEvent.class))).thenAnswer(inv -> {
            BiteshipWebhookEvent e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", 42L);
            return e;
        });
    }

    private static MockHttpServletRequest body(String json) {
        return raw(json.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest raw(byte[] bytes) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/webhooks/biteship/x");
        r.setContentType("application/json");
        if (bytes != null) {
            r.setContent(bytes);
        }
        return r;
    }

    private static final String PAYLOAD = """
            {"event":"order.status","order_id":"bs_1","courier_tracking_id":"TR1","courier_waybill_id":"WB1",
             "courier_company":"jne","courier_type":"reg","courier_driver_name":"Joko",
             "courier_driver_phone":"0811111","courier_driver_plate_number":"B 1 XY","courier_link":"https://x",
             "order_price":10000,"status":"delivered","updated_at":"2026-11-05T10:00:00+07:00"}
            """;

    @Test
    @DisplayName("配置密钥为空 → 一律 404（fail closed），不落表")
    void emptyConfiguredSecret() {
        props.setWebhookSecret("");
        ResponseEntity<Void> r = controller.receive(SECRET, body(PAYLOAD));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.receive("", body(PAYLOAD)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(inbox, events);
    }

    @Test
    @DisplayName("密钥不符 → 404 且无响应体")
    void wrongSecret() {
        ResponseEntity<Void> r = controller.receive("wrong", body(PAYLOAD));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).isNull();
        assertThat(controller.receive(SECRET + "x", body(PAYLOAD)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(inbox, events);
    }

    @Test
    @DisplayName("🔴 复审：密钥不符时不读请求体；Content-Length 超限直接 413、不读体")
    void rejectsBeforeReadingBody() throws Exception {
        MockHttpServletRequest r = org.mockito.Mockito.spy(body(PAYLOAD));
        controller.receive("wrong", r);
        org.mockito.Mockito.verify(r, never()).getInputStream();

        MockHttpServletRequest big = org.mockito.Mockito.spy(body(PAYLOAD));
        when(big.getContentLengthLong()).thenReturn((long) BiteshipWebhookController.MAX_BODY_BYTES + 1);
        assertThat(controller.receive(SECRET, big).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        org.mockito.Mockito.verify(big, never()).getInputStream();
    }

    @Test
    @DisplayName("体内无 order_id（连通性测试）→ 200、不落表")
    void connectivityProbe() {
        assertThat(controller.receive(SECRET, body("{\"event\":\"order.status\"}")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(controller.receive(SECRET, body("{\"order_id\":\"\"}")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        verify(inbox, never()).save(any());
    }

    @Test
    @DisplayName("超 64KB → 413；JSON 解析失败 / 非对象 → 400，均不落表")
    void sizeAndParse() {
        byte[] big = new byte[BiteshipWebhookController.MAX_BODY_BYTES + 1];
        assertThat(controller.receive(SECRET, raw(big)).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        // 无 Content-Length（chunked）时按上限读取后判超限
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/x") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        chunked.setContent(big);
        assertThat(controller.receive(SECRET, chunked).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(controller.receive(SECRET, body("{not json")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.receive(SECRET, body("[1,2]")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.receive(SECRET, raw(null)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(inbox, never()).save(any());
    }

    @Test
    @DisplayName("🔒 正常：落表 payload 只留白名单键（不含司机信息），发布 BiteshipWebhookReceivedEvent，回 200")
    void storesWhitelistedPayloadAndPublishes() {
        ResponseEntity<Void> r = controller.receive(SECRET, body(PAYLOAD));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<BiteshipWebhookEvent> saved = ArgumentCaptor.forClass(BiteshipWebhookEvent.class);
        verify(inbox).save(saved.capture());
        BiteshipWebhookEvent e = saved.getValue();
        assertThat(e.getBiteshipOrderId()).isEqualTo("bs_1");
        assertThat(e.getEvent()).isEqualTo("order.status");
        assertThat(e.getPayload()).containsKeys("order_id", "status", "courier_waybill_id", "order_price")
                .doesNotContainKeys("courier_driver_name", "courier_driver_phone", "courier_driver_plate_number",
                        "courier_link");
        assertThat(e.getPayload().toString()).doesNotContain("0811111").doesNotContain("Joko");
        verify(events).publishEvent(new BiteshipWebhookReceivedEvent(42L));
    }

    @Test
    @DisplayName("event 缺省为 order.status；落表失败 → 500 空体")
    void defaultsAndStoreFailure() {
        controller.receive(SECRET, body("{\"order_id\":\"bs_2\"}"));
        ArgumentCaptor<BiteshipWebhookEvent> saved = ArgumentCaptor.forClass(BiteshipWebhookEvent.class);
        verify(inbox).save(saved.capture());
        assertThat(saved.getValue().getEvent()).isEqualTo("order.status");

        when(inbox.save(any(BiteshipWebhookEvent.class))).thenThrow(new IllegalStateException("db down"));
        ResponseEntity<Void> r = controller.receive(SECRET, body("{\"order_id\":\"bs_3\"}"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody()).isNull();
    }
}
