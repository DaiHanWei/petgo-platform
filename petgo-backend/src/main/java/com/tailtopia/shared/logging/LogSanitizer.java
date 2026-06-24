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
            "symptomtext", "symptom", "symptoms", "aiobservation", "diagnosis");

    /** 签名 URL 特征（命中即整串打码——OSS/S3 预签名、带 Signature/Expires 的链接）。 */
    private static final Pattern SIGNED_URL = Pattern.compile(
            "(Signature=|OSSAccessKeyId=|x-oss-|X-Amz-|Expires=\\d)", Pattern.CASE_INSENSITIVE);

    // 自建 ObjectMapper：Boot 4 默认 Jackson 3，容器内无 Jackson 2 ObjectMapper bean（与 GeminiDeveloperApiClient 一致）。
    private final ObjectMapper mapper = new ObjectMapper();

    /** 脱敏 + 截断一个 body。空体返回 ""；非 JSON 体只记类型与字节数。 */
    public String sanitize(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return "";
        }
        if (contentType == null || !contentType.toLowerCase().contains("json")) {
            return "<" + (contentType == null ? "binary" : contentType) + ", " + body.length + "B>";
        }
        try {
            JsonNode root = mapper.readTree(body);
            redact(root);
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

    private void redact(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            obj.fieldNames().forEachRemaining(name -> {
                if (SENSITIVE_KEYS.contains(name.toLowerCase())) {
                    obj.put(name, MASK);
                } else {
                    JsonNode child = obj.get(name);
                    if (child.isTextual()) {
                        obj.put(name, maskString(child.asText()));
                    } else {
                        redact(child);
                    }
                }
            });
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);
                if (child.isTextual()) {
                    arr.set(i, arr.textNode(maskString(child.asText())));
                } else {
                    redact(child);
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
