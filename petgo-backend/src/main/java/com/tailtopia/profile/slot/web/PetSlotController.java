package com.tailtopia.profile.slot.web;

import com.tailtopia.profile.slot.PetSlotService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 宠物名额摘要（V1.4.0 multi-pet Story 1.4 · AC6；Story 2.2 AC8 扩展同一端点）。仅 {@code ROLE_USER}（SecurityConfig 精确路径）。
 *
 * <p>App 点「+」时先取它：{@code petCount ≥ cap} → 已达上限抽屉；{@code petCount ≥ total} → 名额已满抽屉；
 * 否则进建档页。建档 409 只是兜底。
 */
@RestController
@RequestMapping("/api/v1/pet-slots")
public class PetSlotController {

    private final PetSlotService slots;

    public PetSlotController(PetSlotService slots) {
        this.slots = slots;
    }

    @GetMapping("/me")
    public PetSlotOverview me(@AuthenticationPrincipal Jwt jwt) {
        return PetSlotOverview.of(slots.quotaOf(currentUserId(jwt)), slots.unitPrice());
    }

    private static long currentUserId(Jwt jwt) {
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
