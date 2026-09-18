package com.tailtopia.admin.shared.web;

import com.tailtopia.admin.shared.nav.NavBadgeService;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 侧栏角标 fragment（V1.3.0 Story 2.2 AC2；Story 2.3a 的 htmx 横切件继续在此扩展）。
 * {@code GET /admin/nav/badges} → {@code fragments/nav-badges :: badges}：组级总数 + 各队列 oob 替换。
 * 无入口门（任何后台账号可请求）；计数只含登录者可见队列。
 */
@Controller
public class AdminNavController {

    private final NavBadgeService badges;

    public AdminNavController(NavBadgeService badges) {
        this.badges = badges;
    }

    @GetMapping("/admin/nav/badges")
    public String badges(Authentication auth, Model model) {
        Set<String> authorities = auth == null ? Set.of()
                : auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        model.addAttribute("badges", badges.counts(authorities));
        model.addAttribute("queues", NavBadgeService.QUEUES);
        return "admin/fragments/nav-badges :: badges";
    }
}
