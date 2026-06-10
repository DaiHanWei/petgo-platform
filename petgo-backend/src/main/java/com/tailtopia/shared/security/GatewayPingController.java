package com.tailtopia.shared.security;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门控对称性验证端点（仅 dev profile，Story 1.5 B3）。
 *
 * <ul>
 *   <li>{@code POST /api/v1/_guarded-ping}：受保护写占位——无 JWT → 401（验「写拒绝未登录」）。</li>
 *   <li>{@code GET /api/v1/public/_ping}：游客只读占位——permitAll → 200（验「读放行」）。</li>
 * </ul>
 * 仅用于门控对称性自动化/手动验证，不进生产业务。
 */
@RestController
@RequestMapping("/api/v1")
@Profile("dev")
public class GatewayPingController {

    @GetMapping("/public/_ping")
    public Map<String, Object> publicPing() {
        return Map.of("ok", true, "scope", "public");
    }

    @PostMapping("/_guarded-ping")
    public Map<String, Object> guardedPing() {
        return Map.of("ok", true, "scope", "guarded");
    }
}
