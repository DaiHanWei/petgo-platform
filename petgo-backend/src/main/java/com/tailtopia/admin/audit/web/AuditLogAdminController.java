package com.tailtopia.admin.audit.web;

import com.tailtopia.admin.audit.domain.AdminAuditLog;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditChainVerifier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 审计日志查询页（Story 1.3，AC5/AC6）。SSR + HTMX，路由 {@code /admin/audit-logs}（不返 JSON）。
 *
 * <p>门控：{@code @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('admin.view_logs')")}——
 * 无此权限的后台账号 → 403（AccessDenied）。STAFF 的 {@code admin.view_logs} authority 由 Story 1.5 装载，
 * 1.5 落地前实际仅超管命中（前向兼容、无前向依赖）。
 *
 * <p>筛选按日期范围 [from, to] / 操作人账号 id / 操作类型，结果按 {@code createdAt} 倒序分页（永久保留）。
 * 依 {@code HX-Request} 头区分：HTMX 局部刷新返结果片段，直接访问返完整视图。
 */
@Controller
public class AuditLogAdminController {

    private static final int PAGE_SIZE = 50;

    /** 筛选下拉的操作类型候选（含本故事 + Epic 2~6 预留常量）。 */
    private static final List<String> ACTION_TYPES = List.of(
            AuditActions.EMERGENCY_LOGIN_SUCCEEDED,
            AuditActions.ACCOUNT_CREATED,
            AuditActions.PERMISSION_GRANTED,
            AuditActions.VET_BANNED,
            AuditActions.VET_UNBANNED,
            AuditActions.CONTENT_TAKEN_DOWN,
            AuditActions.USER_DEACTIVATED);

    private final AdminAuditService auditService;

    /** 哈希链校验（V1.3.0 Story 6.5 AC1 页头徽标；既有 verifier，本 story 不改其算法）。 */
    private final AuditChainVerifier chainVerifier;

    public AuditLogAdminController(AdminAuditService auditService, AuditChainVerifier chainVerifier) {
        this.auditService = auditService;
        this.chainVerifier = chainVerifier;
    }

    /** 徽标复算的窗口大小：审计表 append-only 永久保留，每开一次页全表重算哈希不是页面该干的事（story T1 给的退路）。 */
    private static final int CHAIN_WINDOW = 500;

    /**
     * 哈希链状态徽标（Story 6.5）：页面载入后 htmx 异步取，只复算<b>最近 {@value #CHAIN_WINDOW} 行</b>
     * （文案里如实写「已校验 N 行」）；全链结论走 {@code verifyAll()} 的运维核验路径。非 htmx 直达回整页。
     * 查看权限与列表同码。
     */
    @GetMapping("/admin/audit-logs/chain-status")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('admin.view_logs')")
    public String chainStatus(@RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        if (hxRequest == null) {
            return "redirect:/admin/audit-logs";
        }
        model.addAttribute("chain", chainVerifier.verifyRecent(CHAIN_WINDOW));
        return "admin/audit-logs :: chainStatus";
    }

    @GetMapping("/admin/audit-logs")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('admin.view_logs')")
    public String auditLogs(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "actor", required = false) Long actor,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest,
            Model model) {

        // 日期 → UTC 半开区间 [from 00:00, to+1 00:00)，使「到某日」含当日全天。
        Instant fromInstant = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String actionFilter = (action == null || action.isBlank()) ? null : action;
        int pageIndex = Math.max(page, 0);

        Page<AdminAuditLog> result = auditService.search(
                fromInstant, toInstant, actor, actionFilter, PageRequest.of(pageIndex, PAGE_SIZE));

        model.addAttribute("active", "audit-logs");
        model.addAttribute("result", result);
        model.addAttribute("actionTypes", ACTION_TYPES);
        // 回显筛选条件（含分页链接复用）。
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("actor", actor);
        model.addAttribute("action", actionFilter);

        // HTMX 局部刷新返结果片段（partial：摘要「匹配记录」随片段 oob）；整页请求返完整视图。
        model.addAttribute("partial", hxRequest != null);
        return hxRequest != null ? "admin/audit-logs :: resultsFragment" : "admin/audit-logs";
    }
}
