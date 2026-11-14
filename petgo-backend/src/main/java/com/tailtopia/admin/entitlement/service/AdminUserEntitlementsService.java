package com.tailtopia.admin.entitlement.service;

import com.tailtopia.admin.shared.time.AdminTime;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.dto.EntitlementHistoryPage;
import com.tailtopia.entitlement.repository.EntitlementHistoryQuery;
import com.tailtopia.entitlement.service.CreditSummary;
import com.tailtopia.entitlement.service.EntitlementCreditService;
import com.tailtopia.entitlement.service.FamilySummary;
import com.tailtopia.entitlement.service.TypeSummary;
import com.tailtopia.starterpack.domain.StarterPackPurchase;
import com.tailtopia.starterpack.domain.StarterPackPurchaseStatus;
import com.tailtopia.starterpack.repository.StarterPackPurchaseRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户抽屉「已购解锁」页签的礼包 / 免费次数三块（V1.5.0 gift-pack Story 5-2 · AC3 · 后台稿 J 泳道）：只读，数据经 4-1 / 4-4 的
 * 服务与查询（{@code EntitlementCreditService.summary}、{@code EntitlementHistoryQuery}），不在后台直写这两张表。
 */
@Service
public class AdminUserEntitlementsService {

    static final int HISTORY_LIMIT = 20;
    static final Duration EXPIRING_SOON = Duration.ofDays(7);

    /** 补发下拉一项：提交值（枚举名）+ 文案键（模板只读这两个，不在模板里调静态方法）。 */
    public record GrantOption(String value, String typeKey, String template) {
    }

    /** 补发下拉可选类型：8 款 KTP + Tailsonality + Rp1.000 礼物（不含「任意款」，D-31）。 */
    public static final List<GrantOption> GRANTABLE = List.of(CreditType.values()).stream()
            .filter(t -> t != CreditType.KTP_HD_ANY)
            .map(t -> new GrantOption(t.name(), typeKey(t), template(t)))
            .toList();

    private final StarterPackPurchaseRepository packs;
    private final EntitlementCreditService credits;
    private final EntitlementHistoryQuery history;
    private final AdminTime time;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();

    public AdminUserEntitlementsService(StarterPackPurchaseRepository packs, EntitlementCreditService credits,
            EntitlementHistoryQuery history, AdminTime time,
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        this.packs = packs;
        this.credits = credits;
        this.history = history;
        this.time = time;
        this.jdbc = jdbc;
    }

    /** 页签视图。{@code isEmpty()} = 三块都没有内容（与原四张卡一起决定空态）。 */
    public record View(List<PackRow> packs, List<CreditRow> credits, List<HistoryRow> history) {

        public boolean isEmpty() {
            return packs.isEmpty() && credits.isEmpty() && history.isEmpty();
        }
    }

    /** 礼包购买一行：档位、时间（WIB）、支付方式、金额。 */
    public record PackRow(int tier, String paidAtWib, String channel, long price) {
    }

    /**
     * 剩余次数一行：{@code typeKey} 取文案（KTP 模板 / ANY / TAILSONALITY / GIFT_1K），{@code template} 为 KTP 模板名
     * （卡种 i18n 后缀，如 {@code STUDENT.BINTANG}），{@code soon} = 7 天内到期（标红）。
     */
    public record CreditRow(String typeKey, String template, int remaining, String earliestExpiryWib, boolean soon) {
    }

    /**
     * 记录一行：{@code change} 带符号（+3 / −1）；{@code sourceKey} / {@code sourceArg} 或 {@code useKey} / {@code useArg} 取文案。
     */
    public record HistoryRow(String atWib, String typeKey, String template, String change, String sourceKey,
            String sourceArg, String useKey, String useArg) {
    }

    @Transactional(readOnly = true)
    public View tab(long userId) {
        List<PackRow> packRows = packs.findByUserIdAndStatusOrderByPaidAtDesc(userId, StarterPackPurchaseStatus.PAID)
                .stream()
                .map(p -> new PackRow(p.getTier(), time.fmt(p.getPaidAt()), p.getPayChannel().name(), p.getPrice()))
                .toList();
        List<EntitlementHistoryQuery.Row> rows = history.page(userId, null, HISTORY_LIMIT);
        return new View(packRows, creditRows(credits.summary(userId), clock.instant()),
                historyRows(rows, adminReasons(rows)));
    }

