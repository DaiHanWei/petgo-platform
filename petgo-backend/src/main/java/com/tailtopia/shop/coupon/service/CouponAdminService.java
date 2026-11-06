package com.tailtopia.shop.coupon.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.CouponStatus;
import com.tailtopia.shop.coupon.domain.CouponValidMode;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.ShopCouponCode;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import com.tailtopia.shop.coupon.repository.ShopCouponCodeRepository;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 券模板的建 / 停与兑换码（v1.4.2 shop Story 2.1 AC3；S-2：后台业务 service 归本线，后台线只做 Controller + 页面）。
 *
 * <p>校验失败抛 {@code AppException.validation}，detail 用中文（给后台运营看）。
 * {@code actorAdminId == null} 只有 D-24 stag 入口会这样 → 不写审计、INFO 一行。
 * 🔴 审计摘要与日志<b>不含兑换码明文</b>。
 */
@Service
public class CouponAdminService {

    private static final Logger log = LoggerFactory.getLogger(CouponAdminService.class);

    static final String AUDIT_TARGET = "SHOP_COUPON";
    static final int NAME_MAX = 60;
    static final int SCOPE_VALUES_MAX = 200;
    static final int SINGLE_USE_BATCH_MAX = 1000;
    static final int SINGLE_USE_CODE_LENGTH = 10;
    /** 一次性码字符集：去掉易混的 0 / O / 1 / I。 */
    static final char[] SINGLE_USE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    static final Pattern CODE_FORMAT = Pattern.compile("^[A-Z0-9]{4,32}$");
    private static final int GENERATE_MAX_ROUNDS = 20;

    private final ShopCouponRepository coupons;
    private final ShopCouponCodeRepository codes;
    private final ShopProductRepository products;
    private final CouponLedgerService ledger;
    private final com.tailtopia.shop.coupon.repository.UserCouponRepository userCoupons;
    private final CouponNewbieLock newbieLock;
    private CouponRowLock rowLock;
    private final ShopTokenGenerator tokens;
    private final AdminAuditService audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public CouponAdminService(ShopCouponRepository coupons, ShopCouponCodeRepository codes,
            ShopProductRepository products, CouponLedgerService ledger,
            com.tailtopia.shop.coupon.repository.UserCouponRepository userCoupons, CouponNewbieLock newbieLock,
            ShopTokenGenerator tokens, AdminAuditService audit) {
        this(coupons, codes, products, ledger, userCoupons, newbieLock, tokens, audit, Clock.systemUTC());
    }

    /** 测试注入固定时钟。 */
    CouponAdminService(ShopCouponRepository coupons, ShopCouponCodeRepository codes,
            ShopProductRepository products, CouponLedgerService ledger,
            com.tailtopia.shop.coupon.repository.UserCouponRepository userCoupons, CouponNewbieLock newbieLock,
            ShopTokenGenerator tokens, AdminAuditService audit, Clock clock) {
        this.coupons = coupons;
        this.codes = codes;
        this.products = products;
        this.ledger = ledger;
        this.userCoupons = userCoupons;
        this.newbieLock = newbieLock;
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
    }

    /** V1.4.2 shop-admin Story 2.1：编辑 / 加总量 / 补码前加行锁并刷新（setter 注入，保留原构造器签名）。 */
    @org.springframework.beans.factory.annotation.Autowired
    void setRowLock(CouponRowLock rowLock) {
        this.rowLock = rowLock;
    }

    // ------------------------------------------------------------------ 建券

    /** 校验后保存（ACTIVE），不做防误配确认（D-24 stag 入口沿用）。新人券在同一事务内先加 advisory 锁再查发放期重叠。 */
    @Transactional
    public ShopCoupon create(CouponDraft draft, Long actorAdminId) {
        return create(draft, true, actorAdminId);
    }

