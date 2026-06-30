package com.tailtopia.admin.anomaly.web;

import com.tailtopia.consult.service.ConsultSessionAdminQueryService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 问诊会话元数据查询（Story 5.2，AB-4B）。SSR + HTMX，{@code /admin/consult-sessions}，不返 JSON。
 * **纯只读**：仅 GET、无写、无审计。门控 {@code consult.view_sessions}。
 * 仅展示会话元数据 + 评分（NFR5：绝不读 IM 正文/AI/媒体）。
 */
@Controller
public class AdminConsultSessionController {

    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('consult.view_sessions')";

    private final ConsultSessionAdminQueryService queryService;

    public AdminConsultSessionController(ConsultSessionAdminQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/admin/consult-sessions")
    @PreAuthorize(VIEW_AUTH)
    public String search(@RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "vetId", required = false) Long vetId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        model.addAttribute("active", "consult-sessions");
        model.addAttribute("userId", userId);
        model.addAttribute("vetId", vetId);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        boolean searched = userId != null || vetId != null || from != null || to != null;
        model.addAttribute("searched", searched);
        // 日期按 UTC 日界换算：from 取当日 00:00、to 取次日 00:00（不含）。
        model.addAttribute("items", queryService.search(userId, vetId,
                from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        return hxRequest != null ? "admin/consult-sessions :: rows" : "admin/consult-sessions";
    }
}
