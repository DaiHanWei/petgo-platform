package com.tailtopia.admin.shop.service;

import com.tailtopia.admin.shop.dto.AdminCouponForm;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.CouponValidMode;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.service.CouponAdminService;
import com.tailtopia.shop.coupon.service.CouponDraft;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 优惠券页的后台编排（V1.4.2 shop-admin Story 2.2 · AD-1）：表单 → {@link CouponDraft}、建券与建码同一事务。
 * 🔴 业务规则全在 {@link CouponAdminService}（电商线原类），这里不写第二份校验，只做形态转换与事务边界。
 */
@Service
public class AdminShopCouponService {

    /** 兑换码方式：通用码 / 批量一次性码。 */
    public static final String CODE_UNIVERSAL = "UNIVERSAL";
    public static final String CODE_BATCH = "BATCH";

    private final CouponAdminService coupons;
    private final com.tailtopia.shop.coupon.repository.UserCouponRepository userCoupons;
    private final com.tailtopia.shop.coupon.repository.ShopCouponCodeRepository codes;

    public AdminShopCouponService(CouponAdminService coupons,
            com.tailtopia.shop.coupon.repository.UserCouponRepository userCoupons,
            com.tailtopia.shop.coupon.repository.ShopCouponCodeRepository codes) {
        this.coupons = coupons;
        this.userCoupons = userCoupons;
        this.codes = codes;
    }

    /** 列表摘要（A1）：进行中 / 未开始张数、占用中（被待支付订单占用）张数、本月（WIB）带来订单金额。 */
    public record Summary(long running, long notStarted, long locked, long monthOrderAmount) {
    }

    @Transactional(readOnly = true)
    public Summary summary(List<CouponAdminService.AdminCouponRow> all, Instant now) {
        long running = all.stream().filter(r -> r.status() == CouponAdminService.AdminCouponStatus.RUNNING).count();
        long notStarted = all.stream().filter(r -> r.status() == CouponAdminService.AdminCouponStatus.NOT_STARTED).count();
        LocalDate first = dayOf(now).withDayOfMonth(1);
        long month = coupons.orderAmountBetween(startOf(first), startOf(first.plusMonths(1)));
        return new Summary(running, notStarted,
                userCoupons.countByStatus(com.tailtopia.shop.coupon.domain.UserCouponStatus.LOCKED), month);
    }

    /** 各模板已使用张数（couponId → count）。 */
    @Transactional(readOnly = true)
    public java.util.Map<Long, Long> usedByCoupon() {
        java.util.Map<Long, Long> out = new java.util.HashMap<>();
        for (Object[] r : userCoupons.countUsedGroupedByCoupon()) {
            out.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        }
        return out;
    }

    /** 停用确认（A9）要的两个数：已领未用（AVAILABLE）、被待支付订单占用（LOCKED）。 */
    public record StopCounts(long available, long locked) {
    }

    @Transactional(readOnly = true)
    public StopCounts stopCounts(ShopCoupon c) {
        return new StopCounts(
                userCoupons.countByCouponIdAndStatus(c.getId(), com.tailtopia.shop.coupon.domain.UserCouponStatus.AVAILABLE),
                userCoupons.countByCouponIdAndStatus(c.getId(), com.tailtopia.shop.coupon.domain.UserCouponStatus.LOCKED));
    }

    /** 通用码明文（详情展示给运营核对；🔒 不进日志 / 审计）。无则 null。 */
    @Transactional(readOnly = true)
    public String universalCodeOf(ShopCoupon c) {
        return codes.findByCouponIdAndSingleUseOrderByIdAsc(c.getId(), false).stream()
                .map(com.tailtopia.shop.coupon.domain.ShopCouponCode::getCode).findFirst().orElse(null);
    }

    /** 兑换码方式（展示用）：BATCH / UNIVERSAL / null（非兑换码券或尚未建码）。 */
    @Transactional(readOnly = true)
    public String codeModeOf(ShopCoupon c) {
        if (c.getAcquireType() != CouponAcquireType.CODE) {
            return null;
        }
        if (codes.countByCouponIdAndSingleUse(c.getId(), true) > 0) {
            return CODE_BATCH;
        }
        return codes.countByCouponIdAndSingleUse(c.getId(), false) > 0 ? CODE_UNIVERSAL : null;
    }

    /**
     * 新建（A3~A5）：建券 + （兑换码券）通用码 / 按总量批量生成一次性码，同一事务——任一步失败整体回滚，
     * 不会留下「有券没码」。未确认的防误配项抛 {@code CouponRiskNotAcknowledgedException}（不落库）。
     */
    @Transactional
    public ShopCoupon create(AdminCouponForm form, boolean acknowledgeRisk, Long actorAdminId) {
        CouponDraft draft = toDraft(form);
        boolean batch = draft.acquireType() == CouponAcquireType.CODE && CODE_BATCH.equals(form.getCodeMode());
        if (batch && draft.totalQuantity() == null) {
            throw AppException.validation("批量一次性码券必须填写发放总量").code("admin.err.coupon.batchNeedsTotal");
        }
        String code = null;
        if (draft.acquireType() == CouponAcquireType.CODE && !batch) {
            code = CouponAdminService.normalizeCode(form.getUniversalCode());
        }
        ShopCoupon saved = coupons.create(draft, acknowledgeRisk, actorAdminId);
        if (batch) {
            coupons.generateSingleUseCodesForTotal(saved.getPublicToken(), actorAdminId);
        } else if (code != null) {
            coupons.createUniversalCode(saved.getPublicToken(), code, actorAdminId);
        }
        return saved;
    }