    /**
     * 后台建券（V1.4.2 shop-admin Story 2.1 · AD-6 / O-14）：校验 → 防误配（门槛为 0、总量不限）未带
     * {@code acknowledgeRisk} 时抛 {@link CouponRiskNotAcknowledgedException}（带风险列表，不落库）→ 保存。
     * 面额 ≥ 门槛的满减券硬拒（O-14），不走确认。
     */
    @Transactional
    public ShopCoupon create(CouponDraft draft, boolean acknowledgeRisk, Long actorAdminId) {
        Validated v = validate(draft);
        requireRiskAcknowledged(v, acknowledgeRisk);
        if (v.acquireType() == CouponAcquireType.NEWBIE) {
            newbieLock.acquire();
            rejectNewbieOverlap(v, null);
        }
        ShopCoupon saved = coupons.save(ShopCoupon.create(tokens.generate(), v.kind(), v.nameId(), v.nameEn(),
                v.amount(), v.minSpend(), v.scopeType(), v.scopeValues(), v.acquireType(), v.issueStart(),
                v.issueEnd(), v.validMode(), v.validStart(), v.validEnd(), v.validDays(), v.totalQuantity(),
                v.perUserLimit()));
        audit(actorAdminId, AuditActions.SHOP_COUPON_CREATED, saved.getPublicToken(),
                "kind=" + v.kind() + " acquire=" + v.acquireType() + " amount=" + v.amount() + " minSpend=" + v.minSpend()
                        + " scope=" + v.scopeType() + " total=" + qty(v.totalQuantity())
                        + " perUser=" + v.perUserLimit());
        return saved;
    }

    /** 校验通过后的规范化字段（建券与编辑共用一套校验）。 */
    record Validated(CouponKind kind, String nameId, String nameEn, long amount, long minSpend,
            CouponScopeType scopeType, List<String> scopeValues, CouponAcquireType acquireType,
            Instant issueStart, Instant issueEnd, CouponValidMode validMode, Instant validStart,
            Instant validEnd, Integer validDays, Integer totalQuantity, int perUserLimit) {
    }

    /** 防误配项（A7 / O-14；「面额 ≥ 门槛」已改为硬拒，不在此列）。 */
    public enum CouponRisk {
        /** 门槛为 0：任何订单都能用。 */
        MIN_SPEND_ZERO,
        /**
         * 发放总量不限（新人券除外）。⚠️ 每人限领在库里恒 ≥ 1（{@code ck_shop_coupons_per_user}），
         * PRD「每人不限」本版不可表达，故以「总量不限」作为该风险项（Completion Notes 待确认）。
         */
        UNLIMITED_QUANTITY
    }

    /** 未确认防误配项时抛出：不落库，调用方据 {@link #risks()} 弹 confirm-modal，确认后带 acknowledgeRisk 重提。 */
    public static class CouponRiskNotAcknowledgedException extends RuntimeException {
        private final transient List<CouponRisk> risks;

        public CouponRiskNotAcknowledgedException(List<CouponRisk> risks) {
            super("券配置有风险项，需确认后保存");
            this.risks = List.copyOf(risks);
        }

        public List<CouponRisk> risks() {
            return risks;
        }
    }

    /** 防误配判定（纯函数）。 */
    static List<CouponRisk> risksOf(Validated v) {
        List<CouponRisk> out = new ArrayList<>();
        if (v.minSpend() == 0) {
            out.add(CouponRisk.MIN_SPEND_ZERO);
        }
        if (v.totalQuantity() == null && v.acquireType() != CouponAcquireType.NEWBIE) {
            out.add(CouponRisk.UNLIMITED_QUANTITY);
        }
        return out;
    }

    /** 只校验、不落库，返回防误配项（后台保存前预检用）。 */
    public List<CouponRisk> risks(CouponDraft draft) {
        return risksOf(validate(draft));
    }

    private static void requireRiskAcknowledged(Validated v, boolean acknowledgeRisk) {
        List<CouponRisk> risks = risksOf(v);
        if (!acknowledgeRisk && !risks.isEmpty()) {
            throw new CouponRiskNotAcknowledgedException(risks);
        }
    }

    private void rejectNewbieOverlap(Validated v, Long excludeId) {
        // 建券沿用电商线的 exists 查询；编辑时排除自身。重叠时再取那张券给报错文案带名称与发放期（A6）。
        boolean overlap = excludeId == null
                ? coupons.existsActiveNewbieOverlapping(v.issueStart(), v.issueEnd())
                : !coupons.findActiveNewbieOverlapping(v.issueStart(), v.issueEnd(), excludeId).isEmpty();
        if (overlap) {
            ShopCoupon other = coupons.findActiveNewbieOverlapping(v.issueStart(), v.issueEnd(), excludeId)
                    .stream().findFirst().orElse(null);
            AppException e = AppException.conflict("已有发放期重叠的新人券");
            if (other != null) {
                java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                        .withZone(com.tailtopia.shared.schedule.ScheduleWindow.WIB);
                e = e.code("admin.err.coupon.newbieOverlap", other.getNameId(),
                        f.format(other.getIssueStartAt()), f.format(other.getIssueEndAt()));
            }
            throw e;
        }
    }

