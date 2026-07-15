package com.tailtopia.shared.pay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Midtrans 收款网关（Story 1.1，{@code petgo.pay.mode=live}）。Core API {@code /v2/charge}（QRIS/e-wallet），
 * 回调用 SHA-512 {@code signature_key} 验签。
 *
 * <p>护栏（照 {@code shared/ai/GeminiDeveloperApiClient} + {@code shared/im/LiveTencentImClient}）：
 * <ul>
 *   <li>Server Key 走 {@code Authorization: Basic base64(serverKey:)} 头，<b>绝不入 URL query</b>（避免落日志）。</li>
 *   <li>异常仅记<b>异常类名</b>，绝不打印 body / 凭证 / 签名 / 上游堆栈 → 抛 {@link PayException}。</li>
 *   <li>验签用纯 JDK {@link MessageDigest} SHA-512，常量时间比对（{@link MessageDigest#isEqual}）。</li>
 * </ul>
 *
 * <p>真实 sandbox 收款回调属 <b>L2</b>（需商务合同 + 凭证）；验签确定性属 <b>L0</b>（固定输入断言）。
 */
public class MidtransGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MidtransGateway.class);

    private final PayProperties props;
    private final RestClient client;

    public MidtransGateway(PayProperties props) {
        this.props = props;
        Duration timeout = Duration.ofSeconds(props.getTimeoutSeconds());
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(timeout);
        rf.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(rf)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ChargeResult createCharge(ChargeRequest request) {
        // QRIS 收款：Core API charge。gross_amount 为整数字符串（IDR 无小数）。order_id = 不可枚举 public_token。
        Map<String, Object> body = Map.of(
                "payment_type", "qris",
                "transaction_details", Map.of(
                        "order_id", request.orderId(),
                        "gross_amount", request.amount()),
                "qris", Map.of("acquirer", "gopay"));
        Map<String, Object> response;
        try {
            response = client.post()
                    .uri("/v2/charge")
                    .header("Authorization", basicAuth())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException e) {
            // 仅记异常类名，绝不外泄 body/凭证/签名/堆栈。
            log.warn("支付网关收款失败: {}", e.getClass().getSimpleName());
            throw new PayException("支付网关收款失败");
        }
        return toChargeResult(response);
    }

    @SuppressWarnings("unchecked")
    private ChargeResult toChargeResult(Map<String, Object> response) {
        if (response == null) {
            throw new PayException("支付网关收款响应为空");
        }
        String gatewayRef = str(response.get("transaction_id"));
        // actions[] 里取 generate-qr-code 的 url 作为付款载荷（前端渲染二维码）。
        String payload = null;
        Object actions = response.get("actions");
        if (actions instanceof List<?> list) {
            for (Object a : list) {
                if (a instanceof Map<?, ?> action && "generate-qr-code".equals(str(action.get("name")))) {
                    payload = str(action.get("url"));
                    break;
                }
            }
        }
        if (gatewayRef == null) {
            throw new PayException("支付网关收款响应缺 transaction_id");
        }
        // rawMeta 落脱敏快照（Midtrans charge 响应不含签名；仍走 sanitize 保守剔除）。
        return new ChargeResult(gatewayRef, payload, sanitizeCharge(response));
    }

    @Override
    @SuppressWarnings("unchecked")
    public DisburseResult disburse(DisburseRequest request) {
        // Iris/Disbursement（独立于收款 Core API）：POST {irisBaseUrl}/api/v1/payouts。
        // Basic base64(irisApiKey:) 鉴权。beneficiary_account/name 为 PII —— 仅进请求体，绝不 log。
        if (props.getIrisApiKey() == null || props.getIrisApiKey().isBlank()) {
            log.warn("退款出款被拒：irisApiKey 未配置");
            throw new PayException("退款出款网关未配置");
        }
        Duration timeout = Duration.ofSeconds(props.getTimeoutSeconds());
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(timeout);
        rf.setReadTimeout(timeout);
        RestClient iris = RestClient.builder()
                .baseUrl(props.getIrisBaseUrl())
                .requestFactory(rf)
                .build();
        Map<String, Object> payout = Map.of(
                "beneficiary_name", request.accountHolderName(),
                "beneficiary_account", request.payoutAccount(),
                "beneficiary_bank", request.channel().toLowerCase(),
                "amount", String.valueOf(request.amount()),
                "notes", "refund " + request.refundRef());
        Map<String, Object> body = Map.of("payouts", List.of(payout));
        Map<String, Object> response;
        try {
            response = iris.post()
                    .uri("/api/v1/payouts")
                    .header("Authorization", irisBasicAuth())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException e) {
            // 仅记异常类名，绝不外泄 body/凭证/PII/堆栈。
            log.warn("退款出款失败: {}", e.getClass().getSimpleName());
            throw new PayException("退款出款失败");
        }
        return toDisburseResult(response);
    }

    @SuppressWarnings("unchecked")
    private DisburseResult toDisburseResult(Map<String, Object> response) {
        if (response == null) {
            throw new PayException("退款出款响应为空");
        }
        // Iris payouts 响应 payouts[].reference_no + status（queued/processed/completed）。
        String ref = null;
        String status = null;
        Object payouts = response.get("payouts");
        if (payouts instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> p) {
            ref = str(p.get("reference_no"));
            status = str(p.get("status"));
        }
        return new DisburseResult(ref, normalizeDisburseStatus(status), sanitizeDisburse(response));
    }

    /** Iris 出款状态归一化：completed/processed→COMPLETED；queued/processing→PROCESSING；其余→FAILED。 */
    private static String normalizeDisburseStatus(String raw) {
        if (raw == null) {
            return "FAILED";
        }
        return switch (raw.toLowerCase()) {
            case "completed", "processed" -> "COMPLETED";
            case "queued", "processing", "pending" -> "PROCESSING";
            default -> "FAILED";
        };
    }

    private String irisBasicAuth() {
        String token = Base64.getEncoder()
                .encodeToString((props.getIrisApiKey() + ":").getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private static Map<String, Object> sanitizeDisburse(Map<String, Object> response) {
        // 出款响应不回显 PII（账号/户名）；保守只留 reference_no/status 层，不落原始 beneficiary。
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        Object payouts = response.get("payouts");
        if (payouts instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> p) {
            if (p.get("reference_no") != null) {
                meta.put("reference_no", p.get("reference_no"));
            }
            if (p.get("status") != null) {
                meta.put("status", p.get("status"));
            }
        }
        return meta;
    }

    @Override
    public boolean verifyCallback(Map<String, Object> body) {
        if (body == null) {
            return false;
        }
        // fail-closed（Review P1）：serverKey 缺失时期望签名会退化为可被外部算出的值 → 一律拒。
        if (props.getServerKey() == null || props.getServerKey().isBlank()) {
            log.warn("支付回调验签被拒：serverKey 未配置");
            return false;
        }
        String orderId = str(body.get("order_id"));
        String statusCode = str(body.get("status_code"));
        String grossAmount = str(body.get("gross_amount"));
        String signatureKey = str(body.get("signature_key"));
        if (orderId == null || statusCode == null || grossAmount == null || signatureKey == null) {
            return false;
        }
        // Midtrans 约定：SHA-512(order_id + status_code + gross_amount + serverKey)。
        String expected = sha512Hex(orderId + statusCode + grossAmount + props.getServerKey());
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureKey.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public PaymentCallback parseCallback(Map<String, Object> body) {
        return CallbackParser.parse(body);
    }

    // ===== 纯 JDK 工具 =====

    private String basicAuth() {
        // Basic base64(serverKey:)——密码位留空，冒号必留。
        String token = Base64.getEncoder()
                .encodeToString((props.getServerKey() + ":").getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    /** SHA-512 十六进制小写（确定性，L0 可断言）。 */
    static String sha512Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-512 必然可用；异常仅记类名，绝不外泄 serverKey。
            throw new PayException("验签摘要不可用");
        }
    }

    private static Map<String, Object> sanitizeCharge(Map<String, Object> response) {
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        response.forEach((k, v) -> {
            if (!"signature_key".equalsIgnoreCase(k)) {
                meta.put(k, v);
            }
        });
        return meta;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
