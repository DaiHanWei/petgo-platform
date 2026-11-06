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
    private final CouponNewbieLock newbieLock;
    private final ShopTokenGenerator tokens;
    private final AdminAuditService audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public CouponAdminService(ShopCouponRepository coupons, ShopCouponCodeRepository codes,
            ShopProductRepository products, CouponLedgerService ledger, CouponNewbieLock newbieLock,
            ShopTokenGenerator tokens, AdminAuditService audit) {
        this(coupons, codes, products, ledger, newbieLock, tokens, audit, Clock.systemUTC());
    }

    /** 测试注入固定时钟。 */
    CouponAdminService(ShopCouponRepository coupons, ShopCouponCodeRepository codes,
            ShopProductRepository products, CouponLedgerService ledger, CouponNewbieLock newbieLock,
            ShopTokenGenerator tokens, AdminAuditService audit, Clock clock) {
        this.coupons = coupons;
        this.codes = codes;
        this.products = products;
        this.ledger = ledger;
        this.newbieLock = newbieLock;
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 建券

    /** 校验后保存（ACTIVE）。新人券在同一事务内先加 advisory 锁再查发放期重叠（AB-24 / FR-130.5）。 */
    @Transactional
    public ShopCoupon create(CouponDraft draft, Long actorAdminId) {
        if (draft == null) {
            throw AppException.validation("券信息不能为空");
        }
        CouponKind kind = require(draft.kind(), "券类型");
        CouponAcquireType acquireType = require(draft.acquireType(), "获取方式");
        CouponValidMode validMode = require(draft.validMode(), "有效期模式");
        String nameId = name(draft.nameId(), "印尼语券名");
        String nameEn = name(draft.nameEn(), "英文券名");

        long amount = require(draft.amount(), "面额");
        long minSpend = draft.minSpend() == null ? 0L : draft.minSpend();
        if (amount <= 0) {
            throw AppException.validation("面额必须大于 0");
        }
        if (minSpend < 0) {
            throw AppException.validation("使用门槛不能为负");
        }
        // 满减券：有门槛时面额必须小于门槛（min_spend = 0 无门槛允许）；免运券 amount 即每张封顶，不受此限。
        if (kind == CouponKind.DISCOUNT && minSpend > 0 && amount >= minSpend) {
            throw AppException.validation("满减券面额必须小于使用门槛");
        }

        CouponScopeType scopeType = draft.scopeType() == null ? CouponScopeType.ALL : draft.scopeType();
        List<String> scopeValues = validateScope(scopeType, draft.scopeValues());

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

        if (acquireType == CouponAcquireType.NEWBIE) {
            newbieLock.acquire();
            if (coupons.existsActiveNewbieOverlapping(issueStart, issueEnd)) {
                throw AppException.conflict("已有发放期重叠的新人券");
            }
        }

        ShopCoupon saved = coupons.save(ShopCoupon.create(tokens.generate(), kind, nameId, nameEn, amount,
                minSpend, scopeType, scopeValues, acquireType, issueStart, issueEnd, validMode,
                validMode == CouponValidMode.FIXED ? validStart : null,
                validMode == CouponValidMode.FIXED ? validEnd : null,
                validMode == CouponValidMode.RELATIVE ? validDays : null,
                totalQuantity, perUserLimit));
        audit(actorAdminId, AuditActions.SHOP_COUPON_CREATED, saved.getPublicToken(),
                "kind=" + kind + " acquire=" + acquireType + " amount=" + amount + " minSpend=" + minSpend
                        + " scope=" + scopeType + " total=" + (totalQuantity == null ? "UNLIMITED" : totalQuantity)
                        + " perUser=" + perUserLimit);
        return saved;
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
        audit(actorAdminId, AuditActions.SHOP_COUPON_CODES_CREATED, coupon.getPublicToken(),
                "singleUse=true count=" + count);
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
