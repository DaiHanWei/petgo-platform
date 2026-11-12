package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.analytics.store.EventTimeCorrector.Corrected;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** L0：明细层时间校正与可疑判定（analytics-pipeline Story 1.6 AC5 / AC6）。纯函数，无 Spring、无库。 */
class EventTimeCorrectorTest {

    /** 2026-11-01 ~ 2027-01-31 全部视为已建分区。 */
    private static final Set<LocalDate> ALL = range(LocalDate.of(2026, 11, 1), LocalDate.of(2027, 1, 31));

    private static Set<LocalDate> range(LocalDate from, LocalDate to) {
        Set<LocalDate> s = new HashSet<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            s.add(d);
        }
        return s;
    }

    private static Instant t(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void normalOffsetAddsTransitDelay() {
        // 发生 → 发送 10 秒，发送 → 收到 2 秒：设备时钟准，校正后 = 发生时间 + 2 秒
        Corrected c = EventTimeCorrector.correct(t("2026-11-20T03:00:00Z"), t("2026-11-20T03:00:10Z"),
                t("2026-11-20T03:00:12Z"), ALL);

        assertThat(c.eventTime()).isEqualTo(t("2026-11-20T03:00:02Z"));
        assertThat(c.suspicious()).isFalse();
        assertThat(c.eventDate()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    void deviceClockFastIsCorrected() {
        // 设备快 1 小时：声明发生 11:00、发送 11:00:05，服务端 10:00:06 收到 → 真实发生 10:00:01
        Corrected c = EventTimeCorrector.correct(t("2026-11-20T11:00:00Z"), t("2026-11-20T11:00:05Z"),
                t("2026-11-20T10:00:06Z"), ALL);

        assertThat(c.eventTime()).isEqualTo(t("2026-11-20T10:00:01Z"));
        assertThat(c.suspicious()).isFalse();
    }

    @Test
    void deviceClockSlowIsCorrected() {
        // 设备慢 2 天（离线后补发场景以外的纯时钟偏差）：校正后仍落在收到前 3 秒
        Corrected c = EventTimeCorrector.correct(t("2026-11-18T10:00:00Z"), t("2026-11-18T10:00:03Z"),
                t("2026-11-20T10:00:03Z"), ALL);

        assertThat(c.eventTime()).isEqualTo(t("2026-11-20T10:00:00Z"));
        assertThat(c.suspicious()).isFalse();
        assertThat(c.eventDate()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    void laterThanReceivedWithinFiveSecondsIsNotSuspicious() {
        Instant received = t("2026-11-20T10:00:00Z");
        Corrected four = EventTimeCorrector.correct(received.plusSeconds(4), received, received, ALL);
        Corrected five = EventTimeCorrector.correct(received.plusSeconds(5), received, received, ALL);

        assertThat(four.suspicious()).isFalse();
        assertThat(five.suspicious()).as("恰好 5 秒不算超过").isFalse();
    }

    @Test
    void laterThanReceivedBySixSecondsIsSuspiciousAndUsesReceivedDate() {
        // 收到：WIB 2026-11-20 23:59:58；校正后晚 6 秒 → WIB 11-21，但可疑 → 日期取收到时间的 WIB 日期
        Instant received = t("2026-11-20T16:59:58Z");
        Corrected c = EventTimeCorrector.correct(received.plusSeconds(6), received, received, ALL);

        assertThat(c.suspicious()).isTrue();
        assertThat(c.eventTime()).isEqualTo(received.plusSeconds(6));
        assertThat(c.eventDate()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    void earlierThanReceivedBySixDaysIsFineButEightDaysIsSuspicious() {
        Instant received = t("2026-11-20T10:00:00Z");
        Corrected six = EventTimeCorrector.correct(received.minus(Duration.ofDays(6)), received, received, ALL);
        Corrected seven = EventTimeCorrector.correct(received.minus(Duration.ofDays(7)), received, received, ALL);
        Corrected eight = EventTimeCorrector.correct(received.minus(Duration.ofDays(8)), received, received, ALL);

        assertThat(six.suspicious()).isFalse();
        assertThat(six.eventDate()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(seven.suspicious()).as("恰好 7 天不算超过").isFalse();
        assertThat(eight.suspicious()).isTrue();
        assertThat(eight.eventDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(eight.eventTime()).isEqualTo(received.minus(Duration.ofDays(8)));
    }

    @Test
    void wibDayBoundaryIsUtcSeventeen() {
        Instant received = t("2026-11-20T17:30:00Z");
        Corrected before = EventTimeCorrector.correct(t("2026-11-20T16:59:59Z"), received, received, ALL);
        Corrected after = EventTimeCorrector.correct(t("2026-11-20T17:00:00Z"), received, received, ALL);

        assertThat(before.eventDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(after.eventDate()).isEqualTo(LocalDate.of(2026, 11, 21));
    }

    @Test
    void crossesMonthAndYearInWib() {
        Instant received = t("2026-12-31T17:00:10Z");
        Corrected c = EventTimeCorrector.correct(t("2026-12-31T17:00:00Z"), received, received, ALL);

        assertThat(c.eventDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(EventTimeCorrector.wibDate(t("2026-11-30T17:00:00Z"))).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void dateOutsideBuiltPartitionsFallsBackToReceivedDate() {
        // 早 5 天、不可疑，但那天的分区不存在（只建了收到当天）
        Instant received = t("2026-11-20T10:00:00Z");
        Corrected c = EventTimeCorrector.correct(received.minus(Duration.ofDays(5)), received, received,
                Set.of(LocalDate.of(2026, 11, 20)));

        assertThat(c.suspicious()).isFalse();
        assertThat(c.eventTime()).isEqualTo(received.minus(Duration.ofDays(5)));
        assertThat(c.eventDate()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    void missingSentAtMeansZeroOffset() {
        Instant received = t("2026-11-20T10:00:00Z");
        Corrected c = EventTimeCorrector.correct(t("2026-11-20T09:59:00Z"), null, received, ALL);

        assertThat(c.eventTime()).isEqualTo(t("2026-11-20T09:59:00Z"));
        assertThat(c.suspicious()).isFalse();
    }

    @Test
    void clientTsIsNotModified() {
        Instant client = t("2026-11-20T09:00:00Z");
        Corrected c = EventTimeCorrector.correct(client, t("2026-11-20T09:00:01Z"), t("2026-11-20T10:00:00Z"), ALL);

        assertThat(c.eventTime()).isEqualTo(t("2026-11-20T09:59:59Z"));
        assertThat(client).isEqualTo(t("2026-11-20T09:00:00Z"));
    }
}
