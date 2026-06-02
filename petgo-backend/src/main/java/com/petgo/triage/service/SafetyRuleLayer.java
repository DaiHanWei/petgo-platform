package com.petgo.triage.service;

import com.petgo.triage.domain.DangerLevel;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * 确定性安全规则层 —— 高危强制升红（Story 4.2，NFR-6 不可协商地基本体）。
 *
 * <p>在 Gemini 返回结果【之后】做后置确定性校验：依据 {@code resources/safety/high_risk_symptoms.yml}
 * （兽医顾问维护）匹配「症状原文 + Gemini 解析文本」双源，命中任一【未被否定】的高危信号 → 强制升红
 * RED。<b>纯确定性、无副作用、不调 Gemini、不查 DB</b>。
 *
 * <p>🔒 不可协商红线：
 * <ul>
 *   <li><b>只升不降</b>：{@code final = max(model, rule)}（GREEN&lt;YELLOW&lt;RED），永不下调。</li>
 *   <li><b>独立于 Gemini</b>：即使模型假阴性给绿/黄，命中清单也必落 RED。</li>
 *   <li><b>fail-fast</b>：清单缺失 / 为空 / 结构非法 → 启动失败（安全层失守宁可不上线）。</li>
 *   <li><b>清单不硬编码</b>：规则全部从 yml 读取，兽医顾问改文件即可增删条目。</li>
 *   <li>审计只落「是否升红 + 命中规则 id」，<b>不落症状健康数据</b>。</li>
 * </ul>
 *
 * <p>E5 保守否定：仅当高危信号被【直接紧邻否定】且【不跨句】时抑制该次命中；只要全文存在任一未被否定
 * 的命中即升红——否定逻辑绝不漏掉真实急症。
 */
@Component
public class SafetyRuleLayer {

    private static final Logger log = LoggerFactory.getLogger(SafetyRuleLayer.class);
    private static final String RESOURCE = "safety/high_risk_symptoms.yml";

    /** 单条高危急症：稳定 id + 归一化后的匹配信号。 */
    private record Emergency(String id, List<String> signals) {
    }

    private List<Emergency> emergencies = List.of();
    private List<String> cjkNegations = List.of();
    private List<String> latinNegations = List.of();
    private String sentenceBoundaries = "。！？!?.;；,，、\n";
    /** 否定词与高危信号之间允许的最大字符间隔（「紧邻」语义，刻意取小，安全偏置）。 */
    private int negationGap = 3;

