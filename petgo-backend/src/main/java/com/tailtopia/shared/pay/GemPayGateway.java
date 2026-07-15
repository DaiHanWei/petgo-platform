package com.tailtopia.shared.pay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * GemPay 收款网关（{@code petgo.pay.mode=live} + {@code provider=gempay}）。取代 {@link MidtransGateway}
 * 作为印尼实际落地收款聚合商。收款 {@code POST {base}/direct}（{@code application/x-www-form-urlencoded}），
 * md5 逐接口签名（{@link GemPaySignature}）。
 *
 * <p>护栏（照 {@link MidtransGateway}）：
 * <ul>
 *   <li>{@code merchant_secret} 只进 md5，绝不作为独立字段上送、绝不入库/落日志。</li>
 *   <li>异常仅记<b>异常类名 / error_code</b>，绝不打印 body / 凭证 / 签名 → 抛 {@link PayException}。</li>
 *   <li>回调验签用纯 JDK md5 + 常量时间比对（{@link MessageDigest#isEqual}）。</li>
 *   <li>{@code signature} / {@code merchant_secret} 在快照落库前一律剔除。</li>
 * </ul>
 *
 * <p><b>放款</b>（{@link #disburse}）GemPay 是两步 {@code inquiry→transfer}，与 Midtrans Iris 单步不同——
 * 待 Story 4.6 rework 对接，本类先 fail-fast 防误用。
 */
public class GemPayGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(GemPayGateway.class);
    private static final String QRIS_CODE = "MBayar_QR";

    private final PayProperties.Gempay g;
    private final RestClient client;

    public GemPayGateway(PayProperties props) {
        this.g = props.getGempay();
        Duration timeout = Duration.ofSeconds(props.getTimeoutSeconds());
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(timeout);
        rf.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(g.getBaseUrl())
                .requestFactory(rf)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ChargeResult createCharge(ChargeRequest request) {
        String channelCode = toGemPayChannel(request.channel());
        // request_id = 不可枚举 public_token（≤128 字符，仅字母数字下划线连字符——base62 token 天然满足）。
        String signature = GemPaySignature.charge(
                request.orderId(), request.amount(), g.getMerchantId(),
                channelCode, g.getMerchantSecret(), g.getProjectNo());

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("merchant_id", g.getMerchantId());
        form.add("project_no", g.getProjectNo());
        form.add("request_id", request.orderId());
        form.add("amount", String.valueOf(request.amount()));
        form.add("channel", channelCode);
        form.add("signature", signature);
        form.add("description", safeDescription(request.purpose())); // 无 PII / 无特殊字符（避 WAF 拦）
        form.add("callback_url", g.getCallbackUrl());
        if (QRIS_CODE.equals(channelCode)) {
            form.add("response_qr", "url"); // QRIS 返回二维码图片链接（qrcode_url）
        }

        Map<String, Object> response;
        try {
            response = client.post()
                    .uri("/direct")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException e) {
            // 仅记异常类名，绝不外泄 body/凭证/签名/堆栈。
            log.warn("GemPay 收款调用失败: {}", e.getClass().getSimpleName());
            throw new PayException("支付网关收款失败");
        }
        return toChargeResult(response);
    }

    private ChargeResult toChargeResult(Map<String, Object> response) {
        if (response == null) {
            throw new PayException("支付网关收款响应为空");
        }
        String errorCode = str(response.get("error_code"));
        if (!"P00".equals(errorCode)) {
            // 只 log 错误码（P01 参数不全 / P02 request_id 重复 / P03 鉴权失败 …），绝不 log body。
            log.warn("GemPay 收款被拒: error_code={}", errorCode);
            throw new PayException("支付网关收款失败");
        }
        String gatewayRef = str(response.get("ref_id"));
        if (gatewayRef == null) {
            throw new PayException("支付网关收款响应缺 ref_id");
        }
        // 付款载荷按渠道取：QRIS→qrcode_url/qrcode；E-Wallet→ewallet_url；VA→virtual_account。
        String payload = firstNonBlank(
                str(response.get("qrcode_url")),
                str(response.get("qrcode")),
                str(response.get("ewallet_url")),
                str(response.get("virtual_account")));
        return new ChargeResult(gatewayRef, payload, sanitize(response));
    }

    @Override
    public DisburseResult disburse(DisburseRequest request) {
        // 当前无 payout 需求：GemPay 放款（两步 inquiry→transfer）暂不接入，误用即 fail-fast。
        log.warn("GemPay 放款未接入（当前无 payout 需求）");
        throw new PayException("GemPay 放款未接入");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<PaymentCallback> queryCharge(String gatewayRef) {
        // 收款结果轮询：GemPay /history 按 ref_id 查单笔（length=1）。仅收款，不涉及 payout。
        if (gatewayRef == null || gatewayRef.isBlank()) {
            return Optional.empty();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("merchant_id", g.getMerchantId());
        form.add("project_no", g.getProjectNo());
        form.add("start", "0");
        form.add("length", "1");
        form.add("ref_id", gatewayRef);
        form.add("signature", GemPaySignature.history(g.getMerchantId(), g.getMerchantSecret(), g.getProjectNo()));

        Map<String, Object> response;
        try {
            response = client.post()
                    .uri("/history")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException e) {
            log.warn("GemPay 交易查询失败: {}", e.getClass().getSimpleName());
            throw new PayException("支付网关交易查询失败");
        }
        return toQueryResult(response);
    }

    @SuppressWarnings("unchecked")
    private Optional<PaymentCallback> toQueryResult(Map<String, Object> response) {
        if (response == null) {
            return Optional.empty();
        }
        String errorCode = str(response.get("error_code"));
        if (!"00".equals(errorCode)) {
            // 00 成功；04 data not found 等 → 查无结果（只 log 错误码，不 log body）。
            log.warn("GemPay 交易查询无结果: error_code={}", errorCode);
            return Optional.empty();
        }
        Object data = response.get("data");
        if (!(data instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> row0)) {
            return Optional.empty();
        }
        Map<String, Object> row = (Map<String, Object>) row0;
        String orderId = str(row.get("request_id"));
        String ref = str(row.get("ref_id"));
        GatewayStatus status = normalizeHistoryStatus(str(row.get("status")));
        return Optional.of(new PaymentCallback(orderId, ref, status, sanitize(row)));
    }

    /**
     * 归一化 {@code /history} 的 {@code status}（package-private 供 L0 锁）。/history 状态比回调更细，含<b>复合态</b>
     * {@code 'Failure => Success'} / {@code 'Expired => Success'}（最终已付）→ PAID；{@code Initial}/{@code Pending} → PENDING。
     */
    static GatewayStatus normalizeHistoryStatus(String status) {
        if (status == null) {
            return GatewayStatus.PENDING;
        }
        String s = status.trim().toLowerCase();
        return switch (s) {
            case "success", "failure => success", "expired => success" -> GatewayStatus.PAID;
            case "failure" -> GatewayStatus.FAILED;
            case "expired" -> GatewayStatus.EXPIRED;
            default -> GatewayStatus.PENDING; // pending / initial / 未知 → 不终态
        };
    }

    @Override
    public boolean verifyCallback(Map<String, Object> body) {
        if (body == null) {
            return false;
        }
        // fail-closed：secret 缺失时签名无从校验 → 一律拒（同 Midtrans serverKey 空即拒）。
        if (isBlank(g.getMerchantSecret())) {
            log.warn("GemPay 回调验签被拒：merchantSecret 未配置");
            return false;
        }
        String requestId = str(body.get("request_id"));
        String signature = str(body.get("signature"));
        if (requestId == null || signature == null) {
            return false;
        }
        // ⚠️ UNCONFIRMED 公式（GemPay 文档缺失，见 GEMPAY-INTEGRATION-DESIGN §6.3）。猜错只会 fail-closed 拒合法回调，不放行伪造。
        String expected = GemPaySignature.callback(requestId, g.getMerchantId(), g.getMerchantSecret());
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public PaymentCallback parseCallback(Map<String, Object> body) {
        // GemPay 回调字段：request_id(=public_token) / ref_id(=gateway_ref) / status(success|failure)。
        String orderId = str(body.get("request_id"));
        String gatewayRef = str(body.get("ref_id"));
        GatewayStatus status = GatewayStatus.fromGemPay(str(body.get("status")));
        return new PaymentCallback(orderId, gatewayRef, status, sanitize(body));
    }

    // ===== 纯工具 =====

    /** 我方 {@code PayChannel} 名 → GemPay 渠道 Code。V1 仅 QRIS 开通；非法/未开通 → 拒。（package-private 供 L0 锁映射） */
    static String toGemPayChannel(String channel) {
        if (channel == null) {
            throw new PayException("支付渠道缺失");
        }
        return switch (channel.trim().toUpperCase()) {
            case "QRIS" -> QRIS_CODE;
            // 未来放开：DANA_EWALLET(需 redirect_url) / BNI_VA / Mandiri_VA / Permata_VA / BRI_VA / CIMB_VA
            default -> throw new PayException("不支持的支付渠道");
        };
    }

    /** 用途 → 安全订单描述（无 PII、无特殊字符，避免被 WAF 拦）。 */
    private static String safeDescription(String purpose) {
        if (purpose == null) {
            return "TailTopia Payment";
        }
        return switch (purpose) {
            case "PAWCOIN_TOPUP" -> "PawCoin Topup";
            case "VET_CONSULT" -> "Vet Consultation";
            case "AI_UNLOCK" -> "AI Unlock";
            case "ID_HD" -> "Pet ID HD";
            default -> "TailTopia Payment";
        };
    }

    /** 拷贝快照并剔除敏感字段（签名/密钥绝不落库/漂日志）。 */
    private static Map<String, Object> sanitize(Map<String, Object> body) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (body != null) {
            body.forEach((k, v) -> {
                String lk = k == null ? "" : k.toLowerCase();
                if (!lk.equals("signature") && !lk.equals("signature_key") && !lk.equals("merchant_secret")) {
                    meta.put(k, v);
                }
            });
        }
        return meta;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