    /** 建券 / 编辑共用校验（AB-24：发放期必填、固定有效期结束不早于发放结束、有效期开始不晚于发放开始、免运券最高减免必填…）。 */
    Validated validate(CouponDraft draft) {
        return validate(draft, null);
    }

    /**
     * @param current 编辑时的现券：适用范围与现值相同则跳过「商品是否存在」检查（范围没改，下架的商品不该挡住加总量）
     */
    Validated validate(CouponDraft draft, ShopCoupon current) {
        if (draft == null) {
            throw AppException.validation("券信息不能为空");
        }
        CouponKind kind = require(draft.kind(), "券类型");
        CouponAcquireType acquireType = require(draft.acquireType(), "获取方式");
        CouponValidMode validMode = require(draft.validMode(), "有效期模式");
        String nameId = name(draft.nameId(), "印尼语券名");
        String nameEn = name(draft.nameEn(), "英文券名");

        // 免运券的 amount 即「最高减免金额」（每张封顶），必填
        long amount = require(draft.amount(), kind == CouponKind.FREE_SHIPPING ? "最高减免金额" : "面额");
        long minSpend = draft.minSpend() == null ? 0L : draft.minSpend();
        if (amount <= 0) {
            throw AppException.validation("面额必须大于 0");
        }
        if (minSpend < 0) {
            throw AppException.validation("使用门槛不能为负");
        }
        // 满减券：有门槛时面额必须小于门槛（min_spend = 0 无门槛走防误配确认）；免运券 amount 即每张封顶，不受此限。O-14 硬拒。
        if (kind == CouponKind.DISCOUNT && minSpend > 0 && amount >= minSpend) {
            throw AppException.validation("满减券面额必须小于使用门槛");
        }

        CouponScopeType scopeType = draft.scopeType() == null ? CouponScopeType.ALL : draft.scopeType();
        List<String> scopeValues = current != null && current.getScopeType() == scopeType
                && sameScopeValues(current.getScopeValues(), draft.scopeValues())
                ? List.copyOf(current.getScopeValues()) : validateScope(scopeType, draft.scopeValues());

        Instant issueStart = require(draft.issueStartAt(), "发放开始时间");
        Instant issueEnd = require(draft.issueEndAt(), "发放结束时间");
        if (!issueEnd.isAfter(issueStart)) {
            throw AppException.validation("发放结束时间必须晚于开始时间");
        }
        Instant validStart = draft.validStartAt();
        Instant validEnd = draft.validEndAt();
        Integer validDays = draft.validDays();
        if (validMode == CouponValidMode.FIXED) {
            if (validStart == null || validEnd == null) {
                throw AppException.validation("固定有效期必须填写起止时间");
            }
            if (validDays != null) {
                throw AppException.validation("固定有效期不能同时填写领取后天数");
            }
            if (!validEnd.isAfter(validStart)) {
                throw AppException.validation("有效期结束时间必须晚于开始时间");
            }
            // 领到即可用：不存在「已领但未生效」的券（计价器没有「尚未生效」这一不可用原因）。
            if (validStart.isAfter(issueStart)) {
                throw AppException.validation("有效期开始时间不能晚于发放开始时间");
            }
            if (issueEnd.isAfter(validEnd)) {
                throw AppException.validation("发放结束时间不能晚于有效期结束时间");
            }
        } else {
            if (validDays == null || validDays < 1) {
                throw AppException.validation("领取后有效天数必须至少 1 天");
            }
            if (validStart != null || validEnd != null) {
                throw AppException.validation("领取后 N 天的券不能填写固定起止时间");
            }
        }

        Integer totalQuantity = draft.totalQuantity();
        if (totalQuantity != null && totalQuantity < 1) {
            throw AppException.validation("发放总量至少为 1（不限请留空）");
        }
        int perUserLimit = draft.perUserLimit() == null ? 1 : draft.perUserLimit();
        if (acquireType == CouponAcquireType.NEWBIE) {
            perUserLimit = 1; // 新人券每人只能 1 张（FR-130.5），强制覆盖
        }
        if (perUserLimit < 1) {
            throw AppException.validation("每人限领至少为 1");
        }
        return new Validated(kind, nameId, nameEn, amount, minSpend, scopeType, scopeValues, acquireType,
                issueStart, issueEnd, validMode,
                validMode == CouponValidMode.FIXED ? validStart : null,
                validMode == CouponValidMode.FIXED ? validEnd : null,
                validMode == CouponValidMode.RELATIVE ? validDays : null,
                totalQuantity, perUserLimit);
    }

