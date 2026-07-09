package com.tailtopia.content.moderation;

import com.tailtopia.content.domain.ModerationKeywordRule;
import com.tailtopia.content.repository.ModerationKeywordRuleRepository;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 分层词库匹配引擎（内容审核 Story 1 · 方案 §9 / §5.4）。词库从 DB（{@code moderation_keyword_rules}）
 * 加载进程内 volatile 缓存（{@code @PostConstruct} 载入 + {@link #refresh()} 手动刷新），<b>不引入缓存中间件</b>（护栏）。
 *
 * <p>{@link #classify(String)} 顺序：归一小写 → <b>L3 白名单优先豁免</b> → L1 黑名单命中且未豁免 = 硬拦截
 * → L2 中风险 = 评分加权。白名单优先级最高（§9.3）：命中白名单的词段不因其外观触发 L1。
 *
 * <p>L0 可注入内存词库（{@link #apply(List)}）在无 DB 下验证顺序与豁免（AC4）；真 DB 加载属 L1。
 */
@Component
public class KeywordRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(KeywordRuleEngine.class);

    /** 单个 L2 命中的加权（并入三方评分，多命中累加后由门面 min(1.0) 截断）。 */
    static final double L2_WEIGHT_PER_HIT = 0.45;

    private final ModerationKeywordRuleRepository repository;
    private volatile CompiledRules cache = CompiledRules.EMPTY;

    /** 生产装配：注入仓库，{@code @PostConstruct} 载入。 */
    public KeywordRuleEngine(ModerationKeywordRuleRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    /** 从库重载启用规则至进程内缓存（运营改词库后调用即可热更）。 */
    public void refresh() {
        if (repository == null) {
            return;
        }
        try {
            apply(repository.findByEnabledTrue());
        } catch (RuntimeException e) {
            // 词库加载失败不应拖垮启动；保留旧缓存并告警（不记敏感内容）。
            log.warn("moderation keyword rules refresh failed, keeping previous cache: {}", e.toString());
        }
    }

    /** 以给定规则集重建缓存（L0 测试可直接注入内存词库）。 */
    public void apply(List<ModerationKeywordRule> rules) {
        List<CompiledRule> l1 = new ArrayList<>();
        List<CompiledRule> l2 = new ArrayList<>();
        List<CompiledRule> l3 = new ArrayList<>();
        for (ModerationKeywordRule r : rules) {
            if (!r.isEnabled()) {
                continue;
            }
            CompiledRule cr = CompiledRule.of(r);
            if (cr == null) {
                continue;
            }
            switch (r.getRuleKind()) {
                case "L1_BLOCK" -> l1.add(cr);
                case "L2_ADJUSTABLE" -> l2.add(cr);
                case "L3_WHITELIST" -> l3.add(cr);
                default -> { /* 未知层，忽略 */ }
            }
        }
        this.cache = new CompiledRules(List.copyOf(l1), List.copyOf(l2), List.copyOf(l3));
    }

    /**
     * 分类文本。§5.4 顺序：L3 白名单豁免 → L1 硬拦截 → L2 加权。
     */
    public KeywordClassification classify(String text) {
        if (text == null || text.isBlank()) {
            return KeywordClassification.NONE;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        CompiledRules rules = cache;

        // 1) L3 白名单：收集命中的白名单词段（用于豁免 L1）。
        List<String> whitelisted = new ArrayList<>();
        for (CompiledRule w : rules.l3()) {
            if (w.matches(lower)) {
                whitelisted.add(w.patternLower());
            }
        }

        // 2) L1 黑名单：命中且未被白名单豁免 → 硬拦截（首个命中即返回）。
        for (CompiledRule b : rules.l1()) {
            if (b.matches(lower) && !exemptedByWhitelist(b, whitelisted)) {
                return new KeywordClassification(true, b.category(), 0.0, null);
            }
        }

        // 3) L2 中风险：累加加权（非硬拦截）。
        double weight = 0.0;
        String l2Category = null;
        for (CompiledRule m : rules.l2()) {
            if (m.matches(lower)) {
                weight += L2_WEIGHT_PER_HIT;
                if (l2Category == null) {
                    l2Category = m.category();
                }
            }
        }
        return new KeywordClassification(false, null, Math.min(1.0, weight), l2Category);
    }

    /** L1 命中被白名单豁免：存在白名单词段覆盖该 L1 词（§9.3 anjing=狗 语境豁免）。 */
    private boolean exemptedByWhitelist(CompiledRule l1Rule, List<String> whitelisted) {
        if (whitelisted.isEmpty() || l1Rule.patternLower() == null) {
            return false;
        }
        String p = l1Rule.patternLower();
        for (String w : whitelisted) {
            if (w.contains(p) || p.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** 编译后的规则（大小写已归一 / 正则预编译）。 */
    private record CompiledRule(String matchType, String patternLower, Pattern regex, String category) {

        static CompiledRule of(ModerationKeywordRule r) {
            String type = r.getMatchType() == null ? "SUBSTRING" : r.getMatchType();
            String pat = r.getPattern() == null ? "" : r.getPattern();
            String patLower = pat.toLowerCase(Locale.ROOT);
            if ("REGEX".equals(type)) {
                try {
                    Pattern compiled = Pattern.compile(pat, Pattern.CASE_INSENSITIVE);
                    return new CompiledRule(type, patLower, compiled, r.getCategory());
                } catch (PatternSyntaxException e) {
                    // 非法正则跳过（不入库层已应校验），避免污染整批加载。
                    log.warn("skip invalid moderation regex rule id={}: {}", r.getId(), e.getMessage());
                    return null;
                }
            }
            return new CompiledRule(type, patLower, null, r.getCategory());
        }

        boolean matches(String lowerText) {
            return switch (matchType) {
                case "REGEX" -> regex != null && regex.matcher(lowerText).find();
                case "EXACT" -> containsToken(lowerText, patternLower);
                default -> lowerText.contains(patternLower); // SUBSTRING
            };
        }

        /** EXACT：按词元（非字母数字为界）整词匹配，避免 anjing 命中 anjingan。 */
        private static boolean containsToken(String lowerText, String token) {
            if (token.isEmpty()) {
                return false;
            }
            for (String t : lowerText.split("[^\\p{L}\\p{N}]+")) {
                if (t.equals(token)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record CompiledRules(List<CompiledRule> l1, List<CompiledRule> l2, List<CompiledRule> l3) {
        static final CompiledRules EMPTY = new CompiledRules(List.of(), List.of(), List.of());
    }
}
