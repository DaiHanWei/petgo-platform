package com.tailtopia.notify.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.ops.service.OpsNotificationDispatcher;
import com.tailtopia.notify.ops.service.OpsNotificationScanner;
import com.tailtopia.notify.ops.service.OpsNotifyParams;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * L0：运营通知定时扫描（V1.3.4 Story 6.2 · AC5 / AC7）。
 */
@ExtendWith(MockitoExtension.class)
class OpsNotificationScannerTest {

    private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");

    @Mock
    OpsNotificationCampaignRepository repo;
    @Mock
    OpsNotificationDispatcher dispatcher;

    private OpsNotificationScanner scanner() {
        return new OpsNotificationScanner(repo, dispatcher, Clock.fixed(NOW, ZoneOffset.UTC),
                OpsNotifyParams.DEFAULTS);
    }

    @Test
    void stallScanRunsBeforeDueScanWithFifteenMinuteThreshold() {
        Instant threshold = NOW.minus(Duration.ofMinutes(15));
        when(repo.findStalledIds(threshold)).thenReturn(List.of(8L));
        when(repo.markStalled(8L, threshold, NOW)).thenReturn(1);
        when(repo.findDueIds(NOW, 20)).thenReturn(List.of(1L, 2L));

        scanner().scan();

        InOrder order = inOrder(repo, dispatcher);
        order.verify(repo).findStalledIds(threshold);
        order.verify(repo).markStalled(8L, threshold, NOW);
        order.verify(repo).findDueIds(NOW, 20);
        order.verify(dispatcher).dispatchAsync(1L);
        order.verify(dispatcher).dispatchAsync(2L);
        // 超时的不重发
        verify(dispatcher, never()).dispatchAsync(8L);
        verify(dispatcher, never()).dispatch(anyLong());
    }

    @Test
    void nothingDueDispatchesNothing() {
        when(repo.findStalledIds(any())).thenReturn(List.of());
        when(repo.findDueIds(NOW, 20)).thenReturn(List.of());
        scanner().scan();
        verify(dispatcher, never()).dispatchAsync(anyLong());
    }

    @Test
    void scheduledWithConfigurableSixtySecondFixedDelay() throws NoSuchMethodException {
        Method scan = OpsNotificationScanner.class.getMethod("scan");
        Scheduled s = scan.getAnnotation(Scheduled.class);
        assertThat(s.fixedDelayString()).isEqualTo("${petgo.ops-notify.scan-ms:60000}");
        assertThat(s.initialDelayString()).isEqualTo("${petgo.ops-notify.scan-initial-delay-ms:60000}");
    }

    @Test
    void d20ParamsAreFixed() {
        OpsNotifyParams p = OpsNotifyParams.DEFAULTS;
        assertThat(p.batchSize()).isEqualTo(500);
        assertThat(p.batchInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.retryBackoffs()).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(4));
        assertThat(p.progressEvery()).isEqualTo(50);
        assertThat(p.stallTimeout()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void noWebLayerForOpsNotification() throws IOException {
        Path root = Path.of("src", "main", "java", "com", "tailtopia");
        try (Stream<Path> s = Files.walk(root)) {
            List<Path> offenders = s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/web/"))
                    .filter(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8).contains("OpsNotification");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
            assertThat(offenders).as("D-2：本主题不新增运营通知的 Controller / 端点").isEmpty();
        }
    }
}
