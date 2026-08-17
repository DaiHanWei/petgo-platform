package com.tailtopia.shop.order.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.dto.CheckoutPreviewView;
import com.tailtopia.shop.order.dto.CheckoutUnavailableException;
import com.tailtopia.shop.order.dto.ShopOrderDetailView;
import com.tailtopia.shop.order.dto.ShopOrderView;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结算、下单与支付（Story 3.7 试算/下单 · Story 3.8 详情/支付/取消）。
 * 业务逻辑全在 {@link CheckoutService}（3.4）与 {@link ShopOrderPaymentService}（3.8）。
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
    private final ShopOrderPaymentService payments;

    public MeCheckoutController(CheckoutService checkout, ShopOrderPaymentService payments) {
        this.checkout = checkout;
        this.payments = payments;
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

    // ===== Story 3.8：待支付订单详情与支付 =====

    /**
     * 订单详情。🔴 **读的时候就地判过期**（懒过期）——用户会在窗口刚过的那一秒打开这一页，
     * 等定时扫描（最长 1 分钟延迟）会让他看到一个其实已经作废的倒计时。
     */
    @GetMapping("/shop-orders/{token}")
    public ShopOrderDetailView detail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String token) {
        var order = payments.requireOwn(currentUserId(jwt), token);
        return ShopOrderDetailView.of(order, payments.linesOf(order));
    }

    /**
     * 发起支付。
     *
     * <p>🔴 **仅 QRIS 一种真钱渠道**（FR-100）：渠道不是入参 —— 留一个恒等于 QRIS 的参数
     * 只会让调用方以为还能选别的。PawCoin 段是否参与由**下单时固化的拆分**决定，同样不可现改。
     */
    @PostMapping("/shop-orders/{token}/pay")
    public ShopOrderPaymentService.PayResult pay(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return payments.pay(currentUserId(jwt), token, idempotencyKey);
    }

    /** 用户主动取消（仅待支付可取消）。释放库存 + 作废支付意图。 */
    @PostMapping("/shop-orders/{token}/cancel")
    public ShopOrderDetailView cancel(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String token) {
        long userId = currentUserId(jwt);
        payments.cancel(userId, token);
        var order = payments.requireOwn(userId, token);
        return ShopOrderDetailView.of(order, payments.linesOf(order));
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
