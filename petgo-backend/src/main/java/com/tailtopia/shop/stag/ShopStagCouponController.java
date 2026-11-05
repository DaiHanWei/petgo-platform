package com.tailtopia.shop.stag;

import com.tailtopia.admin.shared.StagOnly;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.CouponValidMode;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.service.CouponAdminService;
import com.tailtopia.shop.coupon.service.CouponDraft;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 券的 stag 测试入口（v1.4.2 shop Story 2.1 AC9，架构 D-24 / S-2）。
 *
 * <p>🔴 {@link StagOnly}（{@code @Profile("stag")}）：生产不注册，路由根本不存在。挂在后台链 {@code /admin/**}
 * 下（会话登录 + {@code SUPER_ADMIN}，与 {@code AdminPaymentSimulateController} 同）；后台链只对
 * {@code /admin/stag/**} 免 CSRF，便于验收时带后台会话 cookie 用 curl 调用。
 *
 * <p>只做 {@link CouponAdminService} 的薄包装，不写业务逻辑；操作人传 null（不写审计）。
 * 后台线落地后删除或保留为 stag 工具。
 */
@RestController
@StagOnly
public class ShopStagCouponController {

    private static final String AUTH = "hasRole('SUPER_ADMIN')";

    private final CouponAdminService admin;

    public ShopStagCouponController(CouponAdminService admin) {
        this.admin = admin;
    }

    /** 建券请求：{@link CouponDraft} 字段（时间为带时区 ISO-8601）+ 可选通用码 / 一次性码数量。 */
    public record CreateRequest(
            CouponKind kind, String nameId, String nameEn, Long amount, Long minSpend,
            CouponScopeType scopeType, List<String> scopeValues, CouponAcquireType acquireType,
            OffsetDateTime issueStartAt, OffsetDateTime issueEndAt, CouponValidMode validMode,
            OffsetDateTime validStartAt, OffsetDateTime validEndAt, Integer validDays,
            Integer totalQuantity, Integer perUserLimit,
            String universalCode, Integer singleUseCodeCount) {

        CouponDraft toDraft() {
            return new CouponDraft(kind, nameId, nameEn, amount, minSpend, scopeType, scopeValues,
                    acquireType, instant(issueStartAt), instant(issueEndAt), validMode,
                    instant(validStartAt), instant(validEndAt), validDays, totalQuantity, perUserLimit);
        }

        private static Instant instant(OffsetDateTime t) {
            return t == null ? null : t.toInstant();
        }
    }

    public record CreateResponse(String couponToken, List<String> codes) {
    }

    public record StopRequest(boolean voidUnused) {
    }

    @PostMapping("/admin/stag/shop/coupons")
    @PreAuthorize(AUTH)
    @Transactional
    public CreateResponse create(@RequestBody CreateRequest req) {
        ShopCoupon coupon = admin.create(req.toDraft(), null);
        List<String> codes = new ArrayList<>();
        if (req.universalCode() != null && !req.universalCode().isBlank()) {
            codes.add(admin.createUniversalCode(coupon.getPublicToken(), req.universalCode(), null));
        }
        if (req.singleUseCodeCount() != null && req.singleUseCodeCount() > 0) {
            codes.addAll(admin.generateSingleUseCodes(coupon.getPublicToken(), req.singleUseCodeCount(), null));
        }
        return new CreateResponse(coupon.getPublicToken(), codes);
    }

    @PostMapping("/admin/stag/shop/coupons/{couponToken}/stop")
    @PreAuthorize(AUTH)
    public void stop(@PathVariable String couponToken, @RequestBody StopRequest req) {
        admin.stop(couponToken, req.voidUnused(), null);
    }
}
