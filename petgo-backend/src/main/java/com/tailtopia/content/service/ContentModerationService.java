package com.tailtopia.content.service;

import com.tailtopia.content.domain.ModerationKeywordRule;
import com.tailtopia.content.moderation.ContentSafetyClient;
import com.tailtopia.content.moderation.DegradeReason;
import com.tailtopia.content.moderation.ImageScore;
import com.tailtopia.content.moderation.KeywordClassification;
import com.tailtopia.content.moderation.KeywordRuleEngine;
import com.tailtopia.content.moderation.ModerationCircuitBreaker;
import com.tailtopia.content.moderation.ModerationDegradedException;
import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.content.moderation.ModerationProperties;
import com.tailtopia.content.moderation.StubContentSafetyClient;
import com.tailtopia.content.moderation.TextScore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 发布/内容自动审核门面（内容审核 Story 1，替换原 stub 内部为真实阿里云内容安全 + 分层词库 + fail-closed 降级）。
 *
 * <p>编排 {@link KeywordRuleEngine}（L1/L2/L3 分层词库，白名单优先）+ {@link ContentSafetyClient}
 * （阿里云 green20220302，stub/live 双模）+ {@link ModerationCircuitBreaker}（进程内熔断），输出
 * 0–1 风险评分与 {@link ModerationOutcome}。<b>禁引入 MQ/缓存/新中间件</b>（CLAUDE.md 护栏）。
 *
 * <p><b>兼容边界（§5.1）</b>：保留 {@link #moderate(String, List)} 兼容 shim —— 仅 L1 硬拦截返 *_BLOCKED，
 * 其余（含 RISKY/DEGRADED）返 PASS，令调用方 {@link ContentService} 零改动、publish 行为逐字节不变；
 * 评分/降级的实际路由由 story 2/3 采纳 {@link #evaluate(String, List)} 后生效。
 *
 * <p><b>核心不变量（§5.5）</b>：{@code evaluate} 在任何三方异常下绝不返回 PASS —— 统一映射 {@code DEGRADED}。
 */
@Service
public class ContentModerationService {

    private static final Logger log = LoggerFactory.getLogger(ContentModerationService.class);

    /**
     * 审核结论（§5.1 向后兼容扩展：追加 {@code RISKY}/{@code DEGRADED}，不改既有名/序）。
     * 既有调用方 switch 带 default 分支，追加值不破坏编译；shim 从不向旧调用方返回新值。
     */
    public enum Verdict {
        PASS,
        TEXT_BLOCKED,
        IMAGE_BLOCKED,
        RISKY,
        DEGRADED
    }

    private final KeywordRuleEngine keywordEngine;
    private final ContentSafetyClient safetyClient;
    private final ModerationProperties props;
    private final ModerationCircuitBreaker circuitBreaker;

    public ContentModerationService(KeywordRuleEngine keywordEngine, ContentSafetyClient safetyClient,
            ModerationProperties props, ModerationCircuitBreaker circuitBreaker) {
        this.keywordEngine = keywordEngine;
        this.safetyClient = safetyClient;
        this.props = props;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * 便捷构造（<b>仅测试用</b>）：装配默认 stub 客户端 + 内存兜底词库（沿用旧 stub 关键词，保发布流程测试连续性）
     * + 默认阈值 + 新熔断器。生产一律走上方 DI 构造（真实词库从库加载）。
     */
    public ContentModerationService() {
        this(defaultEngine(), new StubContentSafetyClient(), new ModerationProperties(),
                new ModerationCircuitBreaker());
    }

    private static KeywordRuleEngine defaultEngine() {
        KeywordRuleEngine engine = new KeywordRuleEngine(null);
        engine.apply(List.of(
                // 旧 stub 关键词兜底（无 DB 时的最小 L1 黑名单）。
                ModerationKeywordRule.of("L1_BLOCK", "SUBSTRING", "judi", "GAMBLING", "id", true),
                ModerationKeywordRule.of("L1_BLOCK", "SUBSTRING", "gambling", "GAMBLING", "en", true),
                ModerationKeywordRule.of("L1_BLOCK", "SUBSTRING", "narkoba", "DRUGS", "id", true),
                ModerationKeywordRule.of("L1_BLOCK", "SUBSTRING", "pornografi", "PORN", "id", true),
                ModerationKeywordRule.of("L1_BLOCK", "SUBSTRING", "penipuan", "AD_SPAM", "id", true),
                ModerationKeywordRule.of("L1_BLOCK", "SUBSTRING", "scam", "AD_SPAM", "en", true),
                // L3 宠物白名单（优先于黑名单）。
                ModerationKeywordRule.of("L3_WHITELIST", "EXACT", "anjing", "PET_SAFE", "id", true)));
        return engine;
    }

    /**
     * 兼容 shim（§5.1）。仅 L1 硬拦截返 *_BLOCKED；RISKY/DEGRADED/PASS 一律返 PASS（publish 行为不变）。
     *
     * @param text      正文（可空）
     * @param imageUrls 公开桶图 URL 列表（可空）
     */
    public Verdict moderate(String text, List<String> imageUrls) {
        Verdict v = evaluate(text, imageUrls).verdict();
        return switch (v) {
            case TEXT_BLOCKED -> Verdict.TEXT_BLOCKED;
            case IMAGE_BLOCKED -> Verdict.IMAGE_BLOCKED;
            default -> Verdict.PASS; // PASS / RISKY / DEGRADED → 过渡态放行（story 2/3 采纳 evaluate 后生效）
        };
    }

    /**
     * 评论文字审核（内容审核补充规范 story 3，§5.1）。纯文字、无图审——内部复用 {@link #evaluate(String, List)}
     * （传空图列表）并映射为 {@link CommentVerdict} 四态：
     * <ul>
     *   <li>{@code TEXT_BLOCKED} → {@link CommentVerdict#L1_BLOCKED}（命中 L1 硬拦截词库）</li>
     *   <li>{@code RISKY}（评分 ≥0.8）→ {@link CommentVerdict#HIGH_RISK}</li>
     *   <li>{@code DEGRADED} 或 {@code degraded()} → {@link CommentVerdict#DEGRADED}（fail-closed，绝不放行）</li>
     *   <li>其余（{@code PASS}，纯文字下 {@code IMAGE_BLOCKED} 不会出现）→ {@link CommentVerdict#PASS}</li>
     * </ul>
     * 真实三方接入仅替换 {@code evaluate} 内部，本方法契约（四态）不变。
     */
    public CommentVerdict moderateComment(String text) {
        ModerationOutcome outcome = evaluate(text, List.of());
        if (outcome.degraded() || outcome.verdict() == Verdict.DEGRADED) {
            return CommentVerdict.DEGRADED; // fail-closed 优先
        }
        return switch (outcome.verdict()) {
            case TEXT_BLOCKED -> CommentVerdict.L1_BLOCKED;
            case RISKY -> CommentVerdict.HIGH_RISK;
            default -> CommentVerdict.PASS; // PASS（IMAGE_BLOCKED 对纯文字不可达）
        };
    }

    /**
     * 富审核（§5.2）。判定顺序：L1 词库硬拦截 → 图像高置信违规 → 三方评分（+L2 加权）RISKY/PASS；
     * 任何三方降级 → DEGRADED（fail-closed，绝不 PASS）。方法无状态、可重入（供 story 4/5 复用）。
     */
    public ModerationOutcome evaluate(String text, List<String> imageUrls) {
        int textLen = text == null ? 0 : text.length();
        int imgCount = imageUrls == null ? 0 : imageUrls.size();

        // 1) L1 词库硬拦截（不打三方；白名单优先豁免在引擎内处理）。
        KeywordClassification kc = keywordEngine.classify(text);
        if (kc.l1Blocked()) {
            ModerationOutcome outcome = ModerationOutcome.textBlocked(kc.l1Category());
            logDecision(outcome, textLen, imgCount);
            return outcome;
        }

        // 2) 文本三方评分（fail-closed）。
        double textRisk = 0.0;
        String textLabel = null;
        if (text != null && !text.isBlank()) {
            try {
                TextScore ts = guardedScanText(text);
                textRisk = ts.riskScore();
                textLabel = ts.topLabel();
            } catch (ModerationDegradedException e) {
                return degraded(e, textLen, imgCount);
            }
        }

        // 3) 图像三方审核（高置信违规 → 硬拦截；降级 → DEGRADED）。
        if (imageUrls != null) {
            for (String url : imageUrls) {
                if (url == null || url.isBlank()) {
                    continue;
                }
                try {
                    ImageScore is = guardedScanImage(url);
                    String cat = blockedImageCategory(is);
                    if (cat != null) {
                        ModerationOutcome outcome = ModerationOutcome.imageBlocked(cat);
                        logDecision(outcome, textLen, imgCount);
                        return outcome;
                    }
                } catch (ModerationDegradedException e) {
                    return degraded(e, textLen, imgCount);
                }
            }
        }

        // 4) 合并评分（三方文本分 + L2 词库加权），阈值裁 RISKY/PASS。
        double risk = Math.min(1.0, textRisk + kc.l2Weight());
        String topCategory = textLabel != null ? textLabel : kc.l2Category();
        ModerationOutcome outcome = risk >= props.getRiskThreshold()
                ? ModerationOutcome.risky(risk, topCategory)
                : ModerationOutcome.pass(risk, topCategory);
        logDecision(outcome, textLen, imgCount);
        return outcome;
    }

    // --- 三方调用封装（熔断 + 降级映射） ---

    private TextScore guardedScanText(String text) {
        if (!circuitBreaker.allowRequest()) {
            throw new ModerationDegradedException(DegradeReason.CIRCUIT_OPEN, "circuit open (text)");
        }
        try {
            TextScore ts = safetyClient.scanText(text);
            circuitBreaker.recordSuccess();
            return ts;
        } catch (ModerationDegradedException e) {
            circuitBreaker.recordFailure();
            throw e;
        } catch (RuntimeException e) {
            // 兜底：任何未归类的三方异常也 fail-closed，绝不 PASS（不外泄原文/堆栈）。
            circuitBreaker.recordFailure();
            throw new ModerationDegradedException(DegradeReason.HTTP_5XX, "text scan failed");
        }
    }

    private ImageScore guardedScanImage(String url) {
        if (!circuitBreaker.allowRequest()) {
            throw new ModerationDegradedException(DegradeReason.CIRCUIT_OPEN, "circuit open (image)");
        }
        try {
            ImageScore is = safetyClient.scanImage(url);
            circuitBreaker.recordSuccess();
            return is;
        } catch (ModerationDegradedException e) {
            circuitBreaker.recordFailure();
            throw e;
        } catch (RuntimeException e) {
            circuitBreaker.recordFailure();
            throw new ModerationDegradedException(DegradeReason.HTTP_5XX, "image scan failed");
        }
    }

    /** §4.2 图像分类阈值：色情 ≥0.85 / 暴力 ≥0.80 / 违禁品 ≥0.75。返回命中类别，无则 null。 */
    private String blockedImageCategory(ImageScore is) {
        if (is.confidence("porn") >= props.getImageThreshold().getPorn()) {
            return "PORN";
        }
        if (is.confidence("violence") >= props.getImageThreshold().getViolence()) {
            return "VIOLENCE";
        }
        if (is.confidence("contraband") >= props.getImageThreshold().getContraband()) {
            return "CONTRABAND";
        }
        return null;
    }

    private ModerationOutcome degraded(ModerationDegradedException e, int textLen, int imgCount) {
        ModerationOutcome outcome = ModerationOutcome.degraded(e.reason());
        // 降级/配额告警（护栏 §5.7）：仅记 reason + 内容尺寸，不记原文/图 URL/AK/堆栈。
        log.warn("moderation degraded: reason={} textLen={} imgCount={}", e.reason(), textLen, imgCount);
        return outcome;
    }

    /** 决策日志（护栏 §5.7）：仅 verdict/score/category/reason + 内容尺寸，严禁原文/图 URL/AK。 */
    private void logDecision(ModerationOutcome outcome, int textLen, int imgCount) {
        log.info("moderation decision: verdict={} score={} category={} degradeReason={} textLen={} imgCount={}",
                outcome.verdict(), outcome.riskScore(), outcome.topCategory(),
                outcome.degradeReason(), textLen, imgCount);
    }
}
