package com.tailtopia.shop.order.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.dto.CheckoutPreviewRequest;
import com.tailtopia.shop.order.dto.CheckoutPreviewView;
import com.tailtopia.shop.order.dto.CheckoutQuoteView;
import com.tailtopia.shop.order.dto.PlaceOrderRequest;
import com.tailtopia.shop.order.dto.ShopOrderDetailView;
import com.tailtopia.shop.order.dto.ShopOrderView;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.PlaceCommand;
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
    private final com.tailtopia.shop.order.service.CheckoutQuoteService quotes;
    private final ShopOrderPaymentService payments;
    private final com.tailtopia.shop.order.service.ShopOrderFulfillmentService fulfillment;
    private final com.tailtopia.shop.service.ShopLineImageResolver lineImages;

    public MeCheckoutController(CheckoutService checkout,
            com.tailtopia.shop.order.service.CheckoutQuoteService quotes,
            ShopOrderPaymentService payments,
            com.tailtopia.shop.order.service.ShopOrderFulfillmentService fulfillment,
            com.tailtopia.shop.service.ShopLineImageResolver lineImages) {
        this.checkout = checkout;
        this.quotes = quotes;
        this.payments = payments;
        this.fulfillment = fulfillment;
        this.lineImages = lineImages;
    }

    /**
     * 订单详情的三个出口共用：装配前先批量取一次主图（2026-09-03）。
     *
     * <p>🔴 三处各写一遍必然漏掉其中一处 —— 「下单后全链路无图」本就是这么攒出来的。
     */
    private ShopOrderDetailView detailOf(com.tailtopia.shop.order.domain.ShopOrder order) {
        var lines = payments.linesOf(order);
        return ShopOrderDetailView.of(order, lines, fulfillment.shipmentsOf(order.getId()),
                lineImages.mainImageUrlBySkuId(lines.stream()
                        .map(com.tailtopia.shop.order.domain.ShopOrderLine::getSkuId).toList()),
                payments.intentOf(order).orElse(null));
    }

    /**
     * 结算试算。超服务范围<b>不是错误</b>——回 {@code serviceable=false} 让页面渲染警示态
     * （见 {@link CheckoutService#preview}）。
     */
    @GetMapping("/checkout")
    public CheckoutPreviewView preview(@AuthenticationPrincipal Jwt jwt,
            // 可缺省（2026-09-24）：没有地址也要能看订单预览，只是不能下单。
            @RequestParam(required = false) String addressToken) {
        return CheckoutPreviewView.of(checkout.preview(currentUserId(jwt), addressToken));
    }

    /**
     * 新结算试算（v1.4.2 shop Story 3.1，D-8）：多快递、券、PawCoin 自选、保价。
     *
     * <p>POST 只因参数多，<b>语义只读</b>：不占券、不扣币、不锁库存、不写订单、不改购物车。
     * 不可下单（无地址 / 超范围 / 询价失败 / 有不可买行）<b>不是错误</b>，回 {@code placeable=false}
     * + {@code notPlaceableReason}；券不可用也不报错（试算永不因券报错）。
     */
    @PostMapping("/checkout/preview")
    public CheckoutQuoteView previewQuote(@AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) CheckoutPreviewRequest req) {
        if (req == null) {
            throw AppException.validation("source 必填");
        }
        return quotes.preview(currentUserId(jwt), req);
    }

    /**
     * 下单（v1.4.2 shop Story 3.2 重写，D-8）。
     *
     * <p>🔴 <b>判别只看 {@code quoteToken}</b>：为空 → 旧结算模式（旧版 App，S-4）——
     * {@link CheckoutService#placeOrder} 先在事务外询价（{@code LegacyCheckoutAdapter}），再进提交事务；
     * 非空 → 新结算模式，按报价锁定快递与运费、MANUAL 用券、PawCoin 自选，提交即扣币（S-3）。
     *
     * <p>🔴 有行不可购买时回 <b>409 {@code unavailable-lines} + {@code unavailableLines} 扩展成员</b>，
     * 前端据此让用户移除后继续 —— <b>不整单打回</b>（FR-95）。映射由异常自己声明（{@code ProblemExtensions}），
     * 本控制器<b>不重复拼错误信封</b>。
     *
     * <p>🔴 {@code Idempotency-Key} 头去重（可选）：同 key 重放取回既有订单，<b>不重复建单、不重复扣币</b>。
     */
    @PostMapping("/shop-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ShopOrderView placeOrder(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) PlaceOrderRequest req) {
        long userId = currentUserId(jwt);
        if (req == null || !req.newCheckoutMode()) {
            // 旧结算模式：新字段一律忽略；空地址 422 validation「请选择收货地址」（沿用旧文案与码）
            if (req == null || req.addressToken() == null || req.addressToken().isBlank()) {
                throw AppException.validation("请选择收货地址");
            }
            return ShopOrderView.of(checkout.placeOrder(userId, req.addressToken(),
                    req.entrySource(), req.triggerType(), idempotencyKey));
        }
        return ShopOrderView.of(checkout.place(userId, PlaceCommand.of(req), idempotencyKey));
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
        return detailOf(order);
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
        return detailOf(order);
    }

    // ===== Story 4.1：履约段（SPEC-2 出口②） =====

    /**
     * 用户确认收货。
     *
     * <p>🔴 <b>{@code SHIPPED} 态即可确认，不必等系统标记送达</b>（SPEC-2 出口②）——
     * 用户比谁都先知道货到没到。只留「已送达后才能确认」这一条路，等于把订单能否脱离
     * {@code SHIPPED} 完全押在运营记不记得点那个按钮上。
     *
     * <p>幂等：重复点击返回同一个已完成订单，不报错。
     */
    @PostMapping("/shop-orders/{token}/confirm-receipt")
    public ShopOrderDetailView confirmReceipt(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String token) {
        long userId = currentUserId(jwt);
        var order = fulfillment.confirmReceipt(userId, token);
        return detailOf(order);
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
