package com.tailtopia.shared.logistics.biteship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tailtopia.shared.logistics.biteship.BiteshipException.Kind;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipCreateOrderRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipOrderSnapshot;
import com.tailtopia.shared.logistics.biteship.dto.CancelOutcome;
import com.tailtopia.shared.logistics.biteship.dto.CreateOutcome;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** L0：v1.4.2 shop Story 5.1 · Biteship 运单三方法（下单 / 取消 / 查单）请求与响应分类（MockRestServiceServer，不出网）。 */
class LiveBiteshipClientWaybillTest {

    private static final String KEY = "biteship_test.abc123";
    private static final String BASE = "https://api.biteship.com";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MockRestServiceServer server;
    private LiveBiteshipClient client;

    @BeforeEach
    void setUp() {
        BiteshipProperties props = new BiteshipProperties();
        props.setMode("sandbox");
        props.setApiKey(KEY);
        props.setBaseUrl(BASE);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new LiveBiteshipClient(props, "SANDBOX", builder, false);
    }

    private static BiteshipCreateOrderRequest req(Long insurance) {
        return new BiteshipCreateOrderRequest("TOKO-20261105-ABC123-1", "jne", "reg", 10_000L, "now",
                new BiteshipCreateOrderRequest.Contact("Gudang", "62811", "Jl. Gudang 1", "12440"),
                new BiteshipCreateOrderRequest.Contact("Budi", "628123", "Jl. 1, Kebayoran, Jaksel, DKI", "12160"),
                List.of(new BiteshipCreateOrderRequest.Item("Royal Canin 2kg", 100_000L, 2, 1000, 20, 15, 10)),
                insurance);
    }

    private static final String ORDER_BODY = """
            {"success":true,"id":"bs_1","status":"confirmed","price":10500.4,
             "courier":{"waybill_id":"WB1","tracking_id":"TR1","company":"jne","type":"reg",
                        "link":"https://track.example/WB1","name":"Driver Joko","phone":"0811111"},
             "destination":{"contact_name":"Budi","contact_phone":"628123"},
             "updated_at":"2026-11-05T10:00:00+07:00"}
            """;

    @Test
    @DisplayName("下单：字段齐全、保价按传入值、postal_code 为数字；2xx → Created 且快照只取 9 个字段")
    void createOk() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo(BASE + "/v1/orders")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", KEY))
                .andExpect(r -> body.set(((MockClientHttpRequest) r).getBodyAsString()))
                .andRespond(withSuccess(ORDER_BODY, MediaType.APPLICATION_JSON));

        CreateOutcome out = client.createOrder(req(285_000L));

        JsonNode sent = JSON.readTree(body.get());
        assertThat(sent.path("reference_id").asText()).isEqualTo("TOKO-20261105-ABC123-1");
        assertThat(sent.path("courier_company").asText()).isEqualTo("jne");
        assertThat(sent.path("courier_type").asText()).isEqualTo("reg");
        assertThat(sent.path("delivery_type").asText()).isEqualTo("now");
        assertThat(sent.path("courier_insurance").asLong()).isEqualTo(285_000L);
        assertThat(sent.path("destination_postal_code").isNumber()).isTrue();
        assertThat(sent.path("origin_contact_phone").asText()).isEqualTo("62811");
        assertThat(sent.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(sent.has("price")).as("运费不发给 Biteship").isFalse();

        assertThat(out).isInstanceOf(CreateOutcome.Created.class);
        BiteshipOrderSnapshot s = ((CreateOutcome.Created) out).order();
        assertThat(s.id()).isEqualTo("bs_1");
        assertThat(s.waybillId()).isEqualTo("WB1");
        assertThat(s.price()).isEqualTo(10_501L);
        assertThat(s.link()).isEqualTo("https://track.example/WB1");
        assertThat(s.updatedAt()).isEqualTo(Instant.parse("2026-11-05T03:00:00Z"));
        assertThat(s.toString()).doesNotContain("Joko").doesNotContain("0811111").doesNotContain("Budi");
    }

