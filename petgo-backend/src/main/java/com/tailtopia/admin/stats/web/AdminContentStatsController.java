package com.tailtopia.admin.stats.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.stats.dto.StatsScope;
import com.tailtopia.admin.stats.service.InteractionScoreService;
import com.tailtopia.shared.error.AppException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 内容互动积分榜（V1.1.6 Story 15.1 · AB-3G）。
 *
 * <p>🛡 <b>双权限码</b>（AC6）：查看一个、导出一个。理由同 11-4 ——
 * 导出是把数据批量带出系统，风险高一档。
 * 🛡 <b>入口门与侧栏 {@code sec:authorize} 必须逐字一致</b>：
 * 不一致的表现是"看得见入口点进去 403"，或反过来"有权限却看不到入口"，两者都很难自己撞到。
 */
@Controller
public class AdminContentStatsController {

    static final String VIEW = "hasRole('SUPER_ADMIN') or hasAuthority('"
            + AdminPermissions.CONTENT_STATS_VIEW + "')";
    static final String EXPORT = "hasRole('SUPER_ADMIN') or hasAuthority('"
            + AdminPermissions.CONTENT_STATS_EXPORT + "')";

    /** 运营选的日期按 WIB 解释（与后台其余四处一致）。 */
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final InteractionScoreService stats;

    public AdminContentStatsController(InteractionScoreService stats) {
        this.stats = stats;
    }

    @GetMapping("/admin/content-stats")
    @PreAuthorize(VIEW)
    public String rank(
            @RequestParam(defaultValue = "CONTENT_QUALITY") StatsScope scope,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long authorId,
            @RequestParam(defaultValue = "0") int page, Model model) {
        // 默认最近 7 天 —— 打开页面就有东西看，比一张空表 + "请选择日期"好。
        LocalDate end = to != null ? to : LocalDate.now(WIB);
        LocalDate start = from != null ? from : end.minusDays(6);
        model.addAttribute("active", "content-stats");
        model.addAttribute("scope", scope);
        model.addAttribute("scopes", StatsScope.values());
        model.addAttribute("from", start);
        model.addAttribute("to", end);
        model.addAttribute("authorId", authorId);
        model.addAttribute("page", page);
        try {
            model.addAttribute("rows", stats.rank(scope, start, end, authorId, page));
        } catch (AppException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("rows", java.util.List.of());
        }
        return "admin/content-stats";
    }

    /** 导出 CSV（AC1）。🔴 单独一个权限码，且服务层记审计。 */
    @GetMapping("/admin/content-stats/export")
    @PreAuthorize(EXPORT)
    @ResponseBody
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(defaultValue = "CONTENT_QUALITY") StatsScope scope,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long authorId) {
        String csv = stats.exportCsv(scope, from, to, authorId, admin.getAdminAccountId());
        // ⚠️ 带 BOM：Excel 打开无 BOM 的 UTF-8 CSV 会把中文显示成乱码，
        //    而这份表的读者就是拿 Excel 看的管理层。
        byte[] body = ("﻿" + csv).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=content-stats-" + scope + "-" + from + "_" + to + ".csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