    // ------------------------------------------------------------------ 编辑 / 加总量（V1.4.2 shop-admin Story 2.1）

    /**
     * 编辑券（AD-6 / AC1）。发放期<b>未开始</b>：可改全部参数（同建券校验与防误配；改类型 / 发放方式只限未发放、
     * 无兑换码的券，O-32）。发放期<b>已开始</b>：只能加总量（只增不减），其它字段与现值不同即拒绝。已停用的券不能编辑。
     * 摘要只写有变化的字段（{@code 字段: 旧→新}，O-11）。
     */
    @Transactional
    public ShopCoupon update(String couponToken, CouponDraft draft, boolean acknowledgeRisk, Long actorAdminId) {
        ShopCoupon coupon = lockedByToken(couponToken);
        if (coupon.getStatus() != CouponStatus.ACTIVE) {
            throw AppException.validation("已停用的券不能编辑");
        }
        Validated v = validate(draft, coupon);
        Instant now = clock.instant();
        if (!now.isBefore(coupon.getIssueStartAt())) {
            if (!sameExceptTotal(coupon, v)) {
                throw AppException.validation("这张券已开始发放：只能增加发放总量或停用").code("admin.err.coupon.startedReadOnly");
            }
            return raiseTotalQuantityLocked(coupon, v.totalQuantity(), acknowledgeRisk, actorAdminId);
        }
        requireRiskAcknowledged(v, acknowledgeRisk);
        boolean kindOrAcquireChanged = v.kind() != coupon.getKind() || v.acquireType() != coupon.getAcquireType();
        if (kindOrAcquireChanged && coupon.getIssuedCount() > 0) {
            throw AppException.validation("已有用户领取的券不能修改类型或发放方式");
        }
        if (v.acquireType() != coupon.getAcquireType() && codes.existsByCouponId(coupon.getId())) {
            throw AppException.validation("已生成兑换码的券不能修改发放方式");
        }
        long singleUse = codes.countByCouponIdAndSingleUse(coupon.getId(), true);
        if (singleUse > 0 && (v.totalQuantity() == null || v.totalQuantity() < singleUse)) {
            throw AppException.validation("已生成一次性兑换码的券，发放总量不能少于已生成的码数");
        }
        if (v.acquireType() == CouponAcquireType.NEWBIE) {
            newbieLock.acquire();
            rejectNewbieOverlap(v, coupon.getId());
        }
        String diff = diffSummary(coupon, v);
        coupon.applyEdit(v.kind(), v.nameId(), v.nameEn(), v.amount(), v.minSpend(), v.scopeType(), v.scopeValues(),
                v.acquireType(), v.issueStart(), v.issueEnd(), v.validMode(), v.validStart(), v.validEnd(),
                v.validDays(), v.totalQuantity(), v.perUserLimit());
        ShopCoupon saved = coupons.saveAndFlush(coupon);
        int added = singleUse > 0 ? topUpSingleUseCodes(saved) : 0;
        if (!diff.isEmpty()) {
            audit(actorAdminId, AuditActions.SHOP_COUPON_UPDATED, saved.getPublicToken(),
                    diff + (added > 0 ? " codesAdded=" + added : ""));
        }
        return saved;
    }

    /**
     * 加发放总量（AD-6 / AC1 / AC7）：只增不减（{@code null} = 不限，视为增加；批量码券不能改为不限）。
     * 批量一次性码券同一事务补生成差额兑换码（每批 ≤ {@value #SINGLE_USE_BATCH_MAX}，审计合并记总数）。
     */
    @Transactional
    public ShopCoupon raiseTotalQuantity(String couponToken, Integer newTotal, boolean acknowledgeRisk,
            Long actorAdminId) {
        ShopCoupon coupon = lockedByToken(couponToken);
        if (coupon.getStatus() != CouponStatus.ACTIVE) {
            throw AppException.validation("已停用的券不能修改");
        }
        return raiseTotalQuantityLocked(coupon, newTotal, acknowledgeRisk, actorAdminId);
    }

