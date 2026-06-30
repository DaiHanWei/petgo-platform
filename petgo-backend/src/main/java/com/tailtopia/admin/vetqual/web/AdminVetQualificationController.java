package com.tailtopia.admin.vetqual.web;

import com.tailtopia.admin.service.AdminVetService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.vetqual.domain.VetQualification;
import com.tailtopia.admin.vetqual.dto.QualificationForm;
import com.tailtopia.admin.vetqual.service.VetQualificationService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.SignedUrlService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 兽医资质后台审核（Story 2.7，AB-2H）。直录/续期/通过/驳回；证件图私密桶现签短 TTL URL 展示。
 * 门控 {@code @PreAuthorize(vet.qualify)}。**证件 key/签名 URL/证件号绝不落日志/审计**（审计在 service 层）。
 */
@Controller
public class AdminVetQualificationController {

    private static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('vet.qualify')";

    private final VetQualificationService qualService;
    private final AdminVetService adminVetService;
    private final SignedUrlService signedUrlService;

    public AdminVetQualificationController(VetQualificationService qualService,
            AdminVetService adminVetService, SignedUrlService signedUrlService) {
        this.qualService = qualService;
        this.adminVetService = adminVetService;
        this.signedUrlService = signedUrlService;
    }

    @GetMapping("/admin/vets/{id}/qualification")
    @PreAuthorize(AUTH)
    public String qualification(@PathVariable long id, Model model) {
        model.addAttribute("active", "vets");
        model.addAttribute("vetId", id);
        model.addAttribute("vet", adminVetService.view(id));
        VetQualification q = qualService.findForVet(id).orElse(null);
        model.addAttribute("qual", q);
        // 证件图现签短 TTL URL（不缓存、不入库、不落日志）。
        if (q != null) {
            model.addAttribute("ktpUrl", sign(q.getKtpPhotoKey()));
            model.addAttribute("sipdhUrl", sign(q.getSipdhPhotoKey()));
            model.addAttribute("degreeUrl", sign(q.getDegreePhotoKey()));
            model.addAttribute("profileUrl", sign(q.getProfilePhotoKey()));
            model.addAttribute("pdhiUrl", sign(q.getPdhiPhotoKey()));
        }
        if (!model.containsAttribute("qualificationForm")) {
            model.addAttribute("qualificationForm", new QualificationForm());
        }
        return "admin/vet-qualification";
    }

    /** 直录（mode=record，默认）或续期（mode=renew）。 */
    @PostMapping("/admin/vets/{id}/qualification")
    @PreAuthorize(AUTH)
    public String save(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(value = "mode", defaultValue = "record") String mode,
            @ModelAttribute("qualificationForm") QualificationForm form, RedirectAttributes flash) {
        try {
            if ("renew".equals(mode)) {
                qualService.renew(id, form, admin.getAdminAccountId());
                flash.addFlashAttribute("notice", "已续期，资质保持已认证");
            } else {
                qualService.recordByOps(id, form, admin.getAdminAccountId());
                flash.addFlashAttribute("notice", "已录入资质，状态：已认证");
            }
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/vets/" + id + "/qualification";
    }

    @PostMapping("/admin/vets/{id}/qualification/approve")
    @PreAuthorize(AUTH)
    public String approve(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes flash) {
        try {
            qualService.approve(id, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已通过审核，状态：已认证");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/vets/" + id + "/qualification";
    }

    @PostMapping("/admin/vets/{id}/qualification/reject")
    @PreAuthorize(AUTH)
    public String reject(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam("reason") String reason, RedirectAttributes flash) {
        try {
            qualService.reject(id, reason, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已驳回该资质");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/vets/" + id + "/qualification";
    }

    private String sign(String key) {
        return (key == null || key.isBlank()) ? null : signedUrlService.sign(key);
    }
}
