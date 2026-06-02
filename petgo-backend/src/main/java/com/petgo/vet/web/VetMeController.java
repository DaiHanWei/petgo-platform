package com.petgo.vet.web;

import com.petgo.shared.error.AppException;
import com.petgo.vet.dto.VetMeResponse;
import com.petgo.vet.service.VetAccountService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兽医自身端点（Story 5.1）。{@code GET /api/v1/vet/me}：返回 displayName/status，
 * 供登录后探活 + 双向角色门控验证（user token 访问 → 403）。
 *
 * <p>门控：{@code /api/v1/vet/**} 要求 {@code role=VET}（见 {@code SecurityConfig}）。
 * 工作台业务端点在 Story 5.2+ 填充。
 */
@RestController
@RequestMapping("/api/v1/vet")
public class VetMeController {

    private final VetAccountService vetAccounts;

    public VetMeController(VetAccountService vetAccounts) {
        this.vetAccounts = vetAccounts;
    }

    @GetMapping("/me")
    public VetMeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return VetMeResponse.from(vetAccounts.getById(currentVetId(jwt)));
    }

    static long currentVetId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw AppException.unauthorized("需要登录后访问");
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw AppException.unauthorized("无效的登录凭证");
        }
    }
}
