package com.tailtopia.entitlement.web;

import com.tailtopia.entitlement.domain.CreditFamily;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.dto.EntitlementHistoryPage;
import com.tailtopia.entitlement.dto.EntitlementSummaryResponse;
import com.tailtopia.entitlement.repository.EntitlementHistoryQuery;
import com.tailtopia.entitlement.service.CreditSummary;
import com.tailtopia.entitlement.service.EntitlementCreditService;
import com.tailtopia.entitlement.service.FamilySummary;
import com.tailtopia.entitlement.service.TypeSummary;
import com.tailtopia.shared.error.AppException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「Koin &amp; Jatah Saya」数据（V1.5.0 gift-pack Story 4-4 · AC2 / AC3 · D-28）。只给 {@code role=USER}（SecurityConfig）；游客 401。
 */
@RestController
public class MeEntitlementController {

    static final Duration EXPIRING_SOON = Duration.ofDays(7);

    private final EntitlementCreditService credits;
    private final EntitlementHistoryQuery history;
    private final Clock clock = Clock.systemUTC();

    public MeEntitlementController(EntitlementCreditService credits, EntitlementHistoryQuery history) {
        this.credits = credits;
        this.history = history;
    }

    @GetMapping("/api/v1/me/entitlements")
    public EntitlementSummaryResponse summary(@AuthenticationPrincipal Jwt jwt) {
        return toResponse(credits.summary(currentUserId(jwt)), clock.instant());
    }

    @GetMapping("/api/v1/me/entitlements/history")
    @Transactional(readOnly = true)
    public EntitlementHistoryPage history(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor) {
        long userId = currentUserId(jwt);
        EntitlementHistoryQuery.Cursor after = null;
        if (cursor != null && !cursor.isBlank()) {
            after = EntitlementHistoryQuery.Cursor.decodeOrNull(cursor.trim());
            if (after == null) {
                throw AppException.validation("游标非法");
            }
        }
        List<EntitlementHistoryQuery.Row> rows = history.page(userId, after, EntitlementHistoryQuery.PAGE_SIZE + 1);
        boolean hasMore = rows.size() > EntitlementHistoryQuery.PAGE_SIZE;
        List<EntitlementHistoryQuery.Row> page = hasMore ? rows.subList(0, EntitlementHistoryQuery.PAGE_SIZE) : rows;
        String next = null;
        if (hasMore) {
            EntitlementHistoryQuery.Row last = page.get(page.size() - 1);
            next = new EntitlementHistoryQuery.Cursor(last.item().at(), last.kindRank(), last.id()).encode();
        }
        return new EntitlementHistoryPage(page.stream().map(EntitlementHistoryQuery.Row::item).toList(), next);
    }

    /** 汇总 → 响应（纯函数，可单测）。 */
    static EntitlementSummaryResponse toResponse(CreditSummary s, Instant now) {
        List<EntitlementSummaryResponse.Family> out = new ArrayList<>();
        for (CreditFamily f : CreditFamily.values()) {
            FamilySummary fs = s.families().stream().filter(x -> x.family() == f).findFirst().orElse(null);
            if (fs == null || fs.remaining() <= 0) {
                continue;
            }
            List<EntitlementSummaryResponse.Template> templates = fs.types().stream()
                    .filter(t -> t.remaining() > 0)
                    .sorted(Comparator.comparing(TypeSummary::type))
                    .map(t -> new EntitlementSummaryResponse.Template(templateOf(t.type()), t.remaining(),
                            t.earliestExpiry()))
                    .toList();
            boolean soon = fs.earliestExpiry() != null && !fs.earliestExpiry().isAfter(now.plus(EXPIRING_SOON));
            out.add(new EntitlementSummaryResponse.Family(f.name(), fs.remaining(), fs.earliestExpiry(), soon,
                    f == CreditFamily.KTP_HD ? templates : List.of()));
        }
        int total = out.stream().mapToInt(EntitlementSummaryResponse.Family::remaining).sum();
        return new EntitlementSummaryResponse(total, List.copyOf(out));
    }

    /** {@code KTP_HD:STUDENT_BINTANG} → {@code STUDENT_BINTANG}。 */
    static String templateOf(CreditType type) {
        String wire = type.wire();
        int i = wire.indexOf(':');
        return i >= 0 ? wire.substring(i + 1) : wire;
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
