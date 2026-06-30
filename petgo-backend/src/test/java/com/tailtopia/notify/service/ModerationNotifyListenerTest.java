package com.tailtopia.notify.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tailtopia.moderation.event.ReportResolvedEvent;
import com.tailtopia.notify.domain.NotificationType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** L0：举报处理 → 举报人统一模糊通知（Story 4.1 AC4）。 */
class ModerationNotifyListenerTest {

    @Test
    void notifiesReporterWithGenericMessage() {
        NotificationService notify = mock(NotificationService.class);
        new ModerationNotifyListener(notify)
                .onReportResolved(new ReportResolvedEvent(1L, 88L, Instant.now()));

        verify(notify).send(eq(88L), eq(NotificationType.REPORT_REVIEWED), any(), any(), any(), any());
    }
}
