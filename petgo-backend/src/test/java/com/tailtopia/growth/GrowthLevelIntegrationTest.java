package com.tailtopia.growth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.domain.ReverseReason;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.event.GrowthLevelChangedEvent;
import com.tailtopia.growth.service.AccrualCommand;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.growth.service.ReverseCommand;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** V1.4.0 ① Story 1.2 · AC2–AC5（L1，需 Docker postgres）：真库上的升级 / 连跳 / 降级 / 满级。 */
@RecordApplicationEvents
class GrowthLevelIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private GrowthLedgerService ledger;

    @Autowired
    private GrowthQueryService query;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEvents events;

    private static final Instant AT = Instant.parse("2026-10-18T05:00:00Z");

    private void gift(long userId, int points, String key) {
        ledger.accrue(new AccrualCommand(userId, RuleCode.GIFT_SENT, points, key, "GIFT_ORDER", key, null, AT));
    }

    private long changes(long userId) {
        return events.stream(GrowthLevelChangedEvent.class).filter(e -> e.userId() == userId).count();
    }

    @Test
    void levelUpAndJumpAndDownAndMax() {
        long u = newUser().getId();
        gift(u, 495, "g1-" + u);
        assertThat(query.getLevel(u)).isEqualTo(GrowthLevel.of(4, GrowthTier.NEWBIE));
        gift(u, 5, "g2-" + u);
        assertThat(query.getLevel(u)).isEqualTo(GrowthLevel.of(5, GrowthTier.BUDDY));
        assertThat(events.stream(GrowthLevelChangedEvent.class))
                .contains(new GrowthLevelChangedEvent(u, 4, 5));

        long v = newUser().getId();
        gift(v, 250, "h1-" + v);
        long before = changes(v);
        gift(v, 3_000, "h2-" + v); // 250 → 3250：Lv4 → Lv9
        assertThat(changes(v) - before).isEqualTo(1);
        assertThat(events.stream(GrowthLevelChangedEvent.class)).contains(new GrowthLevelChangedEvent(v, 4, 9));

        gift(v, 100, "h3-" + v); // 3350：Lv10
        ledger.reverse(new ReverseCommand("h3-" + v, ReverseReason.REFUND, "REFUND:t" + v, null));
        assertThat(query.getLevel(v).level()).isEqualTo(9);
        assertThat(events.stream(GrowthLevelChangedEvent.class)).contains(new GrowthLevelChangedEvent(v, 10, 9));

        long w = newUser().getId();
        gift(w, 41_300, "m1-" + w);
        long atMax = changes(w);
        gift(w, 5_000, "m2-" + w);
        assertThat(changes(w)).isEqualTo(atMax);
        assertThat(jdbc.queryForObject("SELECT lifetime_total FROM growth_accounts WHERE user_id = ?", Long.class, w))
                .isEqualTo(46_300L);
        assertThat(query.getLevel(w).level()).isEqualTo(20);
    }
}
