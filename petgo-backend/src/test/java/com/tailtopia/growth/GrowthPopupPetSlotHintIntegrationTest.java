package com.tailtopia.growth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.AccrualCommand;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.profile.slot.domain.PetSlotPurchase;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseKind;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseStatus;
import com.tailtopia.profile.slot.domain.PetSlotRental;
import com.tailtopia.profile.slot.domain.PetSlotRentalSource;
import com.tailtopia.profile.slot.repository.PetSlotPurchaseRepository;
import com.tailtopia.profile.slot.repository.PetSlotRentalRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * V1.4.0 multi-pet Story 4.4 · AC1（L1，需 Docker postgres + redis）：Lv4 → Lv5 时 {@code /popups} 的
 * {@code celebration.petSlotHint} 按「此刻有生效中月租」读时计算（RENT 与运营补发同算，已过期的不算）。
 */
class GrowthPopupPetSlotHintIntegrationTest extends ApiIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final Instant AT = Instant.parse("2026-10-19T05:00:00Z");

    @Autowired
    private GrowthLedgerService ledger;
    @Autowired
    private PetSlotRentalRepository rentals;
    @Autowired
    private PetSlotPurchaseRepository purchases;

    private static String token(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    /** 升到 Lv5（495 → Lv4，再 +5 → 500 = Lv5；照 {@code GrowthLevelUpNotifyIntegrationTest}）。同步入账、不等异步。 */
    private void reachLv5(long u) {
        ledger.accrue(new AccrualCommand(u, RuleCode.GIFT_SENT, 495, "hint-a-" + u, "GIFT_ORDER", "hint-a-" + u,
                null, AT));
        ledger.accrue(new AccrualCommand(u, RuleCode.GIFT_SENT, 5, "hint-b-" + u, "GIFT_ORDER", "hint-b-" + u,
                null, AT));
    }

    /** 给用户造一个月租名额：{@code startsAt + days} 到期。 */
    private void rental(long u, PetSlotRentalSource source, Instant startsAt, int days) {
        PetSlotPurchase p = PetSlotPurchase.pending(token("ihp"), u, PetSlotPurchaseKind.RENT, 1, 5000, 5000,
                PayChannel.PAWCOIN, null, "it-hint-" + SEQ.incrementAndGet(), null, startsAt);
        p.settle(PetSlotPurchaseStatus.PAID, startsAt);
        p = purchases.saveAndFlush(p);
        rentals.saveAndFlush(PetSlotRental.start(token("ihr"), u, source, startsAt, days, p.getId()));
    }

    private void expectHint(long u, boolean hint) throws Exception {
        mvc.perform(get("/api/v1/me/growth/popups").header(HttpHeaders.AUTHORIZATION, userBearer(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.celebration.toLevel").value(5))
                .andExpect(jsonPath("$.celebration.unlockedBenefits[0]").value("PET_SLOT_PLUS_ONE"))
                .andExpect(jsonPath("$.celebration.petSlotHint").value(hint));
    }

    @Test
    void lv4ToLv5WithLiveRentalHintsPermanentSlot() throws Exception {
        long u = newUser().getId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        rental(u, PetSlotRentalSource.RENT, now, 30);
        reachLv5(u);
        expectHint(u, true);
    }

    @Test
    void adminGrantedSlotCountsAsLiveRental() throws Exception {
        long u = newUser().getId();
        rental(u, PetSlotRentalSource.ADMIN_GRANT, Instant.now().truncatedTo(ChronoUnit.SECONDS), 7);
        reachLv5(u);
        expectHint(u, true);
    }

    @Test
    void noRentalOrExpiredRentalHasNoHint() throws Exception {
        long none = newUser().getId();
        reachLv5(none);
        expectHint(none, false);

        long expired = newUser().getId();
        rental(expired, PetSlotRentalSource.RENT, Instant.now().minus(31, ChronoUnit.DAYS), 30);
        reachLv5(expired);
        expectHint(expired, false);
    }
}
