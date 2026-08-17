package com.tailtopia.shop.cart.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.cart.service.CartService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购物车（Story 3.1，FR-96）。
 *
 * <p>🔴 路径 <b>{@code /api/v1/me/cart}</b>（决策 C1，不用 {@code /users/me}）。
 * <p>🔒 <b>全部要求登录</b> —— {@code /me} 前缀本就在 {@code SecurityConfig} 的受保护范围，
 * 本 Story <b>不需要也不得</b>为它开放行规则。游客加购会拿到 401，这正是 FR-96 要的。
 */
@RestController
@RequestMapping("/api/v1/me/cart")
public class MeCartController {

    private final CartService cart;

    public MeCartController(CartService cart) {
        this.cart = cart;
    }

    @GetMapping
    public CartView view(@AuthenticationPrincipal Jwt jwt) {
        return cart.view(currentUserId(jwt));
    }

    @PostMapping("/items")
    public CartView add(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String skuToken, @RequestParam(defaultValue = "1") int qty) {
        return cart.add(currentUserId(jwt), skuToken, qty);
    }

    @PutMapping("/items/{skuToken}")
    public CartView setQty(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String skuToken, @RequestParam int qty) {
        return cart.setQty(currentUserId(jwt), skuToken, qty);
    }

    @DeleteMapping("/items/{skuToken}")
    public CartView remove(@AuthenticationPrincipal Jwt jwt, @PathVariable String skuToken) {
        return cart.remove(currentUserId(jwt), skuToken);
    }

    /** 清空全部失效商品（已下架 / 已售罄）。 */
    @DeleteMapping("/invalid-items")
    public CartView clearInvalid(@AuthenticationPrincipal Jwt jwt) {
        return cart.clearInvalid(currentUserId(jwt));
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
