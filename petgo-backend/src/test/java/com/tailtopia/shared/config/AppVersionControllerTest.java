package com.tailtopia.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.config.service.AppVersionConfigService;
import com.tailtopia.shared.error.AppException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** L0（V1.3.4 Story 3.1 · AC2.3）：配置行缺失 → 503，不返回硬编码兜底值。 */
class AppVersionControllerTest {

    @Test
    void missingConfigRowIs503() {
        AppVersionConfigService service = mock(AppVersionConfigService.class);
        when(service.current()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new AppVersionController(service).appVersion())
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