    private ShopCoupon raiseTotalQuantityLocked(ShopCoupon coupon, Integer newTotal, boolean acknowledgeRisk,
            Long actorAdminId) {
        Integer old = coupon.getTotalQuantity();
        if (java.util.Objects.equals(old, newTotal)) {
            return coupon;
        }
        // 改为「不限」同样是防误配项（A7），未确认不落库
        if (newTotal == null && coupon.getAcquireType() != CouponAcquireType.NEWBIE && !acknowledgeRisk) {
            throw new CouponRiskNotAcknowledgedException(List.of(CouponRisk.UNLIMITED_QUANTITY));
        }
        if (newTotal != null && newTotal < 1) {
            throw AppException.validation("发放总量至少为 1（不限请留空）");
        }
        if (old == null || (newTotal != null && newTotal < old)) {
            throw AppException.validation("发放总量只能增加").code("admin.err.coupon.quantityOnlyUp");
        }
        long singleUse = codes.countByCouponIdAndSingleUse(coupon.getId(), true);
        if (newTotal == null && singleUse > 0) {
            throw AppException.validation("批量一次性码券的发放总量不能改为不限");
        }
        coupon.raiseTotalQuantity(newTotal);
        ShopCoupon saved = coupons.saveAndFlush(coupon);
        int added = singleUse > 0 ? topUpSingleUseCodes(saved) : 0;
        audit(actorAdminId, AuditActions.SHOP_COUPON_QUOTA_INCREASED, saved.getPublicToken(),
                "total: " + qty(old) + "→" + qty(newTotal) + (added > 0 ? " codesAdded=" + added : ""));
        return saved;
    }

    /** 批量码券：把一次性码补到 = 发放总量（AC7）。返回新生成数。 */
    private int topUpSingleUseCodes(ShopCoupon coupon) {
        Integer total = coupon.getTotalQuantity();
        if (total == null) {
            return 0;
        }
        long have = codes.countByCouponIdAndSingleUse(coupon.getId(), true);
        int need = (int) Math.max(0, total - have);
        for (int left = need; left > 0; left -= SINGLE_USE_BATCH_MAX) {
            generateCodes(coupon, Math.min(left, SINGLE_USE_BATCH_MAX));
        }
        return need;
    }

    /**
     * 批量码券：按发放总量生成一次性码（AC7「生成数量 = 总量」；建券后同一事务调用）。总量为空拒绝（批量码不能不限，O-32）；
     * 超过 {@value #SINGLE_USE_BATCH_MAX} 分批生成，审计合并记一条总数。已生成的不重复生成。
     */
    @Transactional
    public int generateSingleUseCodesForTotal(String couponToken, Long actorAdminId) {
        codeCoupon(couponToken);
        // 行锁串行：并发「生成」或与加总量并发时只补一次差额
        ShopCoupon coupon = lockedByToken(couponToken);
        if (coupon.getStatus() != CouponStatus.ACTIVE) {
            throw AppException.validation("已停用的券不能生成兑换码");
        }
        if (coupon.getTotalQuantity() == null) {
            throw AppException.validation("批量一次性码券必须填写发放总量").code("admin.err.coupon.batchNeedsTotal");
        }
        if (codes.countByCouponIdAndSingleUse(coupon.getId(), false) > 0) {
            throw AppException.validation("这张券已有通用码，不能再生成一次性码");
        }
        int added = topUpSingleUseCodes(coupon);
        if (added > 0) {
            audit(actorAdminId, AuditActions.SHOP_COUPON_CODES_CREATED, coupon.getPublicToken(),
                    "singleUse=true count=" + added);
        }
        return added;
    }

    private static boolean sameScopeValues(List<String> current, List<String> raw) {
        Set<String> a = new LinkedHashSet<>(current == null ? List.of() : current);
        Set<String> b = new LinkedHashSet<>(raw == null ? List.of()
                : raw.stream().filter(x -> x != null && !x.isBlank()).map(String::trim).toList());
        return a.equals(b);
    }

    private static boolean sameExceptTotal(ShopCoupon c, Validated v) {
        return c.getKind() == v.kind() && c.getNameId().equals(v.nameId()) && c.getNameEn().equals(v.nameEn())
                && c.getAmount() == v.amount() && c.getMinSpend() == v.minSpend() && c.getScopeType() == v.scopeType()
                && new LinkedHashSet<>(c.getScopeValues()).equals(new LinkedHashSet<>(v.scopeValues()))
                && c.getAcquireType() == v.acquireType() && c.getIssueStartAt().equals(v.issueStart())
                && c.getIssueEndAt().equals(v.issueEnd()) && c.getValidMode() == v.validMode()
                && java.util.Objects.equals(c.getValidStartAt(), v.validStart())
                && java.util.Objects.equals(c.getValidEndAt(), v.validEnd())
                && java.util.Objects.equals(c.getValidDays(), v.validDays())
                && c.getPerUserLimit() == v.perUserLimit();
    }

