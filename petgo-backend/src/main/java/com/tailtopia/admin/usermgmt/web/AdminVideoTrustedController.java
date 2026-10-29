package com.tailtopia.admin.usermgmt.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.usermgmt.service.VideoTrustedPublisherAdminService;
import com.tailtopia.admin.usermgmt.service.VideoTrustedPublisherAdminService.Row;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 视频免审名单页（专题线 video · Story 1.1 · FR-22）。只读；加入 / 移出在用户抽屉（{@link AdminUserController}）。
 * 查看即需 {@code video.trusted.manage}（照 ops-popups 先例：单一码，不预授予）。
 */
@Controller
public class AdminVideoTrustedController {

    private static final String AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.VIDEO_TRUSTED_MANAGE + "')";
    private static final int PAGE_SIZE = 20;

    private final VideoTrustedPublisherAdminService service;

    public AdminVideoTrustedController(VideoTrustedPublisherAdminService service) {
        this.service = service;
    }

    @GetMapping("/admin/video-trusted")
    @PreAuthorize(AUTH)
    public String list(@RequestParam(value = "page", defaultValue = "0") int page, Model model) {
        model.addAttribute("active", "video-trusted");
        Page<Row> result = service.page(PageRequest.of(Math.max(page, 0), PAGE_SIZE));
        model.addAttribute("rows", result.getContent());
        model.addAttribute("page", result);
        return "admin/video-trusted";
    }
}
