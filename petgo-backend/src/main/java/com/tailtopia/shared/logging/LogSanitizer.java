package com.tailtopia.shared.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 接口日志请求/响应体脱敏器（CLAUDE.md 护栏：日志严禁记录 PII / 健康数据 / 令牌 / 签名 URL）。
 *
 * <p>策略：JSON 体按字段名递归打码敏感键 + 签名 URL 正则整串打码 + 截断；非 JSON 体（图片/multipart）
 * 只记 content-type + 字节数。打码后**仍保留结构**，便于排查（决策：脱敏后保留其余）。
 */
@Component
public class LogSanitizer {

    /** 单个 body 最大记录字符数（超出截断）。 */
    private static final int MAX_BODY_CHARS = 2000;

    private static final String MASK = "***";

    /** 敏感字段名（小写匹配，整串打码）。涵盖令牌/密码/PII/健康症状。 */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "rawpassword", "idtoken", "accesstoken", "refreshtoken", "refresh",
            "token", "usersig", "secret", "secretkey", "signature", "authorization",
            "email", "phone", "googlesub",
            // 健康/症状类（消费侧问诊、AI 分诊）
            "symptomtext", "symptom", "symptoms", "aiobservation", "diagnosis",
            // 收货地址 PII（V1.4.0 Story 2.1 · Epic 2 头注要求）——App 内首个用户地址数据集。
            // 🔴 收件人姓名/履约电话/详细地址三项：快递员靠它们找到人，泄露即等同泄露住址。
            //    receiverphone 虽已被上面的 "phone" 命中，仍显式列出以免将来有人改那条时连带打开这里。
            "receivername", "receiverphone", "addressline", "kodepos");

    /**
     * <b>仅请求体</b>打码的字段名：用户自由文本，可含第三者 PII / 指控原文（如账号举报的
     * 「其他」补充说明，V102 建表注释明写「禁止进日志」）。
     * ⚠️ 刻意不并入 {@link #SENSITIVE_KEYS}：RFC 9457 错误<b>响应</b>体的 {@code detail}
     * 是排障主字段，全局打码会把所有错误响应弄瞎。
     */
    private static final Set<String> REQUEST_ONLY_SENSITIVE_KEYS = Set.of("detail");

    /** 签名 URL 特征（命中即整串打码——OSS/S3 预签名、带 Signature/Expires 的链接）。 */
    private static final Pattern SIGNED_URL = Pattern.compile(
            "(Signature=|OSSAccessKeyId=|x-oss-|X-Amz-|Expires=\\d)", Pattern.CASE_INSENSITIVE);

    // 自建 ObjectMapper：Boot 4 默认 Jackson 3，容器内无 Jackson 2 ObjectMapper bean（与 GeminiDeveloperApiClient 一致）。
    private final ObjectMapper mapper = new ObjectMapper();

    /** 脱敏 + 截断一个 body（响应体/通用口径）。空体返回 ""；非 JSON 体只记类型与字节数。 */
    public String sanitize(byte[] body, String contentType) {
        return sanitize(body, contentType, false);
    }

    /** <b>请求体</b>口径：在通用规则之上，额外打码 {@link #REQUEST_ONLY_SENSITIVE_KEYS}。 */
    public String sanitizeRequest(byte[] body, String contentType) {
        return sanitize(body, contentType, true);
    }

    private String sanitize(byte[] body, String contentType, boolean isRequest) {
        if (body == null || body.length == 0) {
            return "";
        }
        if (contentType == null || !contentType.toLowerCase().contains("json")) {
            return "<" + (contentType == null ? "binary" : contentType) + ", " + body.length + "B>";
        }
        try {
            JsonNode root = mapper.readTree(body);
            redact(root, isRequest);
            return truncate(mapper.writeValueAsString(root));
        } catch (Exception e) {
            // 非法 JSON / 解析失败：不冒险记原文，只记长度。
            return "<unparseable json, " + body.length + "B>";
        }
    }

    String sanitizeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return sanitize(text.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private void redact(JsonNode node, boolean isRequest) {
        if (node instanceof ObjectNode obj) {
            obj.fieldNames().forEachRemaining(name -> {
                String lower = name.toLowerCase();
                if (SENSITIVE_KEYS.contains(lower)
                        || (isRequest && REQUEST_ONLY_SENSITIVE_KEYS.contains(lower))) {
                    obj.put(name, MASK);
                } else {
                    JsonNode child = obj.get(name);
                    if (child.isTextual()) {
                        obj.put(name, maskString(child.asText()));
                    } else {
                        redact(child, isRequest);
                    }
                }
            });
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);
                if (child.isTextual()) {
                    arr.set(i, arr.textNode(maskString(child.asText())));
                } else {
                    redact(child, isRequest);
                }
            }
        }
    }

    private String maskString(String value) {
        return SIGNED_URL.matcher(value).find() ? "<signed-url>" : value;
    }

    private String truncate(String s) {
        return s.length() <= MAX_BODY_CHARS ? s : s.substring(0, MAX_BODY_CHARS) + "…(truncated)";
    }
}