    /** O-11：只列有变化的字段 {@code 字段: 旧→新}，空串 = 无变化。 */
    static String diffSummary(ShopCoupon c, Validated v) {
        List<String> parts = new ArrayList<>();
        diff(parts, "kind", c.getKind(), v.kind());
        diff(parts, "nameId", c.getNameId(), v.nameId());
        diff(parts, "nameEn", c.getNameEn(), v.nameEn());
        diff(parts, "amount", c.getAmount(), v.amount());
        diff(parts, "minSpend", c.getMinSpend(), v.minSpend());
        diff(parts, "scope", c.getScopeType() + String.valueOf(c.getScopeValues()),
                v.scopeType() + String.valueOf(v.scopeValues()));
        diff(parts, "acquire", c.getAcquireType(), v.acquireType());
        diff(parts, "issueStart", c.getIssueStartAt(), v.issueStart());
        diff(parts, "issueEnd", c.getIssueEndAt(), v.issueEnd());
        diff(parts, "validMode", c.getValidMode(), v.validMode());
        diff(parts, "validStart", c.getValidStartAt(), v.validStart());
        diff(parts, "validEnd", c.getValidEndAt(), v.validEnd());
        diff(parts, "validDays", c.getValidDays(), v.validDays());
        diff(parts, "total", qty(c.getTotalQuantity()), qty(v.totalQuantity()));
        diff(parts, "perUser", c.getPerUserLimit(), v.perUserLimit());
        return String.join(" ", parts);
    }

    private static void diff(List<String> parts, String field, Object old, Object now) {
        if (!java.util.Objects.equals(old, now)) {
            parts.add(field + ": " + (old == null ? "—" : old) + "→" + (now == null ? "—" : now));
        }
    }

    private static String qty(Integer total) {
        return total == null ? "UNLIMITED" : String.valueOf(total);
    }

    /** 加行锁并<b>刷新</b>（同 {@link CouponRowLock#lockFresh}：先按 token 读过的实例在一级缓存里，直接 FOR UPDATE 会拿回旧值）。 */
    private ShopCoupon lockedByToken(String couponToken) {
        ShopCoupon c = coupons.findByPublicToken(couponToken).orElseThrow(() -> AppException.notFound("券不存在"));
        return rowLock.lockFresh(c.getId()).orElseThrow(() -> AppException.notFound("券不存在"));
    }

    // ------------------------------------------------------------------ 列表 / 详情数据（V1.4.2 shop-admin Story 2.1）

    /** 列表状态（现算，AD-6）：已停用 / 未开始 / 进行中 / 已结束（按发放期）。 */
    public enum AdminCouponStatus {
        NOT_STARTED, RUNNING, ENDED, STOPPED
    }

    /** 列表状态纯函数。 */
    public static AdminCouponStatus statusOf(ShopCoupon c, Instant now) {
        if (c.getStatus() == CouponStatus.STOPPED) {
            return AdminCouponStatus.STOPPED;
        }
        if (now.isBefore(c.getIssueStartAt())) {
            return AdminCouponStatus.NOT_STARTED;
        }
        return now.isBefore(c.getIssueEndAt()) ? AdminCouponStatus.RUNNING : AdminCouponStatus.ENDED;
    }

    /** 列表一行：券 + 现算状态。 */
    public record AdminCouponRow(ShopCoupon coupon, AdminCouponStatus status) {
    }

    /** 后台列表（AC4）：按现算状态 / 类型 / 发放方式筛选；任一条件为 null = 不限。新建在前。 */
    @Transactional(readOnly = true)
    public List<AdminCouponRow> listForAdmin(AdminCouponStatus status, CouponKind kind, CouponAcquireType acquireType) {
        Instant now = clock.instant();
        return coupons.findForAdmin(kind, acquireType).stream()
                .map(c -> new AdminCouponRow(c, statusOf(c, now)))
                .filter(r -> status == null || r.status() == status)
                .toList();
    }

    /** 券详情四格（AC5 / A10）：已领、占用中、已使用、带来的订单金额（用该券且已付款订单的 {@code total_amount} 合计）。 */
    public record CouponStats(long claimed, long locked, long used, long orderAmount) {
    }

    @Transactional(readOnly = true)
    public CouponStats detailStats(String couponToken) {
        ShopCoupon c = coupons.findByPublicToken(couponToken).orElseThrow(() -> AppException.notFound("券不存在"));
        return new CouponStats(userCoupons.countByCouponId(c.getId()),
                userCoupons.countByCouponIdAndStatus(c.getId(), UserCouponStatus.LOCKED),
                userCoupons.countByCouponIdAndStatus(c.getId(), UserCouponStatus.USED),
                userCoupons.sumPaidOrderAmountUsingCoupon(c.getId(), null, null));
    }