    @PostConstruct
    void load() {
        Map<String, Object> root;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw failFast("高危急症清单缺失：" + RESOURCE);
            }
            root = new Yaml().load(in);
        } catch (java.io.IOException e) {
            throw failFast("高危急症清单读取失败：" + RESOURCE);
        }
        applyConfig(root);
    }

    /** 校验并应用清单结构（拆出供 L0 fail-fast 单测）。结构非法即抛 → 启动失败。 */
    @SuppressWarnings("unchecked")
    void applyConfig(Map<String, Object> root) {
        if (root == null) {
            throw failFast("高危急症清单为空：" + RESOURCE);
        }

        Object gap = root.get("negation_gap");
        if (gap instanceof Number n) {
            this.negationGap = n.intValue();
        }
        Object boundaries = root.get("sentence_boundaries");
        if (boundaries instanceof String s && !s.isEmpty()) {
            this.sentenceBoundaries = s;
        }
        loadNegations((Map<String, Object>) root.get("negations"));

        Object rawList = root.get("emergencies");
        if (!(rawList instanceof List<?> list) || list.isEmpty()) {
            throw failFast("高危急症清单 emergencies 缺失或为空");
        }
        List<Emergency> parsed = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                throw failFast("高危急症条目结构非法");
            }
            Object id = m.get("id");
            Object signals = m.get("signals");
            if (!(id instanceof String sid) || sid.isBlank()) {
                throw failFast("高危急症条目缺少 id");
            }
            if (!(signals instanceof List<?> sigList) || sigList.isEmpty()) {
                throw failFast("高危急症条目 " + sid + " 缺少 signals");
            }
            List<String> norm = new ArrayList<>();
            for (Object sig : sigList) {
                String n = normalize(String.valueOf(sig));
                if (!n.isBlank()) {
                    norm.add(n);
                }
            }
            if (norm.isEmpty()) {
                throw failFast("高危急症条目 " + sid + " signals 归一化后为空");
            }
            parsed.add(new Emergency(sid, norm));
        }
        this.emergencies = List.copyOf(parsed);
        log.info("安全规则层加载完成 emergencies={} negations(cjk/latin)={}/{}",
                emergencies.size(), cjkNegations.size(), latinNegations.size());
    }

    @SuppressWarnings("unchecked")
    private void loadNegations(Map<String, Object> negs) {
        List<String> cjk = new ArrayList<>();
        List<String> latin = new ArrayList<>();
        if (negs != null) {
            for (Object group : negs.values()) {
                if (group instanceof List<?> tokens) {
                    for (Object t : tokens) {
                        String n = normalize(String.valueOf(t));
                        if (n.isBlank()) {
                            continue;
                        }
                        if (n.chars().allMatch(c -> c >= 'a' && c <= 'z')) {
                            latin.add(n);
                        } else {
                            cjk.add(n);
                        }
                    }
                }
            }
        }
        this.cjkNegations = List.copyOf(cjk);
        this.latinNegations = List.copyOf(latin);
    }

    /**
     * 后置裁决最终危险级别（AC1/AC2）。命中高危清单 → 强制 RED；否则保留模型级别（只升不降）。
     *
     * @param modelLevel  模型解析级别（可空：模型异常/缺级别 → 视为 GREEN 再过规则层，不漏兜）
     * @param textSources 匹配信号源（症状原文 + Gemini 解析文本，双源）
     */
    public SafetyDecision enforce(DangerLevel modelLevel, String... textSources) {
        DangerLevel base = modelLevel == null ? DangerLevel.GREEN : modelLevel;
        String combined = combine(textSources);
        List<String> matched = match(combined);

        if (matched.isEmpty()) {
            return new SafetyDecision(base, false, List.of());
        }
        DangerLevel finalLevel = DangerLevel.RED.atLeast(base); // 只升不降：恒为 RED
        boolean escalated = base != DangerLevel.RED; // 模型本已红则非规则升级
        return new SafetyDecision(finalLevel, escalated, matched);
    }

    /** 返回命中（存在未被否定信号）的急症 id 列表。 */
    private List<String> match(String text) {
        List<String> matched = new ArrayList<>();
        for (Emergency e : emergencies) {
            if (hasUnnegatedHit(text, e.signals())) {
                matched.add(e.id());
            }
        }
        return matched;
    }

    private boolean hasUnnegatedHit(String text, List<String> signals) {
        for (String sig : signals) {
            int from = 0;
            while (true) {
                int idx = text.indexOf(sig, from);
                if (idx < 0) {
                    break;
                }
                if (!isNegated(text, idx)) {
                    return true; // 任一未被否定的命中即足以升红
                }
                from = idx + sig.length();
            }
        }
        return false;
    }

    /**
     * 该信号出现位置是否被【紧邻否定】：否定词与该信号间隔 ≤ negationGap 且【同一子句】。
     *
     * <p>「紧邻」语义防止否定越过中间内容误抑制远处真实急症（「没有发烧今天呕吐带血」中
     * 「没有」不否定「呕吐带血」；「没有呼吸困难，但呕吐带血」逗号断句后亦不否定）。
     */
    private boolean isNegated(String text, int idx) {
        // 子句起点：idx 前最后一个句界之后。
        int clauseStart = 0;
        for (int i = idx - 1; i >= 0; i--) {
            if (sentenceBoundaries.indexOf(text.charAt(i)) >= 0) {
                clauseStart = i + 1;
                break;
            }
        }
        String clause = text.substring(clauseStart, idx); // 信号紧接 clause 之后
        int len = clause.length();
        for (String neg : cjkNegations) {
            int p = clause.lastIndexOf(neg);
            while (p >= 0) {
                if (len - (p + neg.length()) <= negationGap) {
                    return true; // 间隔够近 → 紧邻否定
                }
                p = clause.lastIndexOf(neg, p - 1);
            }
        }
        for (String neg : latinNegations) {
            if (hasWordWithinGap(clause, neg)) {
                return true;
            }
        }
        return false;
    }

    /** 拉丁否定整词匹配（两侧非字母，避免 "no" 误中 "now/nose"），且词尾距子句末 ≤ negationGap。 */
    private boolean hasWordWithinGap(String clause, String word) {
        int from = 0;
        while (true) {
            int idx = clause.indexOf(word, from);
            if (idx < 0) {
                return false;
            }
            boolean leftOk = idx == 0 || !isLetter(clause.charAt(idx - 1));
            int end = idx + word.length();
            boolean rightOk = end >= clause.length() || !isLetter(clause.charAt(end));
            if (leftOk && rightOk && clause.length() - end <= negationGap) {
                return true;
            }
            from = idx + word.length();
        }
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static String combine(String... sources) {
        StringBuilder sb = new StringBuilder();
        if (sources != null) {
            for (String s : sources) {
                if (s != null && !s.isEmpty()) {
                    // 用句界「。」分隔双源：normalize 会把空白折成空格（'\n' 会失去句界语义），
                    // 句号能存活，确保否定不跨源泄漏（症状里的「没有」不否定解析文本里的高危词）。
                    sb.append(s).append('。');
                }
            }
        }
        return normalize(sb.toString());
    }

    /** 归一化：小写 + 折叠空白（中文无大小写，折叠不影响子串语义）。 */
    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static IllegalStateException failFast(String msg) {
        return new IllegalStateException(
                "安全规则层（NFR-6）启动校验失败，拒绝启动：" + msg);
    }
}
