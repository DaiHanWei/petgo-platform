package com.tailtopia.profile.web;

import com.tailtopia.profile.dto.ActiveSelectionRequest;
import com.tailtopia.profile.dto.PetListItemResponse;
import com.tailtopia.profile.service.PetListService;
import com.tailtopia.profile.slot.PetSlotStateService;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 名额管理页「Ubah pilihan」改选（V1.4.0 multi-pet Story 2.1 · AC4 · D13 / D14）。仅 {@code ROLE_USER}（SecurityConfig 精确路径）。
 *
 * <p>选中者保持正常、其余只读，立即生效（Toast 由 App 出）；返回最新「我的宠物」列表（同 {@code GET /me/list} 形状）。
 * 单列一个控制器：不改 {@link ProfileApiController} 构造（既有单测不动）。
 */
@RestController
@RequestMapping("/api/v1/pet-profiles")
public class PetActiveSelectionController {

    private final PetSlotStateService petSlotState;
    private final PetListService petListService;

    public PetActiveSelectionController(PetSlotStateService petSlotState, PetListService petListService) {
        this.petSlotState = petSlotState;
        this.petListService = petListService;
    }

    @PutMapping("/me/active-selection")
    public List<PetListItemResponse> changeSelection(@AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) ActiveSelectionRequest req) {
        long ownerId = currentUserId(jwt);
        petSlotState.changeSelection(ownerId, req == null ? null : req.petIds());
        return petListService.listMine(ownerId);
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
