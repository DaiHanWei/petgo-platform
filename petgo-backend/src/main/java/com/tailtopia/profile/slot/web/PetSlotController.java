package com.tailtopia.profile.slot.web;

import com.tailtopia.profile.slot.PetSlotPurchaseService;
import com.tailtopia.profile.slot.PetSlotQuota;
import com.tailtopia.profile.slot.PetSlotService;
import com.tailtopia.profile.slot.dto.PetSlotPurchaseResponse;
import com.tailtopia.profile.slot.dto.PetSlotPurchaseStatusView;
import com.tailtopia.profile.slot.dto.PetSlotRenewRequest;
import com.tailtopia.profile.slot.dto.PetSlotRentRequest;
import com.tailtopia.shared.error.AppException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 宠物名额（V1.4.0 multi-pet Story 1.4 · AC6；Story 2.2 · AC8 扩为四个端点）。全部仅 {@code ROLE_USER}（SecurityConfig 精确路径）。
 *
 * <ul>
 *   <li>{@code GET /me}：名额摘要 + 月租明细（App 点「+」/ 名额管理页）；</li>
 *   <li>{@code POST /me/rentals}、{@code POST /me/renewals}：新租 / 续租下单，<b>强制</b> {@code Idempotency-Key} 头
 *       （缺失 → 422，照 {@code PawCoinTopupController.topup}）；</li>
 *   <li>{@code GET /me/purchases/{purchaseToken}}：App {@code pollPaid} 轮询（非本人 → 404）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/pet-slots")
public class PetSlotController {

    private final PetSlotService slots;
    private final PetSlotPurchaseService purchases;

    public PetSlotController(PetSlotService slots, PetSlotPurchaseService purchases) {
        this.slots = slots;
        this.purchases = purchases;
    }

    @GetMapping("/me")
    public PetSlotOverview me(@AuthenticationPrincipal Jwt jwt) {
        long userId = currentUserId(jwt);
        PetSlotQuota quota = slots.quotaOf(userId);
        return PetSlotOverview.of(quota, slots.unitPrice(), slots.readOnlyPetCount(userId),
                slots.activeRentals(userId), slots.now());
    }

    @PostMapping("/me/rentals")
    public PetSlotPurchaseResponse rent(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PetSlotRentRequest req) {
        long userId = currentUserId(jwt);
        requireIdempotencyKey(idempotencyKey);
        return purchases.rent(userId, req.quantity(), req.channel(), idempotencyKey);
    }

    @PostMapping("/me/renewals")
    public PetSlotPurchaseResponse renew(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PetSlotRenewRequest req) {
        long userId = currentUserId(jwt);
        requireIdempotencyKey(idempotencyKey);
        return purchases.renew(userId, req.rentalTokens(), req.channel(), idempotencyKey);
    }

    @GetMapping("/me/purchases/{purchaseToken}")
    public PetSlotPurchaseStatusView purchaseStatus(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String purchaseToken) {
        return purchases.status(currentUserId(jwt), purchaseToken);
    }

    /** 资金创建端点强制幂等键（照 {@code PawCoinTopupController.topup}）：杜绝双击 / 重试造成重复下单与重复网关 charge。 */
    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw AppException.validation("资金下单必须携带 Idempotency-Key 头");
        }
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
