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
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRate;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesResult;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** L0：v1.4.2 shop Story 1.1 · Biteship 询价请求组装与响应分类（MockRestServiceServer，不出网）。 */
class LiveBiteshipClientTest {

    private static final String KEY = "biteship_test.abc123";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MockRestServiceServer server;
    private LiveBiteshipClient client;

    @BeforeEach
    void setUp() {
        BiteshipProperties props = new BiteshipProperties();
        props.setMode("sandbox");
        props.setApiKey(KEY);
        props.setBaseUrl("https://api.biteship.com");
        props.setCouriers(" JNE, sicepat ,jntcargo,jne,anteraja ");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new LiveBiteshipClient(props, "SANDBOX", builder, false);
    }

    private static BiteshipRatesRequest req() {
        return new BiteshipRatesRequest("12440", "40115", List.of(
                new BiteshipRatesRequest.Item("Royal Canin Adult 3 kg", 285_000L, 2, 1000, 20, 15, 10)));
    }

    private static final String OK_BODY = """
            {"success":true,"object":"courier_pricing","message":"ok","code":20001003,
             "origin":{"postal_code":12440},"destination":{"postal_code":40115},
             "pricing":[
               {"courier_code":"jne","courier_name":"JNE","courier_service_code":"reg",
                "courier_service_name":"Reguler","duration":"2 - 3 days",
                "shipment_duration_range":"2 - 3","shipment_duration_unit":"days",
                "price":10000.4,"available_for_insurance":true,"shipping_fee":9000,"insurance_fee":0},
               {"courier_code":"sicepat","courier_name":"SiCepat","courier_service_code":"reg",
                "courier_service_name":"REG","duration":"1 - 2 days",
                "shipment_duration_range":"1 - 2","shipment_duration_unit":"days",
                "price":12000,"available_for_insurance":false}
             ]}
            """;

    @Test
    @DisplayName("请求：POST /v1/rates/couriers、原样 key、邮编为 JSON 数字、couriers 逗号串剔除 jntcargo、items 单位、无 courier_insurance")
    void requestShape() {
        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo("https://api.biteship.com/v1/rates/couriers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", KEY))
                .andExpect(r -> body.set(((MockClientHttpRequest) r).getBodyAsString()))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        client.rates(req());
        server.verify();

