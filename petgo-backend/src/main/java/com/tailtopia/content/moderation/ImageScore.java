package com.tailtopia.content.moderation;

import java.util.Map;

/**
 * 图像审核评分：分类标签 → 置信度（0–1）。分类如 {@code porn}/{@code violence}/{@code contraband}，
 * 由 {@code ContentModerationService} 按 §4.2 阈值判定是否高置信违规（IMAGE_BLOCKED）。
 */
public record ImageScore(Map<String, Double> labelConfidence) {

    public ImageScore {
        labelConfidence = labelConfidence == null ? Map.of() : Map.copyOf(labelConfidence);
    }

    /** 某分类置信度，缺省 0。 */
    public double confidence(String label) {
        return labelConfidence.getOrDefault(label, 0.0);
    }
}
