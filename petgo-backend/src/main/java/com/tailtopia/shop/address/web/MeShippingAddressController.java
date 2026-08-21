package com.tailtopia.shop.address.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.address.dto.ShippingAddressRequest;
import com.tailtopia.shop.address.dto.ShippingAddressView;
import com.tailtopia.shop.address.service.ShippingAddressService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地址簿 CRUD（Story 2.1，FR-98）。
 *
 * <p>🔴 路径用 <b>{@code /api/v1/me/shipping-addresses}</b>，不是 {@code /users/me/...}（决策 C1 · NFR-7）。
 *
 * <p>🔒 全部端点都要求登录 —— 地址是 PII，与 Toko 商品浏览的游客开放策略<b>正好相反</b>。
 * 这也是为什么 {@code /me} 前缀本就在 {@code SecurityConfig} 的受保护范围内：
 * 本 Story <b>不需要、也不得</b>为它另开放行规则。
 */
@RestController
@RequestMapping("/api/v1/me/shipping-addresses")
public class MeShippingAddressController {

    private final ShippingAddressService service;

    public MeShippingAddressController(ShippingAddressService service) {
        this.service = service;
    }

    @GetMapping
    public List<ShippingAddressView> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(currentUserId(jwt)).stream().map(ShippingAddressView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShippingAddressView create(@AuthenticationPrincipal Jwt jwt,
            @RequestBody ShippingAddressRequest req) {
        return ShippingAddressView.of(service.create(currentUserId(jwt), req.toFields()));
    }

    @PutMapping("/{token}")
    public ShippingAddressView update(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String token, @RequestBody ShippingAddressRequest req) {
        return ShippingAddressView.of(service.update(currentUserId(jwt), token, req.toFields()));
    }

    /** 🔴 越权与不存在同为 404（防枚举），由 service 的双条件查询天然保证。 */
    @DeleteMapping("/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        service.delete(currentUserId(jwt), token);
    }

    @PostMapping("/{token}/default")
    public ShippingAddressView setDefault(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String token) {
        return ShippingAddressView.of(service.setDefault(currentUserId(jwt), token));
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
