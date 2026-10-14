package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.im.ImAccountMapper;
import com.tailtopia.shared.im.TencentImClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * L0：同步可判失败的单人推送（V1.3.4 Story 6.2 · AC4.3）。
 */
@ExtendWith(MockitoExtension.class)
class NotificationPusherNowTest {

    @Mock
    TencentImClient im;

    @Test
    void successReturnsTrueAndUsesUserImId() {
        when(im.pushOfflineChecked(anyString(), any(), any(), any(), any(), any())).thenReturn(true);
        assertThat(new NotificationPusher(im).pushToUserNow(7L, "T", "B", "OPS_ANNOUNCEMENT", "tok", "camp"))
                .isTrue();
        verify(im).pushOfflineChecked(ImAccountMapper.userImId(7L), "T", "B", "OPS_ANNOUNCEMENT", "tok", "camp");
    }

    @Test
    void clientFalseReturnsFalse() {
        when(im.pushOfflineChecked(anyString(), any(), any(), any(), any(), any())).thenReturn(false);
        assertThat(new NotificationPusher(im).pushToUserNow(7L, "T", "B", "OPS_ANNOUNCEMENT", "tok", "camp"))
                .isFalse();
    }

    @Test
    void exceptionIsSwallowedAsFalse() {
        when(im.pushOfflineChecked(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));
        assertThat(new NotificationPusher(im).pushToUserNow(7L, "T", "B", "OPS_ANNOUNCEMENT", "tok", "camp"))
                .isFalse();
    }
}
