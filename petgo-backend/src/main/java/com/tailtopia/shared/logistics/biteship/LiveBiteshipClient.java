package com.tailtopia.shared.logistics.biteship;

import com.tailtopia.shared.logistics.biteship.BiteshipException.Kind;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipCreateOrderRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipOrderSnapshot;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRate;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesRequest;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipRatesResult;
import com.tailtopia.shared.logistics.biteship.dto.CancelOutcome;
import com.tailtopia.shared.logistics.biteship.dto.CreateOutcome;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 真实 Biteship 客户端（v1.4.2 shop Story 1.1）。sandbox 与 live 共用同一 base URL，区别只在 key
 * （{@code biteship_test.} / {@code biteship_live.}）。
 *
 * <p>询价 {@code POST /v1/rates/couriers}：起止点只用邮编（JSON 数字）；<b>不传
 * {@code courier_insurance}</b>（否则 {@code price} 含 Biteship 保价费，与本平台自算保价 D-7 重复）；
 * 不传 COD 参数。
 *
 * <p>🔒 日志只记 HTTP 状态、Biteship {@code code}、快递数、耗时、目的邮编前 2 位；
 * 绝不记 key、完整邮编、地址、手机号、商品名。
 */
public class LiveBiteshipClient implements BiteshipClient {

    private static final Logger log = LoggerFactory.getLogger(LiveBiteshipClient.class);

    /** 按「不可配送」处理的 400 错误码：无可用快递 / 邮编无效或不可用。 */
    static final Set<Integer> NO_COURIER_CODES = Set.of(40001010, 40001001);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final BiteshipProperties props;
    private final String providerMode;
    private final RestClient rest;

    /**
     * @param builder 由调用方传入（测试用 {@code MockRestServiceServer.bindTo(builder)}）；
     *                本构造器会给它设置 base URL 与超时请求工厂（若 {@code applyTimeouts}）
     */
    public LiveBiteshipClient(BiteshipProperties props, String providerMode,
            RestClient.Builder builder, boolean applyTimeouts) {
        this.props = props;
        this.providerMode = providerMode;
        if (applyTimeouts) {
            SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
            rf.setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
            rf.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
            builder.requestFactory(rf);
        }
        this.rest = builder.baseUrl(props.getBaseUrl()).build();
    }

    @Override
    public String providerMode() {
        return providerMode;
    }

