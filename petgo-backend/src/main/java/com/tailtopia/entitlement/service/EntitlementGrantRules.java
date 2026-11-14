package com.tailtopia.entitlement.service;

import com.tailtopia.entitlement.domain.CreditFamily;
import com.tailtopia.entitlement.domain.CreditSource;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 发放 / 扣减的纯函数部分（Story 4-1 AC3 / AC4，L0 可测，不依赖 Spring / DB）。
 */
public final class EntitlementGrantRules {

    public static final int MIN_QUANTITY = 1;
    public static final int MAX_QUANTITY = 100;
    public static final int MAX_IDEM_KEY = 160;
    public static final int MAX_SOURCE_REF = 64;
    public static final int MAX_LABEL = 80;
    public static final int MAX_REASON = 200;
    public static final int MAX_PET_NAME = 20;
    /** 有效期：到账起 3 个月（按 Asia/Jakarta 日历，V1.5.0 不可配置）。 */
    public static final int VALID_MONTHS = 3;

    private EntitlementGrantRules() {
    }

    /** {@code grantedAt} 按 WIB 日历 plusMonths(3) 的同一时刻（月末自动收到当月最后一天）。 */
    public static Instant expiresAt(Instant grantedAt) {
        return grantedAt.atZone(ScheduleWindow.WIB).plusMonths(VALID_MONTHS).toInstant();
    }

    /**
     * 写库前的入参校验（不含用户状态，那一步要查库）。顺序照 AC3.1：数量 → 键 / 来源引用 / 标签 → 任意款来源 → ADMIN 必填。
     */
    public static void validate(GrantCommand cmd) {
        if (cmd == null || cmd.type() == null || cmd.source() == null) {
            throw AppException.validation("缺少发放类型或来源");
        }
        if (cmd.quantity() < MIN_QUANTITY || cmd.quantity() > MAX_QUANTITY) {
            throw AppException.entitlementQuantityInvalid("发放数量须为 1–100");
        }
        if (isBlank(cmd.idemKey()) || cmd.idemKey().length() > MAX_IDEM_KEY) {
            throw AppException.validation("幂等键为空或超长");
        }
        if (isBlank(cmd.sourceRef()) || cmd.sourceRef().length() > MAX_SOURCE_REF) {
            throw AppException.validation("来源引用为空或超长");
        }
        if (tooLong(cmd.labelId(), MAX_LABEL) || tooLong(cmd.labelEn(), MAX_LABEL)) {
            throw AppException.validation("来源名超长");
        }
        if (tooLong(cmd.reason(), MAX_REASON)) {
            throw AppException.validation("原因超长（≤ 200）");
        }
        if (cmd.type() == CreditType.KTP_HD_ANY && cmd.source() != CreditSource.ACTIVITY) {
            throw AppException.entitlementTypeNotAllowed("任意款 KTP 次数只能由运营活动发放");
        }
        if (cmd.source() == CreditSource.ADMIN
                && (cmd.operatorAdminId() == null || isBlank(cmd.reason()))) {
            throw AppException.validation("后台补发须带操作人与原因（≤ 200）");
        }
    }

    /** 候选批（已按扣减顺序排好）。 */
    public record Candidate(long creditId, int remaining) { }

    /** 一批扣多少。 */
    public record Take(long creditId, int quantity) { }

    /**
     * 按候选顺序逐批扣，直到凑够 {@code quantity}；可用合计不足 → 返回空列表（调用方抛 409）。
     */
    public static List<Take> plan(List<Candidate> candidates, int quantity) {
        List<Take> takes = new ArrayList<>();
        int left = quantity;
        for (Candidate c : candidates) {
            if (left <= 0) {
                break;
            }
            int n = Math.min(left, c.remaining());
            if (n > 0) {
                takes.add(new Take(c.creditId(), n));
                left -= n;
            }
        }
        return left > 0 ? List.of() : takes;
    }

    /** 截到 20 个码点（不切坏代理对）；空白 → null。 */
    public static String petNameSnapshot(String petName) {
        if (isBlank(petName)) {
            return null;
        }
        String t = petName.trim();
        int cps = t.codePointCount(0, t.length());
        return cps <= MAX_PET_NAME ? t : t.substring(0, t.offsetByCodePoints(0, MAX_PET_NAME));
    }

    /**
     * 按类型行聚合成汇总（AC5）：剩余 0 的类型丢弃；大类按枚举序；KTP 大类下按款（含 ANY，枚举序）列出。
     */
    public static CreditSummary summarize(List<TypeSummary> byType) {
        List<FamilySummary> families = new ArrayList<>();
        int total = 0;
        for (CreditFamily f : CreditFamily.values()) {
            List<TypeSummary> types = byType.stream()
                    .filter(t -> t.remaining() > 0 && t.type().family() == f)
                    .sorted(Comparator.comparing(TypeSummary::type))
                    .toList();
            if (types.isEmpty()) {
                continue;
            }
            int remaining = types.stream().mapToInt(TypeSummary::remaining).sum();
            Instant earliest = types.stream().map(TypeSummary::earliestExpiry).min(Comparator.naturalOrder())
                    .orElseThrow();
            families.add(new FamilySummary(f, remaining, earliest, f == CreditFamily.KTP_HD ? types : List.of()));
            total += remaining;
        }
        return new CreditSummary(total, List.copyOf(families));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean tooLong(String s, int max) {
        return s != null && s.length() > max;
    }
}
