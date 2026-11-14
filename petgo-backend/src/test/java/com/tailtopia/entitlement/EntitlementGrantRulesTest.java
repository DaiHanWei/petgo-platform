package com.tailtopia.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.entitlement.domain.CreditFamily;
import com.tailtopia.entitlement.domain.CreditSource;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.service.CreditSummary;
import com.tailtopia.entitlement.service.EntitlementGrantRules;
import com.tailtopia.entitlement.service.EntitlementGrantRules.Candidate;
import com.tailtopia.entitlement.service.EntitlementGrantRules.Take;
import com.tailtopia.entitlement.service.GrantCommand;
import com.tailtopia.entitlement.service.TypeSummary;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：Story 4-1 AC3.5（有效期、校验分支）+ AC4 扣减规划 + AC5 汇总聚合。 */
class EntitlementGrantRulesTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private static Instant wib(int y, int m, int d, int h) {
        return LocalDateTime.of(y, m, d, h, 0).atZone(WIB).toInstant();
    }

    @Test
    void expiresThreeMonthsLaterSameInstant() {
        assertThat(EntitlementGrantRules.expiresAt(wib(2026, 11, 15, 9))).isEqualTo(wib(2027, 2, 15, 9));
    }

    @Test
    void monthEndClampsJan31ToApr30() {
        assertThat(EntitlementGrantRules.expiresAt(wib(2027, 1, 31, 12))).isEqualTo(wib(2027, 4, 30, 12));
    }

    @Test
    void nov30ToFeb28OrLeap29() {
        assertThat(EntitlementGrantRules.expiresAt(wib(2026, 11, 30, 8))).isEqualTo(wib(2027, 2, 28, 8));
        assertThat(EntitlementGrantRules.expiresAt(wib(2027, 11, 30, 8))).isEqualTo(wib(2028, 2, 29, 8));
    }

    @Test
    void wibCalendarNotUtc() {
        // UTC 1 月 31 日 20:00 = WIB 2 月 1 日 03:00 → WIB 5 月 1 日 03:00
        Instant granted = Instant.parse("2027-01-31T20:00:00Z");
        assertThat(EntitlementGrantRules.expiresAt(granted)).isEqualTo(Instant.parse("2027-04-30T20:00:00Z"));
    }

    private static GrantCommand cmd(CreditType type, int qty, CreditSource src, String ref, String key,
            Long op, String reason) {
        return new GrantCommand(1L, type, qty, src, ref, key, null, null, op, reason);
    }

    private static URI typeOf(Runnable r) {
        try {
            r.run();
        } catch (AppException e) {
            return e.getType();
        }
        return null;
    }

    @Test
    void validationBranches() {
        assertThat(typeOf(() -> EntitlementGrantRules.validate(cmd(CreditType.GIFT_1K, 0, CreditSource.PACK, "1", "k", null, null))))
                .isEqualTo(ErrorTypes.ENTITLEMENT_QUANTITY_INVALID);
        assertThat(typeOf(() -> EntitlementGrantRules.validate(cmd(CreditType.GIFT_1K, 101, CreditSource.PACK, "1", "k", null, null))))
                .isEqualTo(ErrorTypes.ENTITLEMENT_QUANTITY_INVALID);
        assertThat(typeOf(() -> EntitlementGrantRules.validate(cmd(CreditType.GIFT_1K, 1, CreditSource.PACK, "1", " ", null, null))))
                .isEqualTo(ErrorTypes.VALIDATION);
        assertThat(typeOf(() -> EntitlementGrantRules.validate(cmd(CreditType.GIFT_1K, 1, CreditSource.PACK, "1", "k".repeat(161), null, null))))
                .isEqualTo(ErrorTypes.VALIDATION);
        assertThat(typeOf(() -> EntitlementGrantRules.validate(cmd(CreditType.GIFT_1K, 1, CreditSource.PACK, "", "k", null, null))))
                .isEqualTo(ErrorTypes.VALIDATION);
        assertThat(typeOf(() -> EntitlementGrantRules.validate(cmd(CreditType.GIFT_1K, 1, CreditSource.PACK, "r".repeat(65), "k", null, null))))
                .isEqualTo(ErrorTypes.VALIDATION);
        assertThat(typeOf(() -> EntitlementGrantRules.validate(new GrantCommand(1L, CreditType.GIFT_1K, 1, CreditSource.PACK, "1", "k", "x".repeat(81), null, null, null))))
                .isEqualTo(ErrorTypes.VALIDATION);
        assertThat(typeOf(() -> EntitlementGrantRules.validate(cmd(CreditType.KTP_HD_ANY, 1, CreditSource.PACK, "1", "k", null, null))))
                .isEqualTo(ErrorTypes.ENTITLEMENT_TYPE_NOT_ALLOWED);
        assertThat(typeOf(() -> EntitlementGrantRules.validate(cmd(CreditType.KTP_HD_ANY, 1, CreditSource.ADMIN, "1", "k", 9L, "r"))))
                .isEqualTo(ErrorTypes.ENTITLEMENT_TYPE_NOT_ALLOWED);
        assertThat(typeOf(() -> EntitlementGrantRules.validate(cmd(CreditType.GIFT_1K, 1, CreditSource.ADMIN, "t", "k", null, "r"))))
                .isEqualTo(ErrorTypes.VALIDATION);
        assertThat(typeOf(() -> EntitlementGrantRules.validate(cmd(CreditType.GIFT_1K, 1, CreditSource.ADMIN, "t", "k", 9L, "  "))))
                .isEqualTo(ErrorTypes.VALIDATION);
        assertThat(typeOf(() -> EntitlementGrantRules.validate(cmd(CreditType.GIFT_1K, 1, CreditSource.PACK, "1", "k", null, "r".repeat(201)))))
                .isEqualTo(ErrorTypes.VALIDATION);
        // 合法
        assertThat(typeOf(() -> EntitlementGrantRules.validate(cmd(CreditType.GIFT_1K, 100, CreditSource.ADMIN, "t", "k", 9L, "r")))).isNull();
        assertThat(typeOf(() -> EntitlementGrantRules.validate(GrantCommand.activity(1L, CreditType.KTP_HD_ANY, 2, 77L, "ACT:77:POST:1", "Lomba", "Contest")))).isNull();
    }

    @Test
    void quantityCheckedBeforeAnyRule() {
        // 数量非法 + ANY + PACK：先报数量
        assertThat(typeOf(() -> EntitlementGrantRules.validate(cmd(CreditType.KTP_HD_ANY, 0, CreditSource.PACK, "1", "k", null, null))))
                .isEqualTo(ErrorTypes.ENTITLEMENT_QUANTITY_INVALID);
    }

    @Test
    void activityFactoryShape() {
        GrantCommand c = GrantCommand.activity(5L, CreditType.TAILSONALITY, 1, 42L, "ACT:42:POST:9", "Nama", "Name");
        assertThat(c).isEqualTo(new GrantCommand(5L, CreditType.TAILSONALITY, 1, CreditSource.ACTIVITY, "42",
                "ACT:42:POST:9", "Nama", "Name", null, null));
    }

    @Test
    void planTakesInOrderAcrossBatches() {
        List<Take> t = EntitlementGrantRules.plan(List.of(new Candidate(1, 2), new Candidate(2, 5)), 5);
        assertThat(t).containsExactly(new Take(1, 2), new Take(2, 3));
        assertThat(EntitlementGrantRules.plan(List.of(new Candidate(1, 2)), 3)).isEmpty();
        assertThat(EntitlementGrantRules.plan(List.of(), 1)).isEmpty();
        assertThat(EntitlementGrantRules.plan(List.of(new Candidate(1, 3), new Candidate(2, 3)), 1))
                .containsExactly(new Take(1, 1));
    }

    @Test
    void petNameTruncatedByCodePoint() {
        assertThat(EntitlementGrantRules.petNameSnapshot("😀".repeat(25))).isEqualTo("😀".repeat(20));
        assertThat(EntitlementGrantRules.petNameSnapshot("  ")).isNull();
        assertThat(EntitlementGrantRules.petNameSnapshot(" Mochi ")).isEqualTo("Mochi");
    }

    @Test
    void summarizeGroupsByFamily() {
        Instant a = Instant.parse("2027-01-01T00:00:00Z");
        Instant b = Instant.parse("2027-02-01T00:00:00Z");
        CreditSummary s = EntitlementGrantRules.summarize(List.of(
                new TypeSummary(CreditType.KTP_HD_ANY, 1, a),
                new TypeSummary(CreditType.KTP_HD_KTP, 2, b),
                new TypeSummary(CreditType.GIFT_1K, 5, b),
                new TypeSummary(CreditType.TAILSONALITY, 0, a)));
        assertThat(s.totalRemaining()).isEqualTo(8);
        assertThat(s.families()).hasSize(2);
        assertThat(s.families().get(0).family()).isEqualTo(CreditFamily.KTP_HD);
        assertThat(s.families().get(0).remaining()).isEqualTo(3);
        assertThat(s.families().get(0).earliestExpiry()).isEqualTo(a);
        assertThat(s.families().get(0).types()).extracting(TypeSummary::type)
                .containsExactly(CreditType.KTP_HD_KTP, CreditType.KTP_HD_ANY);
        assertThat(s.families().get(1).family()).isEqualTo(CreditFamily.GIFT_1K);
        assertThat(s.families().get(1).types()).isEmpty();
        assertThat(EntitlementGrantRules.summarize(List.of()).totalRemaining()).isZero();
    }
}
