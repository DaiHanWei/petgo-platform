package com.tailtopia.admin.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** L0：WIB 格式化（Story 2.3a AC6，D-22）。 */
class AdminTimeTest {

    private final AdminTime t = new AdminTime();

    @Test
    void formatsInWib() {
        Instant utc = Instant.parse("2026-09-09T17:30:00Z"); // WIB = UTC+7 → 次日 00:30
        assertThat(t.fmt(utc)).isEqualTo("2026-09-10 00:30");
        assertThat(t.fmtDate(utc)).isEqualTo("2026-09-10");
        assertThat(t.zone().getId()).isEqualTo("Asia/Jakarta");
    }

    @Test
    void nullSafe() {
        assertThat(t.fmt(null)).isEmpty();
        assertThat(t.fmtDate(null)).isEmpty();
        assertThat(t.today()).isNotNull();
    }
}
