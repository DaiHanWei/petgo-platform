package com.tailtopia.shared.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * L0：定时生效 / 失效的唯一判定（V1.1.6 Story 4.1 · AD-9）。
 *
 * <p>这组测试守的是**边界**。AC 的原话是：若各写一遍，左闭右开会在某处被写成全闭，
 * 出现「App 上已失效、后台还显示生效中」—— 那类不一致没有任何报错，只能靠人工发现。
 */
class ScheduleWindowTest {

    private static final Instant START = Instant.parse("2026-08-18T03:00:00Z");
    private static final Instant END = Instant.parse("2026-08-18T05:00:00Z");

    @Nested
    class 区间左闭右开 {

        /** 🛡 开始那一刻**算生效**。 */
        @Test
        void startInstantIsInside() {
            assertThat(ScheduleWindow.isActiveAt(START, START, END)).isTrue();
            assertThat(ScheduleWindow.phaseAt(START, START, END)).isEqualTo(SchedulePhase.ACTIVE);
        }

        /**
         * 🛡 结束那一刻**算已结束**。
         *
         * <p>运营配「10:00 到 12:00」时的心智是「12:00 就没了」，不是「12:00 那一秒还在」。
         * 写成全闭会让顶置多挂一瞬 —— 更要命的是后台与 App 只要有一侧写成全闭，两边就对不上。
         */
        @Test
        void endInstantIsOutside() {
            assertThat(ScheduleWindow.isActiveAt(END, START, END)).isFalse();
            assertThat(ScheduleWindow.phaseAt(END, START, END)).isEqualTo(SchedulePhase.ENDED);
        }

        @Test
        void oneMilliBeforeStartIsPending() {
            Instant t = START.minusMillis(1);
            assertThat(ScheduleWindow.isActiveAt(t, START, END)).isFalse();
            assertThat(ScheduleWindow.phaseAt(t, START, END)).isEqualTo(SchedulePhase.PENDING);
        }

        @Test
        void oneMilliBeforeEndIsStillActive() {
            Instant t = END.minusMillis(1);
            assertThat(ScheduleWindow.isActiveAt(t, START, END)).isTrue();
            assertThat(ScheduleWindow.phaseAt(t, START, END)).isEqualTo(SchedulePhase.ACTIVE);
        }
    }

    @Nested
    class 重叠判定 {

        private boolean overlapsWith(String s, String e) {
            return ScheduleWindow.overlaps(START, END, Instant.parse(s), Instant.parse(e));
        }

        @Test
        void containedStraddlingAndIdenticalAllOverlap() {
            assertThat(overlapsWith("2026-08-18T03:30:00Z", "2026-08-18T04:30:00Z")).isTrue(); // 被包含
            assertThat(overlapsWith("2026-08-18T02:00:00Z", "2026-08-18T06:00:00Z")).isTrue(); // 包含
            assertThat(overlapsWith("2026-08-18T02:00:00Z", "2026-08-18T04:00:00Z")).isTrue(); // 左跨
            assertThat(overlapsWith("2026-08-18T04:00:00Z", "2026-08-18T06:00:00Z")).isTrue(); // 右跨
            assertThat(overlapsWith("2026-08-18T03:00:00Z", "2026-08-18T05:00:00Z")).isTrue(); // 完全相同
        }

        /** 🛡 **首尾相接不算重叠** —— 10:00–12:00 与 12:00–14:00 是合法排期，不会同时生效。 */
        @Test
        void touchingEndpointsDoNotOverlap() {
            assertThat(overlapsWith("2026-08-18T01:00:00Z", "2026-08-18T03:00:00Z")).isFalse();
            assertThat(overlapsWith("2026-08-18T05:00:00Z", "2026-08-18T07:00:00Z")).isFalse();
        }

        @Test
        void disjointWindowsDoNotOverlap() {
            assertThat(overlapsWith("2026-08-17T01:00:00Z", "2026-08-17T03:00:00Z")).isFalse();
        }
    }

    @Nested
    class 提前结束 {

        /** 提前结束**不覆盖**排期结束时刻，生效判定取较早的那个。 */
        @Test
        void effectiveEndTakesTheEarlierOne() {
            Instant terminated = Instant.parse("2026-08-18T04:00:00Z");
            assertThat(ScheduleWindow.effectiveEnd(END, terminated)).isEqualTo(terminated);
            assertThat(ScheduleWindow.effectiveEnd(END, null)).isEqualTo(END);
        }

        /** 提前结束时刻若晚于排期结束（理论上写不进去），仍以排期结束为准。 */
        @Test
        void terminatedAfterEndFallsBackToEnd() {
            assertThat(ScheduleWindow.effectiveEnd(END, END.plusSeconds(60))).isEqualTo(END);
        }

        @Test
        void afterEarlyTerminationTheWindowIsEnded() {
            Instant terminated = Instant.parse("2026-08-18T04:00:00Z");
            Instant now = Instant.parse("2026-08-18T04:30:00Z");
            assertThat(ScheduleWindow.isActiveAt(now, START,
                    ScheduleWindow.effectiveEnd(END, terminated))).isFalse();
        }
    }

    @Nested
    class 时区 {

        /**
         * 运营配的是 WIB 墙上时间，入库转 UTC 绝对时刻。WIB = UTC+7。
         *
         * <p>已接受的代价：一条 WIB 08:00 上线的配置，对最东边（WIT）的用户是当地 10:00。
         * 本版本不做分区上线。
         */
        @Test
        void wibWallClockConvertsToUtcInstant() {
            assertThat(ScheduleWindow.fromWib(LocalDateTime.of(2026, 8, 18, 8, 0)))
                    .isEqualTo(Instant.parse("2026-08-18T01:00:00Z"));
        }

        /** 跨日：WIB 当天 00:30 是前一天的 UTC 17:30。 */
        @Test
        void wibMidnightCrossesTheUtcDateLine() {
            assertThat(ScheduleWindow.fromWib(LocalDateTime.of(2026, 8, 18, 0, 30)))
                    .isEqualTo(Instant.parse("2026-08-17T17:30:00Z"));
        }

        @Test
        void roundTripsBack() {
            LocalDateTime wall = LocalDateTime.of(2026, 8, 18, 8, 0);
            assertThat(ScheduleWindow.toWib(ScheduleWindow.fromWib(wall))).isEqualTo(wall);
        }
    }
}
