package com.tailtopia.admin.web;

import com.tailtopia.admin.dto.CreateVetForm;
import com.tailtopia.admin.dto.SeedPostForm;
import com.tailtopia.admin.service.AdminContentService;
import com.tailtopia.admin.service.AdminModerationService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminVetService;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.shared.error.AppException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 运营后台页面控制器（Story 3.1）。Thymeleaf 服务端渲染，走 {@code /admin/**}（与 {@code /api/v1} JSON 隔离）。
 *
 * <p>门控由 {@code SecurityConfig} 的 admin filter chain 负责（{@code role=ADMIN}，表单登录会话）。
 * 本 shell 是横切设施：导航壳已预留「举报队列(3.7) / 兽医账号·评分查看(Epic 5)」入口位（占位 disabled）。
 */
@Controller
public class AdminWebController {

    private final AdminContentService adminContentService;
    private final AdminModerationService adminModerationService;
    private final AdminVetService adminVetService;

    public AdminWebController(AdminContentService adminContentService,
            AdminModerationService adminModerationService,
            AdminVetService adminVetService) {
        this.adminContentService = adminContentService;
        this.adminModerationService = adminModerationService;
        this.adminVetService = adminVetService;
    }

    /** 登录页（未认证可访问；认证失败回显 error，登出回显 logout）。 */
    @GetMapping("/admin/login")
    public String login() {
        return "admin/login";
    }

    @GetMapping({"/admin", "/admin/dashboard"})
    public String dashboard(Model model) {
        model.addAttribute("active", "dashboard");
        return "admin/dashboard";
    }

    @GetMapping("/admin/seed-post")
    public String seedPostForm(Model model) {
        model.addAttribute("active", "seed");
        if (!model.containsAttribute("seedPostForm")) {
            model.addAttribute("seedPostForm", new SeedPostForm());
        }
        model.addAttribute("types", ContentType.values());
        return "admin/seed-post";
    }

    // ===== Story 3.7：举报审核队列（复用本 shell）=====

    @GetMapping("/admin/reports")
    public String reports(Model model) {
        model.addAttribute("active", "reports");
        model.addAttribute("reports", adminModerationService.pendingQueue());
        return "admin/reports";
    }

    @PostMapping("/admin/reports/{id}/takedown")
    public String takedown(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id) {
        adminModerationService.takedown(id, admin.getUserId());
        return "redirect:/admin/reports";
    }

    @PostMapping("/admin/reports/{id}/dismiss")
    public String dismiss(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id) {
        adminModerationService.dismiss(id, admin.getUserId());
        return "redirect:/admin/reports";
    }

    // ===== Story 5.1：兽医账号 CRUD（复用本 shell）=====

    @GetMapping("/admin/vets")
    public String vets(Model model) {
        model.addAttribute("active", "vets");
        model.addAttribute("vets", adminVetService.list());
        if (!model.containsAttribute("createVetForm")) {
            model.addAttribute("createVetForm", new CreateVetForm());
        }
        return "admin/vets";
    }

    @PostMapping("/admin/vets")
    public String createVet(@Valid @ModelAttribute("createVetForm") CreateVetForm form,
            BindingResult binding, Model model) {
        model.addAttribute("active", "vets");
        model.addAttribute("vets", adminVetService.list());
        if (binding.hasErrors()) {
            return "admin/vets";
        }
        try {
            long id = adminVetService.create(form.getDisplayName(), form.getUsername(), form.getPassword());
            model.addAttribute("createVetForm", new CreateVetForm());
            model.addAttribute("createdVetId", id);
            // 列表已变更，重查（含新账号）。
            model.addAttribute("vets", adminVetService.list());
            return "admin/vets";
        } catch (AppException e) {
            binding.reject("create.failed", e.getMessage());
            return "admin/vets";
        }
    }

    @PostMapping("/admin/vets/{id}/password")
    public String resetVetPassword(@PathVariable long id, @RequestParam("newPassword") String newPassword) {
        adminVetService.resetPassword(id, newPassword);
        return "redirect:/admin/vets";
    }

    @PostMapping("/admin/vets/{id}/status")
    public String setVetStatus(@PathVariable long id, @RequestParam("banned") boolean banned) {
        adminVetService.setBanned(id, banned);
        return "redirect:/admin/vets";
    }

    // ===== Story 5.6：兽医评分查看（仅运营可见）=====

    @GetMapping("/admin/vets/{id}/ratings")
    public String vetRatings(@PathVariable long id, Model model) {
        model.addAttribute("active", "vets");
        model.addAttribute("vet", adminVetService.view(id));
        model.addAttribute("ratings", adminVetService.ratings(id));
        return "admin/vet-ratings";
    }

    @PostMapping("/admin/seed-post")
    public String publishSeed(@AuthenticationPrincipal AdminUserDetails admin,
            @Valid @ModelAttribute("seedPostForm") SeedPostForm form, BindingResult binding,
            Model model) {
        model.addAttribute("active", "seed");
        model.addAttribute("types", ContentType.values());
        if (binding.hasErrors()) {
            return "admin/seed-post";
        }
        try {
            ContentPostResponse saved = adminContentService.publishSeed(
                    admin.getUserId(), form.getType(), form.getPetId(), form.getText(), form.imageUrls());
            // 发布成功：清空表单 + 成功提示（含 postId，便于运营核对）。
            model.addAttribute("seedPostForm", new SeedPostForm());
            model.addAttribute("publishedId", saved.id());
            return "admin/seed-post";
        } catch (AppException e) {
            // 服务端权威校验失败（类型/字数/图片数/宠物归属）→ 表单内联回显。
            binding.reject("publish.failed", e.getMessage());
            model.addAttribute("seedPostForm", form);
            return "admin/seed-post";
        }
    }
}