    /** 全部券在一段时间内带来的已付款订单金额（列表摘要「本月带来订单金额」，按 {@code paid_at}）。 */
    @Transactional(readOnly = true)
    public long orderAmountBetween(Instant from, Instant to) {
        return userCoupons.sumPaidOrderAmountUsingCoupon(null, from, to);
    }

    /** 导出一行（码 + 是否已兑换）。 */
    public record CodeExportRow(String code, boolean redeemed, Instant redeemedAt) {
    }

    /**
     * 导出批量一次性码（AD-6 / A10；Controller 要求 {@code shop.coupon_edit}）。审计只记数量，🔒 不记码明文、不记日志。
     */
    @Transactional
    public List<CodeExportRow> exportSingleUseCodes(String couponToken, Long actorAdminId) {
        ShopCoupon coupon = codeCoupon(couponToken);
        List<CodeExportRow> rows = codes.findByCouponIdAndSingleUseOrderByIdAsc(coupon.getId(), true).stream()
                .map(c -> new CodeExportRow(c.getCode(), c.getRedeemedAt() != null, c.getRedeemedAt()))
                .toList();
        audit(actorAdminId, AuditActions.SHOP_COUPON_CODES_EXPORTED, coupon.getPublicToken(), "count=" + rows.size());
        return rows;
    }

    private List<String> validateScope(CouponScopeType scopeType, List<String> raw) {
        List<String> values = new ArrayList<>(new LinkedHashSet<>(
                raw == null ? List.of() : raw.stream().filter(v -> v != null && !v.isBlank())
                        .map(String::trim).toList()));
        if (scopeType == CouponScopeType.ALL) {
            if (!values.isEmpty()) {
                throw AppException.validation("全场券不能指定适用范围");
            }
            return values;
        }
        if (values.isEmpty()) {
            throw AppException.validation("指定范围的券必须至少选一项");
        }
        if (values.size() > SCOPE_VALUES_MAX) {
            throw AppException.validation("适用范围最多 " + SCOPE_VALUES_MAX + " 项");
        }
        if (scopeType == CouponScopeType.CATEGORY) {
            Set<String> allowed = Arrays.stream(ProductCategory.values()).map(Enum::name)
                    .collect(Collectors.toSet());
            if (!allowed.containsAll(values)) {
                throw AppException.validation("适用品类不存在");
            }
        } else {
            Set<String> existing = products.findExistingPublicTokens(values);
            if (existing == null || !existing.containsAll(values)) {
                throw AppException.validation("适用商品不存在");
            }
        }
        return values;
    }

    // ------------------------------------------------------------------ 停券

    /**
     * ACTIVE → STOPPED（D-3）；{@code voidUnused=true} 时已领未用（AVAILABLE）→ VOIDED，LOCKED / USED 不动
     * （其订单之后取消时转 VOIDED 不退回）。已 STOPPED 再停 → 幂等返回，不改 {@code void_on_stop}。
     */
    @Transactional
    public void stop(String couponToken, boolean voidUnused, Long actorAdminId) {
        ShopCoupon coupon = coupons.findByPublicToken(couponToken)
                .orElseThrow(() -> AppException.notFound("券不存在"));
        Instant now = clock.instant();
        // 条件更新而非读后写：并发停券只有影响 1 行的那个继续作废 / 审计，其余幂等返回（复审修复）。
        if (coupon.getStatus() != CouponStatus.ACTIVE
                || coupons.stopIfActive(coupon.getId(), voidUnused, now) == 0) {
            return;
        }
        int voided = voidUnused ? ledger.voidUnused(coupon.getId(), now) : 0;
        audit(actorAdminId, AuditActions.SHOP_COUPON_STOPPED, coupon.getPublicToken(),
                "voidUnused=" + voidUnused + " voided=" + voided);
    }

    // ------------------------------------------------------------------ 兑换码

