package com.tailtopia.order.web;

import com.tailtopia.order.dto.OrderDetailView;
import com.tailtopia.order.dto.OrderPage;
import com.tailtopia.order.service.OrderCenterService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单中心读端点（Story 5.1，FR-54）。{@code GET /api/v1/orders}（JWT role=USER）。泛化聚合兽医/AI/充值 3 类
 * （HD 属 Epic 6 预留）+ 游标分页 + 类型筛选 + PawCoin 汇总。<b>仅作用当前 JWT sub，绝不接受任意 userId</b>
 * （防越权 C1，照 {@code PawCoinController}）。
 */
@RestController
public class OrderController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final OrderCenterService orderCenter;

    public OrderController(OrderCenterService orderCenter) {
        this.orderCenter = orderCenter;
    }

    /**
     * 🔴 {@code includeEcommerce} 是电商行的<b>显式加入闸门</b>（Story 3.9 补丁）：
     * 线上 v1.1.4 老 App 不认识 {@code ShopOrderStatus} —— 状态全兜进「已完成」组、
     * 文案渲染英文枚举串、CANCELLED 还打绿色成功徽章。所以默认聚合（不传该参）<b>不含</b>电商行，
     * 只有本版起的新 App 显式传 {@code includeEcommerce=true}（或 {@code type=ECOMMERCE}，
     * 老 App 没有这个枚举）才返回。
     */
    @GetMapping("/api/v1/orders")
    public OrderPage orders(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "false") boolean includeEcommerce) {
        int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        return orderCenter.listOrders(currentUserId(jwt), type, cursor, size, includeEcommerce);
    }

    /** 订单详情（Story 5.3，按 token 跨 3 源；仅 owner；宠物已删→占位 200 非 500）。 */
    @GetMapping("/api/v1/orders/{orderToken}")
    public OrderDetailView detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderToken) {
        return orderCenter.getDetail(currentUserId(jwt), orderToken);
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
