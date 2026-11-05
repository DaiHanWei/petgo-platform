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

    @Override
    public CreateOutcome createOrder(BiteshipCreateOrderRequest req) {
        throw new UnsupportedOperationException("Story 5.1");
    }

    @Override
    public CancelOutcome cancelOrder(String biteshipOrderId, String reason) {
        throw new UnsupportedOperationException("Story 5.1");
    }

    @Override
    public BiteshipOrderSnapshot getOrder(String biteshipOrderId) {
        throw new UnsupportedOperationException("Story 5.1");
    }
}
