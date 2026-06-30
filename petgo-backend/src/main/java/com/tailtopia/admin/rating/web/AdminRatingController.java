package com.tailtopia.admin.rating.web;

import com.tailtopia.admin.rating.service.AdminRatingService;
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
 * 兽医评分总览（Story 6.1，AB-6A）。SSR + HTMX，{@code /admin/ratings}，不返 JSON。
 * **纯只读**：仅 GET、无写、无审计。门控 {@code rating.view}；评分仅运营可见，不对 App 公开。
 */
@Controller
public class AdminRatingController {

    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('rating.view')";

    private final AdminRatingService ratingService;

    public AdminRatingController(AdminRatingService ratingService) {
        this.ratingService = ratingService;
    }

    @GetMapping("/admin/ratings")
    @PreAuthorize(VIEW_AUTH)
    public String overview(@RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        model.addAttribute("active", "ratings");
        model.addAttribute("sort", sort);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        // 日期按 UTC 日界：from 取当日 00:00、to 取次日 00:00（不含）。
        model.addAttribute("items", ratingService.overview(sort,
                from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        return hxRequest != null ? "admin/ratings :: rows" : "admin/ratings";
    }
}