        JsonNode n = JSON.readTree(body.get());
        assertThat(n.path("origin_postal_code").isInt()).isTrue();
        assertThat(n.path("origin_postal_code").asInt()).isEqualTo(12440);
        assertThat(n.path("destination_postal_code").isInt()).isTrue();
        assertThat(n.path("destination_postal_code").asInt()).isEqualTo(40115);
        assertThat(n.path("couriers").asText()).isEqualTo("jne,sicepat,anteraja");
        assertThat(n.has("courier_insurance")).as("不传 courier_insurance（保价本平台自算）").isFalse();
        java.util.List<String> fields = new java.util.ArrayList<>();
        n.propertyNames().forEach(fields::add);
        assertThat(fields).as("顶层字段：不传 COD / 保价参数").containsExactly(
                "origin_postal_code", "destination_postal_code", "couriers", "items");
        JsonNode item = n.path("items").get(0);
        assertThat(item.path("name").asText()).isEqualTo("Royal Canin Adult 3 kg");
        assertThat(item.path("value").asLong()).isEqualTo(285_000L);
        assertThat(item.path("quantity").asInt()).isEqualTo(2);
        assertThat(item.path("weight").asInt()).isEqualTo(1000);
        assertThat(item.path("length").asInt()).isEqualTo(20);
        assertThat(item.path("width").asInt()).isEqualTo(15);
        assertThat(item.path("height").asInt()).isEqualTo(10);
        assertThat(item.has("description") || item.has("category") || item.has("sku")).isFalse();
    }

    @Test
    @DisplayName("商品名截断到 100 字")
    void nameTruncated() {
        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo("https://api.biteship.com/v1/rates/couriers"))
                .andExpect(r -> body.set(((MockClientHttpRequest) r).getBodyAsString()))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));
        client.rates(new BiteshipRatesRequest("12440", "40115", List.of(
                new BiteshipRatesRequest.Item("x".repeat(150), 1L, 1, 1, 1, 1, 1))));
        assertThat(JSON.readTree(body.get()).path("items").get(0).path("name").asText()).hasSize(100);
    }

    @Test
    @DisplayName("响应 pricing 映射到全字段，price 小数按 CEILING")
    void responseMapping() {
        server.expect(requestTo("https://api.biteship.com/v1/rates/couriers"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        BiteshipRatesResult r = client.rates(req());

        assertThat(r.serviceable()).isTrue();
        assertThat(r.noCourierCode()).isNull();
        assertThat(r.rates()).containsExactly(
                new BiteshipRate("jne", "JNE", "reg", "Reguler", "2 - 3 days", "2 - 3", "days",
                        10_001L, true),
                new BiteshipRate("sicepat", "SiCepat", "reg", "REG", "1 - 2 days", "1 - 2", "days",
                        12_000L, false));
    }

    @Test
    @DisplayName("success=true 且 pricing 为空 → 不可配送")
    void emptyPricingIsNoCourier() {
        server.expect(requestTo("https://api.biteship.com/v1/rates/couriers"))
                .andRespond(withSuccess("{\"success\":true,\"pricing\":[]}", MediaType.APPLICATION_JSON));
        BiteshipRatesResult r = client.rates(req());
        assertThat(r.serviceable()).isFalse();
        assertThat(r.rates()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"40001010", "40001001"})
    @DisplayName("400 + 40001010 / 40001001 → 不可配送（不是失败）")
    void noCourierCodes(int code) {
        server.expect(requestTo("https://api.biteship.com/v1/rates/couriers"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"error\":\"x\",\"code\":" + code + "}"));
        BiteshipRatesResult r = client.rates(req());
        assertThat(r.serviceable()).isFalse();
        assertThat(r.noCourierCode()).isEqualTo(code);
    }

    @ParameterizedTest
    @CsvSource({
            "400, 40000001, BAD_REQUEST",
            "401, 40100001, AUTH",
            "403, 40300001, AUTH",
            "402, 40200001, QUOTA",
            "422, 42200001, QUOTA",
            "429, 42900001, RATE_LIMITED",
            "500, 50000001, SERVER",
            "503, 50300001, SERVER",
            "404, 40400001, BAD_REQUEST"
    })
    @DisplayName("其余错误状态 → 对应 kind")
    void errorKinds(int status, int code, Kind kind) {
        server.expect(requestTo("https://api.biteship.com/v1/rates/couriers"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"error\":\"x\",\"code\":" + code + "}"));
        assertThatThrownBy(() -> client.rates(req()))
                .isInstanceOfSatisfying(BiteshipException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(kind);
                    assertThat(e.getHttpStatus()).isEqualTo(status);
                    assertThat(e.getBiteshipCode()).isEqualTo(code);
                });
    }

    @Test
    @DisplayName("超时（ResourceAccessException）→ NETWORK")
    void timeoutIsNetwork() {
        server.expect(requestTo("https://api.biteship.com/v1/rates/couriers"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThatThrownBy(() -> client.rates(req()))
                .isInstanceOfSatisfying(BiteshipException.class,
                        e -> assertThat(e.getKind()).isEqualTo(Kind.NETWORK));
    }

    @Test
    @DisplayName("2xx 坏 JSON → PARSE")
    void badJsonIsParse() {
        server.expect(requestTo("https://api.biteship.com/v1/rates/couriers"))
                .andRespond(withSuccess("{not json", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.rates(req()))
                .isInstanceOfSatisfying(BiteshipException.class,
                        e -> assertThat(e.getKind()).isEqualTo(Kind.PARSE));
    }

    @Test
    @DisplayName("2xx price 非数字 → PARSE")
    void nonNumericPriceIsParse() {
        server.expect(requestTo("https://api.biteship.com/v1/rates/couriers"))
                .andRespond(withSuccess("{\"success\":true,\"pricing\":[{\"courier_code\":\"jne\","
                        + "\"courier_service_code\":\"reg\",\"price\":\"abc\"}]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.rates(req()))
                .isInstanceOfSatisfying(BiteshipException.class,
                        e -> assertThat(e.getKind()).isEqualTo(Kind.PARSE));
    }

    @Test
    @DisplayName("providerMode 原样返回；运单方法留给 Story 5.1")
    void modeAndUnimplemented() {
        assertThat(client.providerMode()).isEqualTo("SANDBOX");
        assertThatThrownBy(() -> client.getOrder("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> client.cancelOrder("x", "others"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> client.createOrder(null)).isInstanceOf(UnsupportedOperationException.class);
    }
}