    /** 编辑（A8 未开始的券改全部；已开始的只加总量，由 {@link CouponAdminService#update} 判定）。 */
    @Transactional
    public ShopCoupon update(String couponToken, AdminCouponForm form, boolean acknowledgeRisk, Long actorAdminId) {
        CouponDraft draft = toDraft(form);
        ShopCoupon current = coupons.require(couponToken);
        // 编辑入口不建兑换码：改成「兑换码」券会得到一张没有码、也不能领的券 —— 要兑换码券请新建
        if (draft.acquireType() == CouponAcquireType.CODE && current.getAcquireType() != CouponAcquireType.CODE) {
            throw AppException.validation("改为兑换码券请新建一张券").code("admin.err.coupon.codeNeedsNew");
        }
        return coupons.update(couponToken, draft, acknowledgeRisk, actorAdminId);
    }

    /** 表单 → 草稿。日期按 WIB：开始日 00:00，结束日含当天（存为次日 00:00）。 */
    public static CouponDraft toDraft(AdminCouponForm f) {
        CouponKind kind = parse(CouponKind.class, f.getKind(), "券类型");
        CouponAcquireType acquire = parse(CouponAcquireType.class, f.getAcquireType(), "发放方式");
        CouponValidMode validMode = parse(CouponValidMode.class, f.getValidMode(), "使用有效期");
        CouponScopeType scope = f.getScopeType() == null || f.getScopeType().isBlank()
                ? CouponScopeType.ALL : parse(CouponScopeType.class, f.getScopeType(), "适用范围");
        List<String> scopeValues = switch (scope) {
            case ALL -> List.of();
            case CATEGORY -> f.getScopeCategories() == null ? List.of() : List.copyOf(f.getScopeCategories());
            case PRODUCT -> f.getScopeProducts() == null ? List.of()
                    : Arrays.stream(f.getScopeProducts().split("[,，\\s]+")).filter(s -> !s.isBlank()).toList();
        };
        boolean fixed = validMode == CouponValidMode.FIXED;
        return new CouponDraft(kind, f.getNameId(), f.getNameEn(), f.getAmount(), f.getMinSpend(), scope, scopeValues,
                acquire, startOf(f.getIssueStart()), endOf(f.getIssueEnd()), validMode,
                fixed ? startOf(f.getValidStart()) : null, fixed ? endOf(f.getValidEnd()) : null,
                fixed ? null : f.getValidDays(), f.getTotalQuantity(), f.getPerUserLimit());
    }

    /** 券 → 表单（编辑回填）。 */
    public static AdminCouponForm toForm(ShopCoupon c) {
        AdminCouponForm f = new AdminCouponForm();
        f.setKind(c.getKind().name());
        f.setNameId(c.getNameId());
        f.setNameEn(c.getNameEn());
        f.setAmount(c.getAmount());
        f.setMinSpend(c.getMinSpend());
        f.setAcquireType(c.getAcquireType().name());
        f.setIssueStart(dayOf(c.getIssueStartAt()));
        f.setIssueEnd(lastDayOf(c.getIssueEndAt()));
        f.setValidMode(c.getValidMode().name());
        f.setValidStart(c.getValidStartAt() == null ? null : dayOf(c.getValidStartAt()));
        f.setValidEnd(c.getValidEndAt() == null ? null : lastDayOf(c.getValidEndAt()));
        f.setValidDays(c.getValidDays());
        f.setTotalQuantity(c.getTotalQuantity());
        f.setPerUserLimit(c.getPerUserLimit());
        f.setScopeType(c.getScopeType().name());
        if (c.getScopeType() == CouponScopeType.CATEGORY) {
            f.setScopeCategories(List.copyOf(c.getScopeValues()));
        } else if (c.getScopeType() == CouponScopeType.PRODUCT) {
            f.setScopeProducts(String.join(", ", c.getScopeValues()));
        }
        return f;
    }

    static Instant startOf(LocalDate d) {
        return d == null ? null : d.atStartOfDay(ScheduleWindow.WIB).toInstant();
    }

    static Instant endOf(LocalDate d) {
        return d == null ? null : d.plusDays(1).atStartOfDay(ScheduleWindow.WIB).toInstant();
    }

    /** WIB 自然日。 */
    public static LocalDate dayOf(Instant t) {
        return LocalDate.ofInstant(t, ScheduleWindow.WIB);
    }

    /** 左闭右开的结束时刻 → 含当天的最后一天（00:00 整点落在次日，减一天）。 */
    public static LocalDate lastDayOf(Instant exclusiveEnd) {
        java.time.ZonedDateTime z = exclusiveEnd.atZone(ScheduleWindow.WIB);
        LocalDate d = z.toLocalDate();
        return z.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? d.minusDays(1) : d;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw AppException.validation(field + "不能为空").code("admin.err.v142.coupon.optionRequired");
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw AppException.validation(field + "不正确").code("admin.err.v142.coupon.optionRequired");
        }
    }
}
