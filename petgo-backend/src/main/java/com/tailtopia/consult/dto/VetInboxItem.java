package com.tailtopia.consult.dto;

import com.tailtopia.consult.domain.ConsultSession;

/**
 * 兽医待接单列表项（Story 5.5）。AI_UPGRADE 携带 AI 上下文摘要；DIRECT 摘要为空。
 *
 * <p>宠物身份（{@code petName}/{@code petSpecies}/{@code petAgeMonths}/{@code ownerHandle}）经
 * service 跨模块只读端口富化（pet_profiles + 用户昵称）。<b>不含性别</b>——V1 建档不收集性别，
 * 前端工作台对其兜底隐藏（Jackson NON_NULL 省略 null）。
 */
public record VetInboxItem(
        long sessionId,
        String source,
        String aiDangerLevel,
        String symptomPreview,
        int imageCount,
        long waitingElapsedSeconds,
        String petName,
        String petSpecies,
        Integer petAgeMonths,
        String ownerHandle) {

    public static VetInboxItem of(ConsultSession s, String petName, String petSpecies,
            Integer petAgeMonths, String ownerHandle) {
        String preview = null;
        if (s.getAiSymptomText() != null) {
            String t = s.getAiSymptomText();
            preview = t.length() > 40 ? t.substring(0, 40) + "…" : t;
        }
        int imgCount = s.getAiImageRefs() == null ? 0 : s.getAiImageRefs().size();
        long elapsed = s.getWaitingStartedAt() == null
                ? 0L
                : Math.max(0L, (System.currentTimeMillis() - s.getWaitingStartedAt().toEpochMilli()) / 1000L);
        return new VetInboxItem(s.getId(), s.getSource().name(), s.getAiDangerLevel(), preview, imgCount, elapsed,
                petName, petSpecies, petAgeMonths, ownerHandle);
    }
}
