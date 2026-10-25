package com.tailtopia.merchant.binding.web;

import com.tailtopia.merchant.binding.service.MerchantBindingService;
import com.tailtopia.merchant.binding.service.MerchantBindingService.BindingView;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 个人设置页填门店邀请码（V1.4.1，商家平台契约 FR-7）。
 *
 * <p>仅 {@code role=USER}（{@code SecurityConfig} 精确路径门控）：controller 把 {@code jwt.sub} 当 {@code users.id} 用，
 * 兽医 token 的 sub 是 vetId。
 *
 * <ul>
 *   <li>{@code GET} → {@code {bound, inviteCode, boundAt}}，未绑定 {@code bound=false}；</li>
 *   <li>{@code POST {code}} → 绑定；码无效 / 门店停用 {@code 422 merchant-code-invalid}，
 *       已绑定 {@code 409 merchant-already-bound}。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/me/merchant-binding")
public class MeMerchantBindingController {

    private final MerchantBindingService service;

    public MeMerchantBindingController(MerchantBindingService service) {
        this.service = service;
    }

    @GetMapping
    public MerchantBindingResponse get(@AuthenticationPrincipal Jwt jwt) {
        return service.current(currentUserId(jwt)).map(MerchantBindingResponse::of)
                .orElse(MerchantBindingResponse.NONE);
    }

    @PostMapping
    public MerchantBindingResponse bind(@AuthenticationPrincipal Jwt jwt, @RequestBody BindRequest req) {
        return MerchantBindingResponse.of(service.bind(currentUserId(jwt), req == null ? null : req.code()));
    }

    public record BindRequest(String code) {
    }

    public record MerchantBindingResponse(boolean bound, String inviteCode, Instant boundAt) {

        static final MerchantBindingResponse NONE = new MerchantBindingResponse(false, null, null);

        static MerchantBindingResponse of(BindingView v) {
            return new MerchantBindingResponse(true, v.inviteCode(), v.boundAt());
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
