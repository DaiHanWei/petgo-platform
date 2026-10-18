package com.tailtopia.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.domain.GrowthAccount;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.event.GrowthLevelChangedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** V1.4.0 ① Story 1.2 · AC1–AC5（L0）：升级 / 连跳 / 降级 / 不变 / 满级，事件只发一次。 */
class GrowthLevelChangeHandlerTest {

    /** PRD 等级曲线（threshold → level）。 */
    private static final TreeMap<Long, Integer> CURVE = new TreeMap<>(Map.ofEntries(
            Map.entry(0L, 1), Map.entry(70L, 2), Map.entry(130L, 3), Map.entry(250L, 4), Map.entry(500L, 5),
            Map.entry(800L, 6), Map.entry(1250L, 7), Map.entry(1800L, 8), Map.entry(2500L, 9),
            Map.entry(3300L, 10), Map.entry(4500L, 11), Map.entry(5800L, 12), Map.entry(7400L, 13),
            Map.entry(9500L, 14), Map.entry(12400L, 15), Map.entry(16500L, 16), Map.entry(21500L, 17),
            Map.entry(27200L, 18), Map.entry(33800L, 19), Map.entry(41300L, 20)));

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final GrowthLevelChangeHandler handler = new GrowthLevelChangeHandler(jdbc, events,
            Clock.fixed(Instant.parse("2026-10-18T05:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void curve() {
        when(jdbc.queryForObject(eq(GrowthLevelChangeHandler.LEVEL_FOR_TOTAL_SQL), anyMap(), eq(Integer.class)))
                .thenAnswer(inv -> {
                    Map<String, Object> p = inv.getArgument(1);
                    return CURVE.floorEntry((Long) p.get("total")).getValue();
                });
    }

    @Test
    void reachingThresholdLevelsUpOnce() {
        GrowthAccount a = GrowthAccount.of(7L, 500, 4); // 495 + 5

        handler.onLifetimeChanged(a, RuleCode.POST_PUBLISHED);

        assertThat(a.getLevel()).isEqualTo(5);
        verify(events, times(1)).publishEvent(new GrowthLevelChangedEvent(7L, 4, 5));
    }

    @Test
    void jumpingSeveralLevelsPublishesOneEvent() {
        GrowthAccount a = GrowthAccount.of(7L, 1300, 4);

        handler.onLifetimeChanged(a, RuleCode.GIFT_SENT);

        assertThat(a.getLevel()).isEqualTo(7);
        verify(events, times(1)).publishEvent(any(Object.class));
        verify(events).publishEvent(new GrowthLevelChangedEvent(7L, 4, 7));
    }

    @Test
    void droppingBelowThresholdLevelsDown() {
        GrowthAccount a = GrowthAccount.of(7L, 3299, 10);

        handler.onLifetimeChanged(a, RuleCode.SPEND_DIGITAL);

        assertThat(a.getLevel()).isEqualTo(9);
        verify(events).publishEvent(new GrowthLevelChangedEvent(7L, 10, 9));
    }

    @Test
    void unchangedLevelPublishesNothing() {
        GrowthAccount a = GrowthAccount.of(7L, 140, 3);

        handler.onLifetimeChanged(a, RuleCode.LIKE_GIVEN);

        assertThat(a.getLevel()).isEqualTo(3);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void maxLevelKeepsAccumulatingWithoutEvents() {
        GrowthAccount a = GrowthAccount.of(7L, 41_300 + 9_999, 20);

        handler.onLifetimeChanged(a, RuleCode.GIFT_SENT);

        assertThat(a.getLevel()).isEqualTo(20);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void emptyResultFallsBackToLevelOne() {
        when(jdbc.queryForObject(eq(GrowthLevelChangeHandler.LEVEL_FOR_TOTAL_SQL), anyMap(), eq(Integer.class)))
                .thenReturn(null);

        assertThat(handler.levelFor(0)).isEqualTo(1);
    }
}
