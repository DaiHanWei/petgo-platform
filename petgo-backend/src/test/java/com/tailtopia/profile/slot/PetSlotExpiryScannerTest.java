package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/** L0：V1.4.0 multi-pet Story 2.3 · AC1 —— 扫描器只做调度：每小时第 5 分 UTC、注入当前时刻、异常不冒泡。 */
class PetSlotExpiryScannerTest {

    private final PetSlotExpiryService service = mock(PetSlotExpiryService.class);
    private final Instant now = Instant.parse("2026-10-13T03:05:00Z");
    private final PetSlotExpiryScanner scanner = new PetSlotExpiryScanner(service, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void scheduledHourlyAtMinuteFiveUtcWithoutTransaction() throws NoSuchMethodException {
        Method m = PetSlotExpiryScanner.class.getMethod("scan");
        Scheduled s = m.getAnnotation(Scheduled.class);
        assertThat(s.cron()).isEqualTo("${petgo.pet-slot.expiry-scan-cron:0 5 * * * *}");
        assertThat(s.zone()).isEqualTo("UTC");
        assertThat(m.getAnnotation(Transactional.class)).isNull(); // 逐用户独立事务在服务里
    }

    @Test
    void delegatesWithCurrentInstant() {
        when(service.scan(now)).thenReturn(new PetSlotExpiryService.ScanResult(1, 1, 1, 2, 1, 0));
        scanner.scan();
        verify(service).scan(now);
    }

    @Test
    void failureIsSwallowed() {
        when(service.scan(any())).thenThrow(new IllegalStateException("db down"));
        assertThatCode(scanner::scan).doesNotThrowAnyException();
    }
}
