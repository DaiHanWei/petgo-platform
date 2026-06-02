package com.petgo.shared.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Gemini Developer API 客户端（Story 4.1，{@code mode=live}）。模型 gemini-2.5-flash，
 * 结构化输出（responseSchema 约束模型回 JSON），<b>签名 URL 直拉私密图</b>（{@code fileData.fileUri}）。
 *
 * <p>护栏：
 * <ul>
 *   <li>key 经 {@code x-goog-api-key} 头注入（不入 URL，避免 query 落日志）；绝不入库 / 不落日志。</li>
 *   <li>异常仅记录 {@code 异常类名}，<b>绝不</b>把症状文字 / 签名 URL / key / 上游堆栈写日志。</li>
 *   <li>超时 / 非 2xx / 不可解析 → 抛 {@link GeminiException}（可重试），交 triage 状态机重试 ≤3。</li>
 * </ul>
 *
 * <p>本类属 <b>L2</b>：真实端到端只有打到真实 gemini-2.5-flash + 真实签名 URL 才算验收（待本地）。
 */
public class GeminiDeveloperApiClient implements GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiDeveloperApiClient.class);

    /** 结构化输出 schema：约束模型回绿/黄/红 + 建议 + 用药参考 + 免责声明。 */
    private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "dangerLevel", Map.of("type", "STRING", "enum", List.of("GREEN", "YELLOW", "RED")),
                    "advice", Map.of("type", "STRING"),
                    "medicationRef", Map.of("type", "STRING"),
                    "disclaimer", Map.of("type", "STRING"),
                    // FR-2 黄色三要素：观察指标 / 时间窗口 / 升级触发条件（黄色应给出，绿色可省）。
                    "observation", Map.of(
                            "type", "OBJECT",
                            "properties", Map.of(
                                    "indicators", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                                    "timeWindow", Map.of("type", "STRING"),
                                    "escalationTriggers",
                                    Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))))),
            "required", List.of("dangerLevel", "advice", "disclaimer"));

    private final GeminiProperties props;
    // 自建 ObjectMapper（与 StsService 一致）：Boot 4 默认 Jackson 3，容器内无 Jackson 2 ObjectMapper bean。
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient client;

    public GeminiDeveloperApiClient(GeminiProperties props) {
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
    public GeminiTriageResult analyze(String symptomText, List<String> signedImageUrls) {
        Map<String, Object> body = buildRequest(symptomText, signedImageUrls);
        Map<String, Object> response;
        try {
            response = client.post()
                    .uri("/models/{model}:generateContent", props.getModel())
                    .header("x-goog-api-key", props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException e) {
            // 仅记录异常类名，绝不外泄症状/URL/key/堆栈。超时与非 2xx 均映射为可重试。
            log.warn("Gemini 调用失败，将重试: {}", e.getClass().getSimpleName());
            throw new GeminiException("Gemini 调用失败");
        }
        return parse(response);
    }

    private Map<String, Object> buildRequest(String symptomText, List<String> signedImageUrls) {
        List<Object> parts = new ArrayList<>();
        parts.add(Map.of("text", buildPrompt(symptomText)));
        if (signedImageUrls != null) {
            for (String url : signedImageUrls) {
                parts.add(Map.of("fileData", Map.of("mimeType", "image/jpeg", "fileUri", url)));
            }
        }
        return Map.of(
                "contents", List.of(Map.of("parts", parts)),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", RESPONSE_SCHEMA));
    }

    private String buildPrompt(String symptomText) {
        return "你是宠物分诊助手。根据主人描述的症状（及图片），判断危险级别并回结构化 JSON。"
                + "dangerLevel 取 GREEN（可观察）/ YELLOW（建议尽快就医）/ RED（疑似急症需立即就医）。"
                + "advice 给观察或处理建议；medicationRef 给用药参考（无则省略）；"
                + "disclaimer 给免责声明。症状描述：" + (symptomText == null ? "" : symptomText);
    }

    @SuppressWarnings("unchecked")
    private GeminiTriageResult parse(Map<String, Object> response) {
        try {
            List<Object> candidates = (List<Object>) response.get("candidates");
            Map<String, Object> content = (Map<String, Object>) ((Map<String, Object>) candidates.get(0)).get("content");
            List<Object> parts = (List<Object>) content.get("parts");
            String text = (String) ((Map<String, Object>) parts.get(0)).get("text");
            Map<String, Object> parsed = objectMapper.readValue(text, Map.class);
            return new GeminiTriageResult(
                    (String) parsed.get("dangerLevel"),
                    (String) parsed.get("advice"),
                    (String) parsed.get("medicationRef"),
                    (String) parsed.get("disclaimer"),
                    parseObservation((Map<String, Object>) parsed.get("observation")),
                    response);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Gemini 响应解析失败，将重试: {}", e.getClass().getSimpleName());
            throw new GeminiException("Gemini 响应解析失败");
        }
    }

    @SuppressWarnings("unchecked")
    private static TriageObservation parseObservation(Map<String, Object> obs) {
        if (obs == null) {
            return null;
        }
        return new TriageObservation(
                (List<String>) obs.get("indicators"),
                (String) obs.get("timeWindow"),
                (List<String>) obs.get("escalationTriggers"));
    }
}
