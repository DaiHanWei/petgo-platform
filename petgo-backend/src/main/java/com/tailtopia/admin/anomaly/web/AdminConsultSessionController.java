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
            @RequestParam(value = "open", required = false) Long open,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        model.addAttribute("active", "consult-sessions");
        model.addAttribute("userId", userId);
        model.addAttribute("vetId", vetId);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        boolean searched = userId != null || vetId != null || from != null || to != null;
        model.addAttribute("searched", searched);
        // 🔴 一个条件都没填时**不查库**（V1.3.0 Story 9.2 起）：
        //    原先空条件会走 `cb.and(new Predicate[0])` —— 那是恒真式，等于把整张
        //    consult_sessions 摊平返回。这一页是按用户/兽医/时间取证的，不是会话总账；
        //    「打开即全量」既是无谓的全表扫描，也让一次误点就把全站会话元数据摆在屏幕上。
        //    空态文案（admin.v130.sessions.searchFirst）说的就是这个行为。
        // 日期按 UTC 日界换算：from 取当日 00:00、to 取次日 00:00（不含）。
        model.addAttribute("items", searched
                ? queryService.search(userId, vetId,
                        from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                        to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
                : java.util.List.of());
        model.addAttribute("open", open);
        return hxRequest != null
                ? "admin/fragments/consult-sessions-list :: rows(true)" : "admin/consult-sessions";
    }

    /**
     * 会话取证抽屉（V1.3.0 Story 9.2 · AC2）。
     *
     * <p>🔴 **只把列表那一行摊开**：会话元数据 + 评分，一个字段都不多。
     * 这里绝不新增任何读 IM 正文 / AI 分诊 / 用户媒体的查询 —— 抽屉是取证视图，不是聊天记录
     * （NFR5；产品曾提「查不到内容不如合并」，已拍板维持并保留页内说明）。
     *
     * <p>非 htmx 直达 → 回列表并自动开该抽屉（A4 异常工单的「去取证」就落在这条链上）。
     */
    @GetMapping("/admin/consult-sessions/{sessionId}/drawer")
    @PreAuthorize(VIEW_AUTH)
    public String drawer(@org.springframework.web.bind.annotation.PathVariable long sessionId,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        if (hxRequest == null) {
            return "redirect:/admin/consult-sessions?open=" + sessionId;
        }
        model.addAttribute("active", "consult-sessions");
        model.addAttribute("s", queryService.findMeta(sessionId)
                .orElseThrow(() -> com.tailtopia.shared.error.AppException.notFound("会话不存在")
                        .code("admin.err.sessions.notFound")));
        return "admin/fragments/drawer-consult-session :: drawer";
    }
}
