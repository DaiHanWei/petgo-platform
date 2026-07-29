package com.tailtopia.content.moderation;

import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.ImageModerationRequest;
import com.aliyun.green20220302.models.ImageModerationResponse;
import com.aliyun.green20220302.models.ImageModerationResponseBody;
import com.aliyun.green20220302.models.TextModerationRequest;
import com.aliyun.green20220302.models.TextModerationResponse;
import com.aliyun.green20220302.models.TextModerationResponseBody;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 阿里云内容安全（green20220302）live 客户端（内容审核 Story 1，{@code app.moderation.mode=live}）。
 *
 * <p>接国际站文本审核多语言服务（region {@code ap-southeast-1}，覆盖印尼语；service 码可配，默认
 * {@code text_standard}）。图像审核（{@code ImageModeration}）本期未开通 → {@link #scanImage} 恒 fail-closed。
 *
 * <p><b>fail-closed 不变量（方案 §4.3）</b>：任何异常 / 非 200 业务码 / 凭证缺失 / 客户端初始化失败，一律抛
 * {@link ModerationDegradedException}（按 {@link DegradeReason} 分类），门面映射为 {@code DEGRADED} → 转人工，
 * <b>绝不返回 PASS</b>。
 *
 * <p>护栏：AK/Secret 从 {@link ModerationProperties.Aliyun} 注入，<b>绝不落日志</b>；日志仅记 region/service/
 * 布尔态，不记原文 / 图 URL / AK / 上游堆栈。
 */
public class AliyunContentSafetyClient implements ContentSafetyClient {

    private static final Logger log = LoggerFactory.getLogger(AliyunContentSafetyClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 国际站多语言文本审核单次字符上限；超限截断，避免必定 4xx（宁可只审前段，也不整体降级）。 */
    private static final int MAX_CONTENT_CHARS = 600;
    private static final int CONNECT_TIMEOUT_MS = 3000;

    private final ModerationProperties props;
    /** 初始化失败 / 凭证缺失 → null，所有调用 fail-closed（不在启动期崩溃）。 */
    private final Client client;

    public AliyunContentSafetyClient(ModerationProperties props) {
        this.props = props;
        this.client = tryBuildClient(props);
    }

    private static Client tryBuildClient(ModerationProperties props) {
        ModerationProperties.Aliyun a = props.getAliyun();
        if (isBlank(a.getAccessKeyId()) || isBlank(a.getAccessKeySecret())) {
            log.warn("Aliyun content-safety live: 凭证缺失（AK/Secret 未配置），文本审核将全部 fail-closed（DEGRADED）。region={}",
                    a.getRegion());
            return null;
        }
        try {
            Config config = new Config()
                    .setAccessKeyId(a.getAccessKeyId())
                    .setAccessKeySecret(a.getAccessKeySecret())
                    .setEndpoint(a.getEndpoint())
                    .setRegionId(a.getRegion())
                    .setConnectTimeout(CONNECT_TIMEOUT_MS)
                    .setReadTimeout(props.getTextTimeoutMs());
            Client c = new Client(config);
            log.info("Aliyun content-safety live client 就绪：region={}, endpoint={}, textService={}, readTimeoutMs={}",
                    a.getRegion(), a.getEndpoint(), a.getTextService(), props.getTextTimeoutMs());
            return c;
        } catch (Exception e) {
            // 仅记异常类型，不记 message（可能含配置细节）。
            log.error("Aliyun content-safety live client 初始化失败（文本审核将全部 fail-closed）：{}",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public TextScore scanText(String text) {
        if (client == null) {
            throw new ModerationDegradedException(DegradeReason.HTTP_4XX,
                    "aliyun client not initialized (credentials?)");
        }
        String content = text == null ? "" : text;
        if (content.length() > MAX_CONTENT_CHARS) {
            content = content.substring(0, MAX_CONTENT_CHARS);
        }
        try {
            String params = JSON.writeValueAsString(Map.of("content", content));
            TextModerationRequest req = new TextModerationRequest()
                    .setService(props.getAliyun().getTextService())
                    .setServiceParameters(params);
            TextModerationResponse resp = client.textModeration(req);
            TextModerationResponseBody body = resp == null ? null : resp.getBody();
            if (body == null) {
                throw new ModerationDegradedException(DegradeReason.HTTP_5XX, "aliyun empty response body");
            }
            Integer code = body.getCode();
            if (code == null || code != 200) {
                // 诊断：阿里云 HTTP 200 外壳 + 业务错误码（如 service 码不存在/未开通）。仅记码/描述/requestId，非用户内容。
                log.warn("Aliyun text moderation 业务错误：code={}, msg={}, requestId={}, service={}",
                        code, body.getMessage(), body.getRequestId(), props.getAliyun().getTextService());
                throw new ModerationDegradedException(classify(code == null ? 500 : code),
                        "aliyun biz code " + code);
            }
            var data = body.getData();
            String labels = data == null ? null : data.getLabels();
            String reason = data == null ? null : data.getReason();
            // 运营自定义词库命中：不依赖 AI riskLevel，强制高危 → 进人工审核（宠物语境靠人判）。
            String customLib = customLibHit(reason);
            if (customLib != null) {
                return new TextScore(0.9, "CUSTOM:" + customLib);
            }
            if (labels == null || labels.isBlank()) {
                return new TextScore(0.0, null); // 无风险标签且未命中自定义库 → PASS
            }
            String topLabel = labels.split(",")[0].trim().toUpperCase(Locale.ROOT);
            double score = scoreFromReason(reason);
            return new TextScore(score, topLabel);
        } catch (ModerationDegradedException e) {
            throw e;
        } catch (TeaException e) {
            throw degradeFromTea(e, "text");
        } catch (Exception e) {
            throw degradeFromGeneric(e, "text");
        }
    }

    @Override
    public ImageScore scanImage(String imageUrl) {
        // 大小模型融合图片审核（service 默认 postImageCheckByVL_global，产品拍板 2026-07-29）。
        // fail-closed 不变量同文本：任何异常/非 200 业务码（含 service 未开通）→ DEGRADED 转人工。
        if (client == null) {
            throw new ModerationDegradedException(DegradeReason.HTTP_4XX,
                    "aliyun client not initialized (credentials?)");
        }
        try {
            String params = JSON.writeValueAsString(Map.of("imageUrl", imageUrl == null ? "" : imageUrl));
            ImageModerationRequest req = new ImageModerationRequest()
                    .setService(props.getAliyun().getImageService())
                    .setServiceParameters(params);
            // 图审单独超时（SLA ≤2s）：client 构造期的 readTimeout 是文本口径，这里按次覆盖。
            RuntimeOptions runtime = new RuntimeOptions();
            runtime.connectTimeout = CONNECT_TIMEOUT_MS;
            runtime.readTimeout = props.getImageTimeoutMs();
            ImageModerationResponse resp = client.imageModerationWithOptions(req, runtime);
            ImageModerationResponseBody body = resp == null ? null : resp.getBody();
            if (body == null) {
                throw new ModerationDegradedException(DegradeReason.HTTP_5XX, "aliyun empty image response body");
            }
            Integer code = body.getCode();
            if (code == null || code != 200) {
                // 诊断：如 service 未开通/无权限会落在这里。仅记码/描述/requestId，不记图 URL。
                log.warn("Aliyun image moderation 业务错误：code={}, msg={}, requestId={}, service={}",
                        code, body.getMsg(), body.getRequestId(), props.getAliyun().getImageService());
                throw new ModerationDegradedException(classify(code == null ? 500 : code),
                        "aliyun image biz code " + code);
            }
            var data = body.getData();
            List<ImageModerationResponseBody.ImageModerationResponseBodyDataResult> results =
                    data == null ? null : data.getResult();
            Map<String, Double> merged = new java.util.HashMap<>();
            if (results != null) {
                for (var r : results) {
                    mergeImageLabel(merged, r.getLabel(),
                            r.getConfidence() == null ? 0.0 : r.getConfidence() / 100.0);
                }
            }
            return new ImageScore(merged);
        } catch (ModerationDegradedException e) {
            throw e;
        } catch (TeaException e) {
            throw degradeFromTea(e, "image");
        } catch (Exception e) {
            throw degradeFromGeneric(e, "image");
        }
    }

    /**
     * 阿里云图审标签 → 内部三分类（§4.2 阈值口径）：{@code pornographic_*}/{@code sexual_*} → porn、
     * {@code violent_*} → violence、{@code contraband_*} → contraband；同分类取最大置信度。
     * 其余标签（nonLabel/广告/政治等）不参与硬拦截判定（§4.2 只定义三类），忽略。包级可见供 L0 单测。
     */
    static void mergeImageLabel(Map<String, Double> merged, String label, double confidence01) {
        if (label == null || label.isBlank()) {
            return;
        }
        String l = label.toLowerCase(Locale.ROOT);
        String category;
        if (l.startsWith("pornographic") || l.startsWith("sexual") || l.startsWith("porn")) {
            category = "porn";
        } else if (l.startsWith("violent") || l.startsWith("violence")) {
            category = "violence";
        } else if (l.startsWith("contraband")) {
            category = "contraband";
        } else {
            return;
        }
        merged.merge(category, confidence01, Math::max);
    }

    /**
     * 运营自定义词库命中检测：解析 Reason JSON 的 {@code customizedLibs} / {@code customizedWords}
     * （阿里云自定义库命中回填）。命中返回库名（供 topLabel 展示），否则 null。结构容错：数组对象或字符串均判命中。
     */
    private static String customLibHit(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(reason);
            JsonNode libs = n.path("customizedLibs");
            // 实测阿里云回 customizedLibs 为库名字符串（如「印尼俚语」）；兼容数组对象形式。
            if (libs.isTextual() && !libs.asText().isBlank()) {
                return libs.asText().trim();
            }
            if (libs.isArray() && !libs.isEmpty()) {
                JsonNode first = libs.get(0);
                String name = first.path("libName").asText(null);
                if (name == null) {
                    name = first.path("name").asText(null);
                }
                return name != null ? name : "custom";
            }
            JsonNode words = n.path("customizedWords");
            if ((words.isArray() && !words.isEmpty())
                    || (words.isTextual() && !words.asText().isBlank())) {
                return "custom";
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Reason JSON 的 {@code riskLevel} → 归一风险分；缺失/解析失败按「有标签即偏高」兜底 0.9（fail toward review）。 */
    private static double scoreFromReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return 0.9;
        }
        try {
            JsonNode node = JSON.readTree(reason);
            String level = node.path("riskLevel").asText("").toLowerCase(Locale.ROOT);
            return switch (level) {
                case "high" -> 0.95;
                case "medium" -> 0.85;
                case "low" -> 0.5;
                default -> 0.9;
            };
        } catch (Exception e) {
            return 0.9;
        }
    }

    private static ModerationDegradedException degradeFromTea(TeaException e, String scope) {
        String codeStr = e.getCode() == null ? "" : e.getCode().toLowerCase(Locale.ROOT);
        DegradeReason r;
        if (codeStr.contains("throttl") || codeStr.contains("limit") || codeStr.contains("quota")) {
            r = DegradeReason.QUOTA;
        } else if (codeStr.contains("timeout")) {
            r = DegradeReason.TIMEOUT;
        } else if (e.statusCode != null) {
            r = classify(e.statusCode);
        } else {
            r = DegradeReason.HTTP_5XX;
        }
        // 诊断：仅记阿里云 API 错误码 / httpStatus / 错误描述（非用户内容、非 AK），供排查 4xx（权限/service 码）。
        log.warn("Aliyun {} moderation 失败：errCode={}, httpStatus={}, apiMsg={}",
                scope, e.getCode(), e.statusCode, e.getMessage());
        return new ModerationDegradedException(r, "aliyun tea error");
    }

    private static ModerationDegradedException degradeFromGeneric(Exception e, String scope) {
        String n = e.getClass().getName().toLowerCase(Locale.ROOT);
        DegradeReason r = n.contains("timeout") ? DegradeReason.TIMEOUT : DegradeReason.HTTP_5XX;
        log.warn("Aliyun {} moderation 异常：{}: {}", scope, e.getClass().getSimpleName(), e.getMessage());
        return new ModerationDegradedException(r, "aliyun call failed");
    }

    private static DegradeReason classify(int status) {
        if (status == 429) {
            return DegradeReason.QUOTA;
        }
        if (status >= 400 && status < 500) {
            return DegradeReason.HTTP_4XX;
        }
        return DegradeReason.HTTP_5XX;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
