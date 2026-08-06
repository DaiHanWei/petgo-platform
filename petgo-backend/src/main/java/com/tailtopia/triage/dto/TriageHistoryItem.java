package com.tailtopia.triage.dto;

import com.tailtopia.triage.domain.TriageTask;
import java.time.Instant;

/**
 * AI 分诊历史条目（Story 5.8，供问诊历史聚合经 service 接口跨模块传递，禁 consult 直读 triage repository）。
 */
public record TriageHistoryItem(long triageId, String dangerLevel, String symptomSummary, Instant date) {

    public static TriageHistoryItem of(TriageTask t) {
        // bug 20260730-437：不再 40 字符硬截——快照页靠本字段回显症状全文（结果接口不回传症状），
        // 列表 UI 自带 maxLines 省略，服务端截断只会让详情页无全文可展。
        String level = t.getDangerLevel() == null ? null : t.getDangerLevel().name();
        return new TriageHistoryItem(t.getId(), level, t.getSymptomText(), t.getCreatedAt());
    }
}
