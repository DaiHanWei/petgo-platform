package com.tailtopia.admin.anomaly.web;

import com.tailtopia.admin.anomaly.domain.AnomalyStatus;
import com.tailtopia.admin.anomaly.domain.ConsultAnomaly;
import com.tailtopia.admin.anomaly.service.ConsultAnomalyService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.SignedUrlService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 问诊异常工单（Story 5.1，AB-4A）。SSR + HTMX，{@code /admin/anomalies}，不返 JSON。
 * 查看 {@code consult.view_anomalies}；处理（备注/标记已处理）{@code consult.handle}；**无删除端点（AC6）**。
 * 仅展示会话元数据 + 处理图现签 URL（绝不读第三方 IM/AI，NFR5）。
 */
@Controller
public class AdminAnomalyController {

    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('consult.view_anomalies')";
    private static final String HANDLE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('consult.handle')";

    private final ConsultAnomalyService anomalyService;
    private final SignedUrlService signedUrlService;

    public AdminAnomalyController(ConsultAnomalyService anomalyService, SignedUrlService signedUrlService) {
        this.anomalyService = anomalyService;
        this.signedUrlService = signedUrlService;
    }

    @GetMapping("/admin/anomalies")
    @PreAuthorize(VIEW_AUTH)
    public String list(@RequestParam(value = "status", required = false) String status,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        model.addAttribute("active", "anomalies");
        model.addAttribute("status", status);
        model.addAttribute("items", anomalyService.list(parseStatus(status)));
        return hxRequest != null ? "admin/anomalies :: rows" : "admin/anomalies";
    }

    @GetMapping("/admin/anomalies/{id}")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@PathVariable long id, Model model) {
        model.addAttribute("active", "anomalies");
        ConsultAnomaly a = anomalyService.find(id).orElseThrow(() -> AppException.notFound("异常工单不存在"));
        model.addAttribute("anomaly", a);
        // 处理图现签短 TTL URL（不缓存、不入库、不落日志）。
        String key = a.getResolutionImageKey();
        model.addAttribute("resolutionImageUrl",
                (key == null || key.isBlank()) ? null : signedUrlService.sign(key));
        return "admin/anomaly-detail";
    }

    @PostMapping("/admin/anomalies/{id}/note")
    @PreAuthorize(HANDLE_AUTH)
    public String note(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam("note") String note, RedirectAttributes flash) {
        try {
            anomalyService.addNote(id, note, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已添加内部备注");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/anomalies/" + id;
    }

    @PostMapping("/admin/anomalies/{id}/resolve")
    @PreAuthorize(HANDLE_AUTH)
    public String resolve(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(value = "resolutionImageKey", required = false) String resolutionImageKey,
            RedirectAttributes flash) {
        anomalyService.resolve(id, resolutionImageKey, admin.getAdminAccountId());
        flash.addFlashAttribute("notice", "已标记为已处理并归档");
        return "redirect:/admin/anomalies/" + id;
    }

    private AnomalyStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AnomalyStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