    /** 通用码（{@code single_use=false}，一张码多人兑换，受每人限领与总量约束）。返回规范化后的码。 */
    @Transactional
    public String createUniversalCode(String couponToken, String code, Long actorAdminId) {
        ShopCoupon coupon = codeCoupon(couponToken);
        String normalized = normalizeCode(code);
        if (codes.existsByCode(normalized)) {
            throw AppException.conflict("兑换码已存在");
        }
        try {
            // saveAndFlush：让并发撞 uq_shop_coupon_codes_code 在这里抛出、转 409，而不是提交时变 500
            codes.saveAndFlush(ShopCouponCode.of(coupon.getId(), normalized, false));
        } catch (DataIntegrityViolationException e) {
            throw AppException.conflict("兑换码已存在");
        }
        audit(actorAdminId, AuditActions.SHOP_COUPON_CODES_CREATED, coupon.getPublicToken(),
                "singleUse=false count=1");
        return normalized;
    }

    /** 批量生成一次性码（{@code 1 ≤ count ≤ 1000}，10 位，去易混字符，{@link SecureRandom}，撞码重试）。 */
    @Transactional
    public List<String> generateSingleUseCodes(String couponToken, int count, Long actorAdminId) {
        if (count < 1 || count > SINGLE_USE_BATCH_MAX) {
            throw AppException.validation("一次性码数量须在 1 ~ " + SINGLE_USE_BATCH_MAX + " 之间");
        }
        ShopCoupon coupon = codeCoupon(couponToken);
        List<String> result = generateCodes(coupon, count);
        audit(actorAdminId, AuditActions.SHOP_COUPON_CODES_CREATED, coupon.getPublicToken(),
                "singleUse=true count=" + count);
        return result;
    }

    /** 生成并保存一批一次性码（1 ≤ count ≤ 1000，不写审计）。 */
    private List<String> generateCodes(ShopCoupon coupon, int count) {
        Set<String> result = new LinkedHashSet<>();
        for (int round = 0; result.size() < count; round++) {
            if (round >= GENERATE_MAX_ROUNDS) {
                throw new IllegalStateException("一次性码生成撞码过多");
            }
            Set<String> batch = new LinkedHashSet<>();
            while (batch.size() < count - result.size()) {
                String c = randomCode();
                if (!result.contains(c)) {
                    batch.add(c);
                }
            }
            Set<String> taken = codes.findExistingCodes(batch);
            if (taken != null) {
                batch.removeAll(taken);
            }
            result.addAll(batch);
        }
        try {
            codes.saveAllAndFlush(result.stream().map(c -> ShopCouponCode.of(coupon.getId(), c, true)).toList());
        } catch (DataIntegrityViolationException e) {
            // 与并发生成 / 建码撞唯一约束（极小概率）：整批回滚，运营重试即可
            throw AppException.conflict("兑换码与已有码重复，请重试");
        }
        return List.copyOf(result);
    }

    /** 码规范化 = {@code trim().toUpperCase(Locale.ROOT)}，格式 {@code ^[A-Z0-9]{4,32}$}（D-4）。2.2 兑换时同口径。 */
    public static String normalizeCode(String raw) {
        if (raw == null) {
            throw AppException.validation("兑换码不能为空");
        }
        String n = raw.trim().toUpperCase(Locale.ROOT);
        if (!CODE_FORMAT.matcher(n).matches()) {
            throw AppException.validation("兑换码只能是 4 ~ 32 位字母或数字");
        }
        return n;
    }

    private String randomCode() {
        char[] buf = new char[SINGLE_USE_CODE_LENGTH];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = SINGLE_USE_ALPHABET[random.nextInt(SINGLE_USE_ALPHABET.length)];
        }
        return new String(buf);
    }

    private ShopCoupon codeCoupon(String couponToken) {
        ShopCoupon coupon = coupons.findByPublicToken(couponToken)
                .orElseThrow(() -> AppException.notFound("券不存在"));
        if (coupon.getAcquireType() != CouponAcquireType.CODE) {
            throw AppException.validation("只有「兑换码」类型的券可以生成兑换码");
        }
        return coupon;
    }

    // ------------------------------------------------------------------ 公共

    private void audit(Long actorAdminId, String action, String couponToken, String summary) {
        if (actorAdminId == null) {
            log.info("券后台动作（无操作人，stag 入口）action={} coupon={}", action, couponToken);
            return;
        }
        audit.record(actorAdminId, action, AUDIT_TARGET, couponToken, summary);
    }

    private static <T> T require(T v, String field) {
        if (v == null) {
            throw AppException.validation(field + "不能为空");
        }
        return v;
    }

    private static String name(String v, String field) {
        if (v == null || v.isBlank()) {
            throw AppException.validation(field + "不能为空");
        }
        String t = v.trim();
        if (t.length() > NAME_MAX) {
            throw AppException.validation(field + "最多 " + NAME_MAX + " 字");
        }
        return t;
    }
}
