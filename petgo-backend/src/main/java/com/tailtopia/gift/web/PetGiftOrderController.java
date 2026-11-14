package com.tailtopia.gift.web;

import com.tailtopia.gift.service.GiftOrderBrowseService;
import com.tailtopia.gift.service.GiftOrderBrowseService.Filter;
import com.tailtopia.gift.web.dto.GiftOpenRequest;
import com.tailtopia.gift.web.dto.GiftOpenResponse;
import com.tailtopia.gift.web.dto.GiftOrderDetail;
import com.tailtopia.gift.web.dto.GiftOrderPreview;
import com.tailtopia.gift.web.dto.PetGiftOrderPage;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 逐笔礼物（V1.5.0 gift-pack Story 1-4 · 架构 §4.1）。全部只给 {@code role=USER}（{@code SecurityConfig} 精确 matcher）；
 * 查看者只取 JWT {@code sub}。与 {@link PetGiftShowcaseController} 同包同写法；旧接口（汇总 / 粉丝榜）不改（D-10）。
 * 本类不打日志。
 * <ul>
 *   <li>{@code GET /api/v1/pets/{petId}/gift-orders?filter=ALL|UNOPENED&categoryId=&cursor=}</li>
 *   <li>{@code GET /api/v1/pets/{petId}/gift-orders/preview}</li>
 *   <li>{@code POST /api/v1/pets/{petId}/gift-orders/open}</li>
 *   <li>{@code GET /api/v1/gift-orders/{orderToken}}（与送礼人轮询 {@code /api/v1/me/gift-orders/{token}} 是两条路由）</li>
 * </ul>
 */
@RestController
public class PetGiftOrderController {

    private final GiftOrderBrowseService service;

    public PetGiftOrderController(GiftOrderBrowseService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/pets/{petId}/gift-orders")
    public PetGiftOrderPage list(@AuthenticationPrincipal Jwt jwt, @PathVariable long petId,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "categoryId", required = false) Long categoryId,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(currentUserId(jwt), petId, parseFilter(filter), categoryId, cursor);
    }

    @GetMapping("/api/v1/pets/{petId}/gift-orders/preview")
    public GiftOrderPreview preview(@AuthenticationPrincipal Jwt jwt, @PathVariable long petId) {
        return service.preview(currentUserId(jwt), petId);
    }

    @PostMapping("/api/v1/pets/{petId}/gift-orders/open")
    public GiftOpenResponse open(@AuthenticationPrincipal Jwt jwt, @PathVariable long petId,
            @RequestBody(required = false) GiftOpenRequest body) {
        return service.open(currentUserId(jwt), petId, body);
    }

    @GetMapping("/api/v1/gift-orders/{orderToken}")
    public GiftOrderDetail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderToken) {
        return service.detail(currentUserId(jwt), orderToken);
    }

    /** 宽松解析：缺省 / 不认识 → ALL。 */
    static Filter parseFilter(String raw) {
        return "UNOPENED".equalsIgnoreCase(raw == null ? "" : raw.trim()) ? Filter.UNOPENED : Filter.ALL;
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
