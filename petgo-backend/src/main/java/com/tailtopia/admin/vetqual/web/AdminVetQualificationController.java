package com.tailtopia.admin.vetqual.web;

import com.tailtopia.admin.service.AdminVetService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.vetqual.domain.VetQualification;
import com.tailtopia.admin.vetqual.dto.QualificationForm;
import com.tailtopia.admin.vetqual.service.VetQualificationService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.SignedUrlService;
import com.tailtopia.shared.i18n.Messages;
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
    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('vet.qualify_view') or hasAuthority('vet.qualify')";

    private final VetQualificationService qualService;
    private final AdminVetService adminVetService;
    private final SignedUrlService signedUrlService;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminVetQualificationController(VetQualificationService qualService,
            AdminVetService adminVetService, SignedUrlService signedUrlService,
            Messages msg) {
        this.qualService = qualService;
        this.adminVetService = adminVetService;
        this.signedUrlService = signedUrlService;
        this.msg = msg;
    }

    /**
     * 资质页签（V1.3.0 Story 9.1b · AC1）。
     *
     * <p>📌 <b>整页 {@code vet-qualification.html} 已删除</b>：内容并入兽医抽屉的「资质」页签。
     * 不做旧地址跳转（D-23）—— 但这条路径上还有三个 POST，所以非 htmx 直达回列表并开抽屉，
     * 而不是 404（404 会让「点了旧书签」与「路径写错」两件事看起来一样）。
     *
     * <p>🔴 页签是**懒加载**的（抽屉里那块 hx-get 到这里）：证件图的预签名 URL 只在真的看的时候才签，
     * 每开一次抽屉就签六个短 TTL URL 是白白扩大暴露面。
     */
    @GetMapping("/admin/vets/{id}/qualification")
    @PreAuthorize(VIEW_AUTH)
    public String qualification(@PathVariable long id,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "HX-Request", required = false) String hxRequest, Model model) {
        if (hxRequest == null) {
            return "redirect:/admin/vets?open=" + id;
        }
        populateQual(id, model);
        return "admin/fragments/drawer-vets :: qual-panel";
    }

    private void populateQual(long id, Model model) {
        model.addAttribute("active", "vets");
        model.addAttribute("vetId", id);
        model.addAttribute("vet", adminVetService.view(id));
        VetQualification q = qualService.findForVet(id).orElse(null);
        model.addAttribute("qual", q);
        // 证件图现签短 TTL URL（不缓存、不入库、不落日志）。
        if (q != null) {
            model.addAttribute("ktpUrl", sign(q.getKtpPhotoKey()));
            model.addAttribute("sipdhUrl", sign(q.getSipdhPhotoKey()));
            model.addAttribute("strvUrl", sign(q.getStrvPhotoKey()));
            model.addAttribute("degreeUrl", sign(q.getDegreePhotoKey()));
            model.addAttribute("profileUrl", sign(q.getProfilePhotoKey()));
            model.addAttribute("pdhiUrl", sign(q.getPdhiPhotoKey()));
        }
        if (!model.containsAttribute("qualificationForm")) {
            model.addAttribute("qualificationForm", new QualificationForm());
        }
    }

    /**
     * 处置成功统一响应：**只换资质页签这一块** + 列表整表重拉（资质态列会变）。
     *
     * <p>⚠️ 不重渲染整个抽屉：资质与评分两个页签是懒加载的，整体重渲染会把它们打回未加载态，
     * 运营刚看的证件图与评分明细全没了。
     */
    private String qualAfterAction(long id, String toast, Model model,
            jakarta.servlet.http.HttpServletResponse response) {
        populateQual(id, model);
        model.addAttribute("toast", toast);
        com.tailtopia.admin.shared.web.AdminFragmentResponses.trigger(response,
                com.tailtopia.admin.shared.web.AdminHxEvents.VET_LIST_REFRESH);
        return "admin/fragments/drawer-vets :: qual-after";
    }

    /** 直录（mode=record，默认）或续期（mode=renew）。 */
    @PostMapping("/admin/vets/{id}/qualification")
    @PreAuthorize(AUTH)
    public String save(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(value = "mode", defaultValue = "record") String mode,
            @ModelAttribute("qualificationForm") QualificationForm form,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "HX-Request", required = false) String hxRequest,
            Model model, jakarta.servlet.http.HttpServletResponse response,
            RedirectAttributes flash) {
        boolean renew = "renew".equals(mode);
        if (hxRequest != null) {
            // 业务失败在服务层抛 422，这里不重复判 —— 让它落进页签的行内错误槽。
            // ⚠️ 表单对象**不进日志**（含证件号与 key）：这条分支同样不打 debug 日志。
            if (renew) {
                qualService.renew(id, form, admin.getAdminAccountId());
            } else {
                qualService.recordByOps(id, form, admin.getAdminAccountId());
            }
            return qualAfterAction(id,
                    msg.get(renew ? "admin.flash.vetQual.renewed" : "admin.flash.vetQual.recorded"),
                    model, response);
        }
        try {
            if (renew) {
                qualService.renew(id, form, admin.getAdminAccountId());
                flash.addFlashAttribute("notice", msg.get("admin.flash.vetQual.renewed"));
            } else {
                qualService.recordByOps(id, form, admin.getAdminAccountId());
                flash.addFlashAttribute("notice", msg.get("admin.flash.vetQual.recorded"));
            }
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/vets?open=" + id;
    }

    @PostMapping("/admin/vets/{id}/qualification/approve")
    @PreAuthorize(AUTH)
    public String approve(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "HX-Request", required = false) String hxRequest,
            Model model, jakarta.servlet.http.HttpServletResponse response,
            RedirectAttributes flash) {
        if (hxRequest != null) {
            qualService.approve(id, admin.getAdminAccountId());
            return qualAfterAction(id, msg.get("admin.flash.vetQual.approved"), model, response);
        }
        try {
            qualService.approve(id, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.vetQual.approved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/vets?open=" + id;
    }

    @PostMapping("/admin/vets/{id}/qualification/reject")
    @PreAuthorize(AUTH)
    public String reject(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam("reason") String reason,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "HX-Request", required = false) String hxRequest,
            Model model, jakarta.servlet.http.HttpServletResponse response,
            RedirectAttributes flash) {
        if (hxRequest != null) {
            qualService.reject(id, reason, admin.getAdminAccountId());
            return qualAfterAction(id, msg.get("admin.flash.vetQual.rejected"), model, response);
        }
        try {
            qualService.reject(id, reason, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.vetQual.rejected"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/vets?open=" + id;
    }

    private String sign(String key) {
        return (key == null || key.isBlank()) ? null : signedUrlService.sign(key);
    }
}
