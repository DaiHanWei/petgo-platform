package com.petgo.triage.dto;

import com.petgo.triage.domain.TriageTask;
import java.time.Instant;

/**
 * AI 分诊历史条目（Story 5.8，供问诊历史聚合经 service 接口跨模块传递，禁 consult 直读 triage repository）。
 */
public record TriageHistoryItem(long triageId, String dangerLevel, String symptomSummary, Instant date) {

    public static TriageHistoryItem of(TriageTask t) {
        String text = t.getSymptomText();
        String summary = text == null ? null : (text.length() > 40 ? text.substring(0, 40) + "…" : text);
        String level = t.getDangerLevel() == null ? null : t.getDangerLevel().name();
        return new TriageHistoryItem(t.getId(), level, summary, t.getCreatedAt());
    }
}
