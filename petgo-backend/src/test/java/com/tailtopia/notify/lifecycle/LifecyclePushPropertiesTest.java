package com.tailtopia.notify.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：生命周期推送配置默认值（V1.5.0 onboarding-recall Story 6.2 · AC1 / AC2 / AC6）。 */
class LifecyclePushPropertiesTest {

    @Test
    @DisplayName("Java 默认：D1/D3/D7 关、召回关、上限 2000、流失 7 天、19:00 WIB")
    void javaDefaults() {
        LifecyclePushProperties p = new LifecyclePushProperties();
        assertThat(p.isEnabled()).isFalse();
        assertThat(p.isWinbackEnabled()).isFalse();
        assertThat(p.getDailyCap()).isEqualTo(2000);
        assertThat(p.getWinbackAfterDays()).isEqualTo(7);
        assertThat(p.getCron()).isEqualTo("0 0 12 * * *");
    }

    @Test
    @DisplayName("application.yml 与 .env.example 同口径：winback-enabled 默认 false、daily-cap 默认 2000")
    void ymlAndEnvExample() throws Exception {
        String yml = new String(getClass().getResourceAsStream("/application.yml").readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(yml).contains("winback-enabled: ${LIFECYCLE_WINBACK_ENABLED:false}")
                .contains("daily-cap: ${LIFECYCLE_PUSH_DAILY_CAP:2000}")
                .contains("enabled: ${LIFECYCLE_PUSH_ENABLED:false}");
        String env = java.nio.file.Files.readString(java.nio.file.Path.of(".env.example"), StandardCharsets.UTF_8);
        assertThat(env).contains("LIFECYCLE_WINBACK_ENABLED=false").contains("LIFECYCLE_PUSH_DAILY_CAP=2000");
    }
}
