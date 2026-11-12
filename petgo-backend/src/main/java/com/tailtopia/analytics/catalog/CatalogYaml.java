package com.tailtopia.analytics.catalog;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.analytics.AnalyticsPipelineState;
import com.tailtopia.analytics.AnalyticsPipelineState.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * 两份 yaml（事件清单、口径）共用的读取、类型校验与失败处理（analytics-pipeline Story 1.2）。
 *
 * <p>🔒 只用 {@link SafeConstructor}：只构造 map / list / 标量，绝不按 yaml 里的 tag 反序列化任意类型。
 * 重复键直接判错（{@link LoaderOptions#setAllowDuplicateKeys}），免得后一条静默覆盖前一条。
 */
final class CatalogYaml {

    /** 结构错误最多列出多少条（够定位即可，告警文案不要无限长）。 */
    static final int MAX_ERRORS = 20;

    private CatalogYaml() {
    }

    /** 读 classpath / 文件资源并做语法解析；语法错误的消息带行列（从 1 起）。 */
    static Object parse(Resource resource, String fileLabel) throws CatalogInvalidException {
        if (resource == null || !resource.exists()) {
            throw new CatalogInvalidException(fileLabel + "：文件不存在");
        }
        try (InputStream in = resource.getInputStream();
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return parse(reader, fileLabel);
        } catch (IOException e) {
            throw new CatalogInvalidException(fileLabel + "：读取失败（" + e.getClass().getSimpleName() + "）", e);
        }
    }

    static Object parse(Reader reader, String fileLabel) throws CatalogInvalidException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try {
            return yaml.load(reader);
        } catch (MarkedYAMLException e) {
            Mark mark = e.getProblemMark() != null ? e.getProblemMark() : e.getContextMark();
            String where = mark == null ? "" : "第 " + (mark.getLine() + 1) + " 行第 " + (mark.getColumn() + 1) + " 列：";
            String problem = e.getProblem() != null ? e.getProblem() : e.getClass().getSimpleName();
            throw new CatalogInvalidException(fileLabel + " " + where + "yaml 语法错误（" + problem + "）", e);
        } catch (YAMLException e) {
            throw new CatalogInvalidException(fileLabel + "：yaml 解析失败（" + e.getClass().getSimpleName() + "）", e);
        }
    }

    // ---------------------------------------------------------------- 结构校验小工具（错误累积进 errors，带路径）

    static final class Errors {
        private final String fileLabel;
        private final List<String> messages = new ArrayList<>();

        Errors(String fileLabel) {
            this.fileLabel = fileLabel;
        }

        void add(String path, String problem) {
            if (messages.size() < MAX_ERRORS) {
                messages.add(path + " " + problem);
            } else if (messages.size() == MAX_ERRORS) {
                messages.add("……（其余错误略）");
            }
        }

        boolean isEmpty() {
            return messages.isEmpty();
        }

        void throwIfAny() throws CatalogInvalidException {
            if (!messages.isEmpty()) {
                throw new CatalogInvalidException(fileLabel + " 结构错误：" + String.join("；", messages));
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object node, String path, Errors errors) {
        if (node instanceof Map<?, ?> m) {
            for (Object k : m.keySet()) {
                if (!(k instanceof String)) {
                    errors.add(path, "的键必须是字符串：" + k);
                    return null;
                }
            }
            return (Map<String, Object>) m;
        }
        errors.add(path, node == null ? "缺失" : "必须是映射（key: value）");
        return null;
    }

    static List<?> asList(Object node, String path, Errors errors) {
        if (node instanceof List<?> l) {
            return l;
        }
        errors.add(path, node == null ? "缺失" : "必须是列表");
        return null;
    }

    /** 必填字符串：非空、去首尾空白后非空、不超过 maxLen（{@code maxLen <= 0} 不限）。 */
    static String requiredString(Map<String, Object> map, String key, String path, int maxLen, Errors errors) {
        Object raw = map.get(key);
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            errors.add(path + "." + key, "不能为空");
            return null;
        }
        return optionalString(map, key, path, maxLen, errors);
    }

    /**
     * 必填<b>名字</b>（事件名 / 属性名 / 指标编号）：yaml 里必须就是字符串标量，不做数字 / 布尔 → 字符串的宽松转换。
     * 否则未加引号的 {@code on} / {@code yes} 会被 YAML 1.1 解析成布尔、再被转成 {@code "true"}，静默登记错键名。
     */
    static String requiredName(Map<String, Object> map, String key, String path, int maxLen, Errors errors) {
        Object raw = map.get(key);
        if (raw != null && !(raw instanceof String)) {
            errors.add(path + "." + key, "必须是字符串（on / off / yes / no / true / false 及纯数字要加引号）：" + raw);
            return null;
        }
        return requiredString(map, key, path, maxLen, errors);
    }

    /** 可选字符串：缺省 / 空串返回 null。日期标量（未加引号）按 yyyy-MM-dd（UTC）转成字符串。 */
    static String optionalString(Map<String, Object> map, String key, String path, int maxLen, Errors errors) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        String s;
        if (v instanceof String str) {
            s = str.strip();
        } else if (v instanceof Number || v instanceof Boolean) {
            s = v.toString();
        } else if (v instanceof Date d) {
            s = d.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
        } else {
            errors.add(path + "." + key, "必须是字符串");
            return null;
        }
        if (s.isEmpty()) {
            return null;
        }
        if (maxLen > 0 && s.length() > maxLen) {
            errors.add(path + "." + key, "超过 " + maxLen + " 个字符");
            return null;
        }
        return s;
    }

    /** 可选布尔：缺省为 {@code defaultValue}。 */
    static boolean optionalBool(Map<String, Object> map, String key, String path, boolean defaultValue, Errors errors) {
        Object v = map.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        errors.add(path + "." + key, "必须是 true / false");
        return defaultValue;
    }

    /** 顶层 {@code version} 必须是 1（以后改结构时升版本，加载端据此拒绝看不懂的格式）。 */
    static void requireVersion(Map<String, Object> root, Errors errors) {
        Object v = root.get("version");
        if (!(v instanceof Integer i) || i != 1) {
            errors.add("version", "必须是 1");
        }
    }

    // ---------------------------------------------------------------- 失败处理：停管道 + 告警，绝不抛

    /**
     * yaml 无效：{@link AnalyticsPipelineState#disable 停用管道}（原因 {@link Reason#CATALOG_INVALID}）、打 WARN、
     * 经 {@link OpsChannel#TECH} 告警（按 UTC 日去重）。告警本身失败只记一行，不向外抛。
     */
    static void reportInvalid(Logger log, AnalyticsPipelineState state, OpsAlertService alerts, Clock clock,
            String appEnv, String dedupePrefix, String fileLabel, String message) {
        state.disable(Reason.CATALOG_INVALID, message);
        log.warn("analytics pipeline disabled: reason={} file={} detail={}", Reason.CATALOG_INVALID, fileLabel, message);
        try {
            String day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
            alerts.send(OpsChannel.TECH, dedupePrefix + day,
                    "【埋点管道已停用】环境 " + appEnv + "\n"
                            + "原因：" + Reason.CATALOG_INVALID + "（" + fileLabel + " 无效）\n"
                            + "位置：" + message + "\n"
                            + "影响：业务不受影响；埋点接收返回 503、埋点定时任务跳过，期间 App 埋点留在本地队列。\n"
                            + "处理：修正 yaml（源文件在 petgo-backend/src/main/resources/analytics/）后重新部署。");
        } catch (RuntimeException ex) {
            log.warn("analytics catalog invalid alert failed: {}", ex.getClass().getSimpleName());
        }
    }
}
