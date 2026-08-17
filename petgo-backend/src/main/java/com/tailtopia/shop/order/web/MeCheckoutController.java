package com.tailtopia.shop.order.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.dto.CheckoutPreviewView;
import com.tailtopia.shop.order.dto.CheckoutUnavailableException;
import com.tailtopia.shop.order.dto.ShopOrderView;
import com.tailtopia.shop.order.service.CheckoutService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结算试算与下单（Story 3.7 前端所需的两个端点；业务逻辑全在 Story 3.4 的 {@link CheckoutService}）。
 *
 * <p>🔴 <b>本控制器不含任何金额计算</b>：试算与下单调的是同一个 service，
 * 前端也只认这里下发的数字。「结算页显示 285.000、提交后变成 305.000」这类问题的唯一防法，
 * 就是自始至终只有一处在算。
 *
 * <p>🔒 {@code /me} 前缀本就在 {@code SecurityConfig} 受保护范围，<b>不需要也不得</b>另开放行规则。
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeCheckoutController {

    private final CheckoutService checkout;

    public MeCheckoutController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    /**
     * 结算试算。超服务范围<b>不是错误</b>——回 {@code serviceable=false} 让页面渲染警示态
     * （见 {@link CheckoutService#preview}）。
     */
    @GetMapping("/checkout")
    public CheckoutPreviewView preview(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String addressToken) {
        return CheckoutPreviewView.of(checkout.preview(currentUserId(jwt), addressToken));
    }

    /**
     * 下单（只锁库存、不扣款；PawCoin 实扣在支付执行时，Story 3.8）。
     *
     * <p>🔴 有行不可购买时回 <b>409 + {@code unavailableLines} 扩展成员</b>，
     * 前端据此让用户移除后继续 —— <b>不整单打回</b>（FR-95）。
     * 笼统的「库存不足，请重试」会让用户在一车 8 件商品里逐个试错。
     * 映射由 {@link CheckoutUnavailableException} 自己声明（它实现 {@code ProblemExtensions}），
     * 本控制器<b>不重复拼错误信封</b> —— 自拼会漏掉 traceId，而排障最先看的就是它。
     */
    @PostMapping("/shop-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ShopOrderView placeOrder(@AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) PlaceOrderRequest req) {
        if (req == null || req.addressToken() == null || req.addressToken().isBlank()) {
            throw AppException.validation("请选择收货地址");
        }
        return ShopOrderView.of(checkout.placeOrder(currentUserId(jwt), req.addressToken(),
                req.entrySource(), req.triggerType()));
    }

    /**
     * 下单请求体。
     *
     * @param entrySource 归因：用户从哪个入口进的商品（区域② 档案推荐 / 区域④ 全部精选 …）
     * @param triggerType 归因：触发类型（复购提醒 / 主动浏览 …）。两者随订单行落库，
     *     是 AB-13B 复购看板的<b>服务端权威口径</b>（PostHog 只作交叉验证）。
     */
    public record PlaceOrderRequest(String addressToken, String entrySource, String triggerType) {
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