    @Override
    public BiteshipRatesResult rates(BiteshipRatesRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("origin_postal_code", Integer.parseInt(req.originPostalCode()));
        body.put("destination_postal_code", Integer.parseInt(req.destPostalCode()));
        body.put("couriers", props.couriersCsv());
        body.put("items", req.items().stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", truncate(i.name(), 100));
            m.put("value", i.value());
            m.put("quantity", i.quantity());
            m.put("weight", i.weightG());
            m.put("length", i.lengthCm());
            m.put("width", i.widthCm());
            m.put("height", i.heightCm());
            return m;
        }).toList());

        long started = System.nanoTime();
        String destPrefix = req.destPostalCode().substring(0, 2);
        try {
            BiteshipRatesResult result = rest.post().uri("/v1/rates/couriers")
                    .header("Authorization", props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> parse(response.getStatusCode(),
                            readBody(response.getBody())), true);
            log.info("biteship rates ok dest={}*** couriers={} noCourierCode={} tookMs={}",
                    destPrefix, result.rates().size(), result.noCourierCode(),
                    (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (BiteshipException e) {
            log.warn("biteship rates failed dest={}*** kind={} http={} code={} tookMs={}",
                    destPrefix, e.getKind(), e.getHttpStatus(), e.getBiteshipCode(),
                    (System.nanoTime() - started) / 1_000_000);
            throw e;
        } catch (ResourceAccessException e) {
            // 连接 / 读超时、网络错误
            log.warn("biteship rates failed dest={}*** kind=NETWORK tookMs={}", destPrefix,
                    (System.nanoTime() - started) / 1_000_000);
            throw new BiteshipException(Kind.NETWORK, null, null);
        }
    }

    /** 解析询价响应（包级可见，单测直接覆盖）。 */
    static BiteshipRatesResult parse(HttpStatusCode status, byte[] body) {
        int http = status.value();
        JsonNode root;
        try {
            root = body == null || body.length == 0 ? null : JSON.readTree(body);
        } catch (JacksonException e) {
            root = null;
            if (status.is2xxSuccessful()) {
                throw new BiteshipException(Kind.PARSE, http, null);
            }
        }
        Integer code = root != null && root.path("code").isNumber() ? root.path("code").asInt() : null;

        if (status.is2xxSuccessful()) {
            if (root == null || !root.isObject()) {
                throw new BiteshipException(Kind.PARSE, http, null);
            }
            if (root.has("success") && !root.path("success").asBoolean(false)) {
                // 2xx 却 success=false：按错误体处理
                if (code != null && NO_COURIER_CODES.contains(code)) {
                    return BiteshipRatesResult.noCourier(code);
                }
                throw new BiteshipException(Kind.BAD_REQUEST, http, code);
            }
            JsonNode pricing = root.path("pricing");
            if (pricing.isMissingNode() || pricing.isNull()) {
                return BiteshipRatesResult.noCourier(null);
            }
            if (!pricing.isArray()) {
                throw new BiteshipException(Kind.PARSE, http, null);
            }
            List<BiteshipRate> rates = new ArrayList<>();
            for (JsonNode p : pricing) {
                rates.add(rate(p, http));
            }
            return rates.isEmpty() ? BiteshipRatesResult.noCourier(null) : BiteshipRatesResult.of(rates);
        }
        if (http == 400 && code != null && NO_COURIER_CODES.contains(code)) {
            return BiteshipRatesResult.noCourier(code);
        }
        Kind kind;
        if (http == 401 || http == 403) {
            kind = Kind.AUTH;
        } else if (http == 402 || http == 422) {
            kind = Kind.QUOTA;
        } else if (http == 429) {
            kind = Kind.RATE_LIMITED;
        } else if (status.is5xxServerError()) {
            kind = Kind.SERVER;
        } else {
            kind = Kind.BAD_REQUEST;
        }
        throw new BiteshipException(kind, http, code);
    }

    private static BiteshipRate rate(JsonNode p, int http) {
        JsonNode price = p.path("price");
        String courierCode = text(p, "courier_code");
        String serviceCode = text(p, "courier_service_code");
        if (!price.isNumber() || courierCode == null || serviceCode == null) {
            throw new BiteshipException(Kind.PARSE, http, null);
        }
        BigDecimal dec = price.decimalValue();
        if (dec.signum() < 0) {
            throw new BiteshipException(Kind.PARSE, http, null);
        }
        return new BiteshipRate(
                courierCode,
                text(p, "courier_name"),
                serviceCode,
                text(p, "courier_service_name"),
                text(p, "duration"),
                text(p, "shipment_duration_range"),
                text(p, "shipment_duration_unit"),
                dec.setScale(0, RoundingMode.CEILING).longValueExact(),
                p.path("available_for_insurance").asBoolean(false));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static byte[] readBody(InputStream in) throws IOException {
        return in == null ? new byte[0] : in.readAllBytes();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.codePointCount(0, s.length()) <= max ? s
                : s.substring(0, s.offsetByCodePoints(0, max));
    }

    // ---------- 运单（v1.4.2 shop Story 5.1） ----------

    /**
     * 下运单 {@code POST /v1/orders}。2xx 且 {@code success != false} → {@code Created}；4xx → {@code Rejected}
     * （含 {@code 40002060} 重复 reference）；5xx / 超时 / 网络 / 2xx 解析失败 → {@code Unknown}（结果不确定）。
     *
     * <p>🔒 请求 / 响应体不记日志；只记 referenceId、Biteship 订单 id、HTTP 状态、Biteship 错误码（P-7）。
     */
    @Override
    public CreateOutcome createOrder(BiteshipCreateOrderRequest req) {
        Map<String, Object> body = createBody(req);
        long started = System.nanoTime();
        try {
            CreateOutcome out = rest.post().uri("/v1/orders")
                    .header("Authorization", props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> parseCreate(response.getStatusCode(),
                            readBody(response.getBody())), true);
            switch (out) {
                case CreateOutcome.Created c -> log.info("biteship createOrder ok ref={} id={} status={} tookMs={}",
                        req.referenceId(), c.order().id(), c.order().status(), ms(started));
                case CreateOutcome.Rejected r -> log.warn("biteship createOrder rejected ref={} code={} tookMs={}",
                        req.referenceId(), r.code(), ms(started));
                case CreateOutcome.Unknown u -> log.warn("biteship createOrder unknown ref={} cause={} tookMs={}",
                        req.referenceId(), u.cause(), ms(started));
            }
            return out;
        } catch (ResourceAccessException e) {
            log.warn("biteship createOrder unknown ref={} cause=NETWORK tookMs={}", req.referenceId(), ms(started));
            return new CreateOutcome.Unknown("NETWORK");
        } catch (RuntimeException e) {
            log.warn("biteship createOrder unknown ref={} cause={} tookMs={}", req.referenceId(),
                    e.getClass().getSimpleName(), ms(started));
            return new CreateOutcome.Unknown(e.getClass().getSimpleName());
        }
    }

    /** 组装下单请求体（包级可见，单测断言字段）。 */
    static Map<String, Object> createBody(BiteshipCreateOrderRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        contact(body, "origin", req.origin());
        contact(body, "destination", req.destination());
        body.put("courier_company", req.courierCompany());
        body.put("courier_type", req.courierType());
        body.put("delivery_type", req.deliveryType() == null ? "now" : req.deliveryType());
        body.put("reference_id", req.referenceId());
        if (req.insuranceValue() != null) {
            body.put("courier_insurance", req.insuranceValue());
        }
        body.put("items", req.items().stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", truncate(i.name(), 100));
            m.put("value", i.value());
            m.put("quantity", i.quantity());
            m.put("weight", i.weightG());
            m.put("length", i.lengthCm());
            m.put("width", i.widthCm());
            m.put("height", i.heightCm());
            return m;
        }).toList());
        return body;
    }

    private static void contact(Map<String, Object> body, String prefix, BiteshipCreateOrderRequest.Contact c) {
        if (c == null) {
            return;
        }
        body.put(prefix + "_contact_name", c.name());
        body.put(prefix + "_contact_phone", c.phone());
        body.put(prefix + "_address", c.address());
        Object pc = c.postalCode();
        try {
            pc = Integer.parseInt(c.postalCode());
        } catch (NumberFormatException | NullPointerException ignored) {
            // 原样传字符串，由 Biteship 判定
        }
        body.put(prefix + "_postal_code", pc);
    }

    /** 解析下单响应（包级可见）。 */
    static CreateOutcome parseCreate(HttpStatusCode status, byte[] body) {
        int http = status.value();
        JsonNode root = readOrNull(body);
        Integer code = root != null && root.path("code").isNumber() ? root.path("code").asInt() : null;
        if (status.is2xxSuccessful()) {
            if (root == null || !root.isObject()) {
                return new CreateOutcome.Unknown("PARSE");
            }
            if (root.has("success") && !root.path("success").asBoolean(false)) {
                return new CreateOutcome.Rejected(code == null ? "HTTP_" + http : String.valueOf(code),
                        errorMessage(root));
            }
            BiteshipOrderSnapshot snap = snapshot(root);
            if (snap.id() == null) {
                return new CreateOutcome.Unknown("PARSE");
            }
            return new CreateOutcome.Created(snap);
        }
        if (status.is4xxClientError()) {
            return new CreateOutcome.Rejected(code == null ? "HTTP_" + http : String.valueOf(code),
                    root == null ? null : errorMessage(root));
        }
        return new CreateOutcome.Unknown("HTTP_" + http);
    }

    /**
     * 取消运单 {@code POST /v1/orders/:id/cancel}。2xx → {@code Cancelled}；4xx → 回查当前状态后
     * {@code NotCancellable(status)}（回查失败为 {@code "unknown"}）；5xx / 网络 → {@code Unknown}。
     */
    @Override
    public CancelOutcome cancelOrder(String biteshipOrderId, String reason) {
        long started = System.nanoTime();
        Integer[] httpOut = new Integer[1];
        try {
            CancelOutcome out = rest.post().uri("/v1/orders/{id}/cancel", biteshipOrderId)
                    .header("Authorization", props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("cancellation_reason_code", reason))
                    .exchange((request, response) -> {
                        httpOut[0] = response.getStatusCode().value();
                        return parseCancel(response.getStatusCode(), readBody(response.getBody()));
                    }, true);
            if (out instanceof CancelOutcome.NotCancellable) {
                String current;
                try {
                    current = getOrder(biteshipOrderId).status();
                } catch (RuntimeException e) {
                    current = "unknown";
                }
                out = new CancelOutcome.NotCancellable(current);
            }
            log.info("biteship cancelOrder id={} http={} outcome={} tookMs={}", biteshipOrderId, httpOut[0],
                    out.getClass().getSimpleName(), ms(started));
            return out;
        } catch (ResourceAccessException e) {
            log.warn("biteship cancelOrder unknown id={} cause=NETWORK tookMs={}", biteshipOrderId, ms(started));
            return new CancelOutcome.Unknown("NETWORK");
        } catch (RuntimeException e) {
            log.warn("biteship cancelOrder unknown id={} cause={} tookMs={}", biteshipOrderId,
                    e.getClass().getSimpleName(), ms(started));
            return new CancelOutcome.Unknown(e.getClass().getSimpleName());
        }
    }

    /** 解析取消响应（包级可见）。4xx 的 {@code providerStatus} 由调用方回查补。 */
    static CancelOutcome parseCancel(HttpStatusCode status, byte[] body) {
        JsonNode root = readOrNull(body);
        if (status.is2xxSuccessful()) {
            if (root != null && root.has("success") && !root.path("success").asBoolean(false)) {
                return new CancelOutcome.NotCancellable(null);
            }
            return new CancelOutcome.Cancelled();
        }
        if (status.is4xxClientError()) {
            return new CancelOutcome.NotCancellable(null);
        }
        return new CancelOutcome.Unknown("HTTP_" + status.value());
    }

    /**
     * 回查运单 {@code GET /v1/orders/:id}。失败抛 {@link BiteshipException}（调用方计入重试，不当成「状态没变」）。
     */
    @Override
    public BiteshipOrderSnapshot getOrder(String biteshipOrderId) {
        long started = System.nanoTime();
        try {
            BiteshipOrderSnapshot snap = rest.get().uri("/v1/orders/{id}", biteshipOrderId)
                    .header("Authorization", props.getApiKey())
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> parseGet(response.getStatusCode(),
                            readBody(response.getBody())), true);
            log.info("biteship getOrder ok id={} status={} tookMs={}", biteshipOrderId, snap.status(), ms(started));
            return snap;
        } catch (BiteshipException e) {
            log.warn("biteship getOrder failed id={} kind={} http={} code={} tookMs={}", biteshipOrderId,
                    e.getKind(), e.getHttpStatus(), e.getBiteshipCode(), ms(started));
            throw e;
        } catch (ResourceAccessException e) {
            log.warn("biteship getOrder failed id={} kind=NETWORK tookMs={}", biteshipOrderId, ms(started));
            throw new BiteshipException(Kind.NETWORK, null, null);
        }
    }

    /** 解析查单响应（包级可见）。 */
    static BiteshipOrderSnapshot parseGet(HttpStatusCode status, byte[] body) {
        int http = status.value();
        JsonNode root = readOrNull(body);
        Integer code = root != null && root.path("code").isNumber() ? root.path("code").asInt() : null;
        if (status.is2xxSuccessful()) {
            if (root == null || !root.isObject()
                    || (root.has("success") && !root.path("success").asBoolean(false))) {
                throw new BiteshipException(root == null ? Kind.PARSE : Kind.BAD_REQUEST, http, code);
            }
            BiteshipOrderSnapshot snap = snapshot(root);
            if (snap.id() == null || snap.status() == null) {
                throw new BiteshipException(Kind.PARSE, http, null);
            }
            return snap;
        }
        Kind kind;
        if (http == 401 || http == 403) {
            kind = Kind.AUTH;
        } else if (http == 429) {
            kind = Kind.RATE_LIMITED;
        } else if (status.is5xxServerError()) {
            kind = Kind.SERVER;
        } else {
            kind = Kind.BAD_REQUEST;
        }
        throw new BiteshipException(kind, http, code);
    }

    /**
     * 只取 9 个字段：🔒 {@code courier.name / phone}（司机）、{@code shipper / origin / destination}（收发件人）一律不读。
     */
    static BiteshipOrderSnapshot snapshot(JsonNode root) {
        JsonNode courier = root.path("courier");
        JsonNode price = root.path("price");
        Long p = price.isNumber()
                ? price.decimalValue().setScale(0, RoundingMode.CEILING).longValue() : null;
        return new BiteshipOrderSnapshot(
                text(root, "id"),
                text(root, "status"),
                text(courier, "waybill_id"),
                text(courier, "tracking_id"),
                text(courier, "company"),
                text(courier, "type"),
                p,
                text(courier, "link"),
                parseInstant(text(root, "updated_at")),
                insuranceFee(courier));
    }

    /**
     * V1.4.2 shop-admin 6.1 · AD-13 / G17：Biteship 实际保价费 {@code courier.insurance.fee}（字段待沙箱确认）。
     * 缺失或非数字 → null（调用方按 0 落 {@code shipments.insurance_cost}，并记 WARN）。
     */
    static Long insuranceFee(JsonNode courier) {
        JsonNode fee = courier.path("insurance").path("fee");
        return fee.isNumber() ? fee.decimalValue().setScale(0, RoundingMode.CEILING).longValue() : null;
    }

    private static java.time.Instant parseInstant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(s).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            try {
                return java.time.Instant.parse(s);
            } catch (java.time.format.DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static JsonNode readOrNull(byte[] body) {
        try {
            return body == null || body.length == 0 ? null : JSON.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** 错误描述：截断到 100 字；🔒 只进返回值，不进日志。 */
    private static String errorMessage(JsonNode root) {
        String m = text(root, "error");
        if (m == null) {
            m = text(root, "message");
        }
        return m == null ? null : truncate(m, 100);
    }

    private static long ms(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
