package com.tailtopia.admin.dailyreport;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日报手动触发（管理端验证用）：{@code POST /admin/daily-report/push} 立即按「昨天」推一次。
 * 失败回 ProblemDetail（含原因，如 webhook 未配置 / 飞书返回非 0 code）。超管专属。
 */
@RestController
public class AdminDailyReportController {

    public static final String AUTH = "hasRole('SUPER_ADMIN')";

    private final DailyReportService service;

    public AdminDailyReportController(DailyReportService service) {
        this.service = service;
    }

    @PostMapping("/admin/daily-report/push")
    @PreAuthorize(AUTH)
    public Map<String, Object> push() {
        DailyReport r = service.pushNow();
        return Map.of("pushed", true, "date", r.date().toString());
    }
}
