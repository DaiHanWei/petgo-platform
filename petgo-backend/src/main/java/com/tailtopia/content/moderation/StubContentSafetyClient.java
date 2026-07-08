package com.tailtopia.content.moderation;

import java.util.Locale;
import java.util.Map;

/**
 * 打桩内容安全客户端（内容审核 Story 1）。{@code app.moderation.mode=stub}（默认）装配，
 * 使评分 / 阈值 / fail-closed 降级 / 熔断状态机在<b>无凭证</b>下可 L0/L1 全分支验证（AC2–AC6）。
 *
 * <p>规则化打分：按文本 / URL 中的可测标记触发 PASS / RISKY / *_BLOCKED / 各降级分支。
 * 真实 green20220302 端到端评分 + 印尼语区分度属 L2（{@code AliyunContentSafetyClient}，需真实 AK）。
 * <ul>
 *   <li>文本含 {@code stub-timeout|stub-4xx|stub-5xx|stub-quota} → 抛对应 {@link ModerationDegradedException}</li>
 *   <li>文本含 {@code stub-high} → 高分 0.9（触发 RISKY）；否则低分 0.1（PASS）</li>
 *   <li>图 URL 含 {@code stub-img-timeout} 等 → 降级；含 {@code stub-porn|stub-violence|stub-contraband} → 高置信违规</li>
 * </ul>
 */
public class StubContentSafetyClient implements ContentSafetyClient {

    @Override
    public TextScore scanText(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        throwIfDegradeMarker(t);
        if (t.contains("stub-high")) {
            return new TextScore(0.9, "HARASSMENT");
        }
        return new TextScore(0.1, null);
    }

    @Override
    public ImageScore scanImage(String imageUrl) {
        String u = imageUrl == null ? "" : imageUrl.toLowerCase(Locale.ROOT);
        if (u.contains("stub-img-timeout")) {
            throw new ModerationDegradedException(DegradeReason.TIMEOUT, "stub image timeout");
        }
        throwIfDegradeMarker(u);
        // 遗留可测标记（沿用旧 stub 语义，保发布流程集成测试连续性）：URL 含 moderation-blocked 即高置信违规。
        if (u.contains("moderation-blocked") || u.contains("stub-porn")) {
            return new ImageScore(Map.of("porn", 0.95, "violence", 0.02, "contraband", 0.01));
        }
        if (u.contains("stub-violence")) {
            return new ImageScore(Map.of("porn", 0.02, "violence", 0.9, "contraband", 0.01));
        }
        if (u.contains("stub-contraband")) {
            return new ImageScore(Map.of("porn", 0.01, "violence", 0.02, "contraband", 0.9));
        }
        return new ImageScore(Map.of("porn", 0.01, "violence", 0.01, "contraband", 0.01));
    }

    private void throwIfDegradeMarker(String s) {
        if (s.contains("stub-timeout")) {
            throw new ModerationDegradedException(DegradeReason.TIMEOUT, "stub timeout");
        }
        if (s.contains("stub-4xx")) {
            throw new ModerationDegradedException(DegradeReason.HTTP_4XX, "stub 4xx");
        }
        if (s.contains("stub-5xx")) {
            throw new ModerationDegradedException(DegradeReason.HTTP_5XX, "stub 5xx");
        }
        if (s.contains("stub-quota")) {
            throw new ModerationDegradedException(DegradeReason.QUOTA, "stub quota");
        }
    }
}
