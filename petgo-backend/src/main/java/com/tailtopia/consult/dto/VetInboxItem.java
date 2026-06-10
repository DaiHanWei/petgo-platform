package com.tailtopia.consult.dto;

import com.tailtopia.consult.domain.ConsultSession;

/**
 * 兽医待接单列表项（Story 5.5）。AI_UPGRADE 携带 AI 上下文摘要；DIRECT 摘要为空。
 */
public record VetInboxItem(
        long sessionId,
        String source,
        String aiDangerLevel,
        String symptomPreview,
        int imageCount,
        long waitingElapsedSeconds) {

    public static VetInboxItem of(ConsultSession s) {
        String preview = null;
        if (s.getAiSymptomText() != null) {
            String t = s.getAiSymptomText();
            preview = t.length() > 40 ? t.substring(0, 40) + "…" : t;
        }
        int imgCount = s.getAiImageRefs() == null ? 0 : s.getAiImageRefs().size();
        long elapsed = s.getWaitingStartedAt() == null
                ? 0L
                : Math.max(0L, (System.currentTimeMillis() - s.getWaitingStartedAt().toEpochMilli()) / 1000L);
        return new VetInboxItem(s.getId(), s.getSource().name(), s.getAiDangerLevel(), preview, imgCount, elapsed);
    }
}