    @Test
    @DisplayName("未勾选保价：请求体不含 courier_insurance")
    void createWithoutInsurance() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo(BASE + "/v1/orders"))
                .andExpect(r -> body.set(((MockClientHttpRequest) r).getBodyAsString()))
                .andRespond(withSuccess(ORDER_BODY, MediaType.APPLICATION_JSON));
        client.createOrder(req(null));
        assertThat(JSON.readTree(body.get()).has("courier_insurance")).isFalse();
    }

    @Test
    @DisplayName("4xx（含 40002060 重复 reference）→ Rejected(code)")
    void createRejected() {
        server.expect(requestTo(BASE + "/v1/orders")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"error\":\"Reference id already used\",\"code\":40002060}"));
        CreateOutcome out = client.createOrder(req(null));
        assertThat(out).isEqualTo(new CreateOutcome.Rejected("40002060", "Reference id already used"));
    }

    @Test
    @DisplayName("5xx / 超时 → Unknown（结果不确定）")
    void createUnknown() {
        server.expect(requestTo(BASE + "/v1/orders")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(client.createOrder(req(null))).isInstanceOf(CreateOutcome.Unknown.class);
        server.reset();
        server.expect(requestTo(BASE + "/v1/orders")).andRespond(withException(new SocketTimeoutException("t")));
        assertThat(client.createOrder(req(null))).isEqualTo(new CreateOutcome.Unknown("NETWORK"));
    }

    @Test
    @DisplayName("取消：2xx → Cancelled；4xx → 回查当前状态 NotCancellable(status)；5xx → Unknown")
    void cancel() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo(BASE + "/v1/orders/bs_1/cancel")).andExpect(method(HttpMethod.POST))
                .andExpect(r -> body.set(((MockClientHttpRequest) r).getBodyAsString()))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));
        assertThat(client.cancelOrder("bs_1", "others")).isInstanceOf(CancelOutcome.Cancelled.class);
        assertThat(JSON.readTree(body.get()).path("cancellation_reason_code").asText()).isEqualTo("others");

        server.reset();
        server.expect(requestTo(BASE + "/v1/orders/bs_1/cancel")).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo(BASE + "/v1/orders/bs_1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ORDER_BODY.replace("\"confirmed\"", "\"picked\""),
                        MediaType.APPLICATION_JSON));
        assertThat(client.cancelOrder("bs_1", "others")).isEqualTo(new CancelOutcome.NotCancellable("picked"));

        server.reset();
        server.expect(requestTo(BASE + "/v1/orders/bs_1/cancel")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(client.cancelOrder("bs_1", "others")).isInstanceOf(CancelOutcome.Unknown.class);
    }

    @Test
    @DisplayName("查单：2xx → 快照；失败抛 BiteshipException（调用方计入重试）")
    void getOrder() {
        server.expect(requestTo(BASE + "/v1/orders/bs_1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(ORDER_BODY, MediaType.APPLICATION_JSON));
        BiteshipOrderSnapshot s = client.getOrder("bs_1");
        assertThat(s.status()).isEqualTo("confirmed");
        assertThat(s.trackingId()).isEqualTo("TR1");

        server.reset();
        server.expect(requestTo(BASE + "/v1/orders/bs_1")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> client.getOrder("bs_1")).isInstanceOf(BiteshipException.class)
                .extracting(e -> ((BiteshipException) e).getKind()).isEqualTo(Kind.SERVER);

        server.reset();
        server.expect(requestTo(BASE + "/v1/orders/bs_1")).andRespond(withException(new SocketTimeoutException("t")));
        assertThatThrownBy(() -> client.getOrder("bs_1")).isInstanceOf(BiteshipException.class)
                .extracting(e -> ((BiteshipException) e).getKind()).isEqualTo(Kind.NETWORK);
    }

    @Test
    @DisplayName("🔒 请求 toString 不含姓名 / 电话 / 地址")
    void requestToStringHasNoPii() {
        String s = req(1L).toString();
        assertThat(s).contains("TOKO-20261105-ABC123-1").doesNotContain("Budi").doesNotContain("628123")
                .doesNotContain("Kebayoran");
    }
}
