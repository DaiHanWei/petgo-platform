package com.petgo.content.service;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 发布时三方自动审核（Story 2.3 R2 · F10，⚠️ 反转 F1「人工审核、不做关键词过滤」）。
 *
 * <p>发布写库**前**近实时审核：文字关键词过滤 + 图像识别。任一拦截即发布失败、**不落库**、
 * 停留编辑页（改后可重提，前端复用内存草稿），**不进人工队列**。
 *
 * <p><b>V1 占位（stub）</b>：文字走应用内关键词规则；图像识别为接口占位（默认放行 + 可触发的规则
 * 占位，真实三方接口后接，假设 A-6）。<b>禁引入 MQ/缓存/新中间件</b>（CLAUDE.md 护栏）——
 * stub 为纯应用内同步实现。真实接入时仅替换本类内部，调用方（{@link ContentService}）不变。
 */
@Service
public class ContentModerationService {

    /** 审核结论。文字与图片均 PASS 才允许发布。 */
    public enum Verdict {
        PASS,
        TEXT_BLOCKED,
        IMAGE_BLOCKED
    }

    /**
     * 文字关键词黑名单（V1 stub，大小写不敏感子串匹配）。真实接入时由三方文本审核替换。
     * 产品语言为印尼语 + 英语，故占位词取印尼语/英语（赌博/毒品/色情/诈骗类）；仅演示用，非真实敏感词库。
     */
    private static final List<String> BLOCKED_KEYWORDS = List.of(
            "judi", "narkoba", "pornografi", "penipuan", "gambling", "scam");

    /**
     * 图像违规标记（V1 stub）：真实三方图像识别接入前的可测占位——图 URL 含此标记即判违规，
     * 否则一律放行。让审核流程可端到端真跑、拦截路径可被测试触发。
     */
    private static final String IMAGE_VIOLATION_MARKER = "moderation-blocked";

    /**
     * 审核一条发布。文字优先，文字过再审图像（与 AC8「文字与图片均过才发布」一致）。
     *
     * @param text      正文（可空）
     * @param imageUrls 公开桶图 URL 列表（可空）
     * @return {@link Verdict}
     */
    public Verdict moderate(String text, List<String> imageUrls) {
        if (text != null && containsBlockedKeyword(text)) {
            return Verdict.TEXT_BLOCKED;
        }
        if (imageUrls != null && imageUrls.stream().anyMatch(this::imageViolates)) {
            return Verdict.IMAGE_BLOCKED;
        }
        return Verdict.PASS;
    }

    private boolean containsBlockedKeyword(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return BLOCKED_KEYWORDS.stream().anyMatch(k -> lower.contains(k.toLowerCase(Locale.ROOT)));
    }

    private boolean imageViolates(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).contains(IMAGE_VIOLATION_MARKER);
    }
}