    List<CreditRow> creditRows(CreditSummary s, Instant now) {
        List<CreditRow> out = new ArrayList<>();
        for (FamilySummary f : s.families()) {
            List<TypeSummary> types = f.types().isEmpty()
                    ? List.of(new TypeSummary(singleTypeOf(f), f.remaining(), f.earliestExpiry()))
                    : f.types().stream().sorted(Comparator.comparing(TypeSummary::type)).toList();
            for (TypeSummary t : types) {
                if (t.remaining() <= 0) {
                    continue;
                }
                boolean soon = t.earliestExpiry() != null && !t.earliestExpiry().isAfter(now.plus(EXPIRING_SOON));
                out.add(new CreditRow(typeKey(t.type()), template(t.type()), t.remaining(),
                        t.earliestExpiry() == null ? "—" : time.fmt(t.earliestExpiry()), soon));
            }
        }
        return List.copyOf(out);
    }

    private static CreditType singleTypeOf(FamilySummary f) {
        return switch (f.family()) {
            case KTP_HD -> CreditType.KTP_HD_ANY;
            case TAILSONALITY -> CreditType.TAILSONALITY;
            case GIFT_1K -> CreditType.GIFT_1K;
        };
    }

    /**
     * 补发行的原因（只后台显示；App 的记录接口不带原因）：GRANT 行的 id 即发放批 id，一次批量只读查询（空集不查）。
     */
    private java.util.Map<Long, String> adminReasons(List<EntitlementHistoryQuery.Row> rows) {
        List<Long> ids = rows.stream()
                .filter(r -> "GRANT".equals(r.item().kind()) && "ADMIN".equals(r.item().source()))
                .map(EntitlementHistoryQuery.Row::id).toList();
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        if (!ids.isEmpty()) {
            jdbc.query("SELECT id, reason FROM entitlement_credits WHERE id IN (:ids)",
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("ids", ids),
                    rs -> {
                        out.put(rs.getLong("id"), rs.getString("reason"));
                    });
        }
        return out;
    }

    List<HistoryRow> historyRows(List<EntitlementHistoryQuery.Row> rows, java.util.Map<Long, String> reasons) {
        List<HistoryRow> out = new ArrayList<>();
        for (EntitlementHistoryQuery.Row row : rows) {
            EntitlementHistoryPage.Item i = row.item();
            CreditType type = CreditType.fromWire(i.creditType()).orElse(null);
            boolean grant = "GRANT".equals(i.kind());
            String sourceKey = null;
            String sourceArg = null;
            String useKey = null;
            String useArg = null;
            if (grant) {
                sourceKey = i.source() == null ? null : "admin.v150.valuePack.history.source." + i.source();
                sourceArg = switch (i.source() == null ? "" : i.source()) {
                    case "PACK" -> i.packTier() == null ? "?" : String.valueOf(i.packTier());
                    case "ACTIVITY" -> activityLabel(i);
                    case "ADMIN" -> reasons.getOrDefault(row.id(), "—");
                    default -> "";
                };
            } else {
                useKey = i.useKind() == null ? null : "admin.v150.valuePack.history.use." + i.useKind();
                useArg = i.petName() == null ? "—" : i.petName();
            }
            out.add(new HistoryRow(time.fmt(i.at()), type == null ? "UNKNOWN" : typeKey(type),
                    type == null ? null : template(type), (grant ? "+" : "−") + i.quantity(), sourceKey, sourceArg,
                    useKey, useArg));
        }
        return List.copyOf(out);
    }

    /** 活动名快照按后台当前语言取：印尼语 → labelId，其余 → labelEn；缺一方回落另一方（5-2 复审 #7）。 */
    private static String activityLabel(EntitlementHistoryPage.Item i) {
        boolean id = "id".equals(org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage());
        String first = id ? i.labelId() : i.labelEn();
        String second = id ? i.labelEn() : i.labelId();
        return first != null ? first : (second != null ? second : "—");
    }

    /** 类型文案键后缀：KTP 模板 → {@code KTP_HD}（配 {@code template}）、任意款 → {@code KTP_HD_ANY}，其余同名。 */
    public static String typeKey(CreditType t) {
        if (t == CreditType.KTP_HD_ANY) {
            return "KTP_HD_ANY";
        }
        return switch (t.family()) {
            case KTP_HD -> "KTP_HD";
            case TAILSONALITY -> "TAILSONALITY";
            case GIFT_1K -> "GIFT_1K";
        };
    }

    /** KTP 模板 → 卡种文案后缀（复用 {@code admin.v134.idcard.style.*}：{@code KTP} / {@code STUDENT.BINTANG}…）；其余 null。 */
    public static String template(CreditType t) {
        if (t.family() != com.tailtopia.entitlement.domain.CreditFamily.KTP_HD || t == CreditType.KTP_HD_ANY) {
            return null;
        }
        String w = t.wire().substring("KTP_HD:".length());
        return w.startsWith("STUDENT_") ? "STUDENT." + w.substring("STUDENT_".length()) : w;
    }
}
