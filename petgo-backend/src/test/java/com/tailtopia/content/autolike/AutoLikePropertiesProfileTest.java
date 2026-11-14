package com.tailtopia.content.autolike;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * L0 · V1.5.0 likes Story 2.4 AC3（FR-221、A-AC40）：base {@code application.yml} 开关默认 true（stag / 生产部署即生效），
 * {@code application-dev.yml} 覆盖为 false（本机 / dev 默认关）。直接读两份 yml，不起 Spring 上下文。
 */
class AutoLikePropertiesProfileTest {

    private static Properties yaml(String name) {
        YamlPropertiesFactoryBean f = new YamlPropertiesFactoryBean();
        f.setResources(new ClassPathResource(name));
        return f.getObject();
    }

    @Test
    void baseDefaultsOnDevOverridesOff() {
        assertThat(yaml("application.yml").getProperty("petgo.auto-like.enabled")).isEqualTo("${AUTO_LIKE_ENABLED:true}");
        assertThat(yaml("application-dev.yml").getProperty("petgo.auto-like.enabled"))
                .isEqualTo("${AUTO_LIKE_ENABLED:false}");
    }

    @Test
    void baseHasAllKeysWithArchitectureDefaults() {
        Properties p = yaml("application.yml");
        assertThat(p.getProperty("petgo.auto-like.morning-cron")).isEqualTo("${AUTO_LIKE_MORNING_CRON:0 30 10 * * *}");
        assertThat(p.getProperty("petgo.auto-like.evening-cron")).isEqualTo("${AUTO_LIKE_EVENING_CRON:0 30 16 * * *}");
        assertThat(p.getProperty("petgo.auto-like.likes-per-run")).isEqualTo("1");
        assertThat(p.getProperty("petgo.auto-like.holdout-salt")).isEqualTo("tt-auto-like-holdout-v1");
        assertThat(p.getProperty("petgo.auto-like.like-target")).isEqualTo("${AUTO_LIKE_TARGET:5}");
    }

    @Test
    void prodProfileDoesNotTurnItOff() {
        String prod = yaml("application-prod.yml").getProperty("petgo.auto-like.enabled");
        assertThat(prod).isNull();
    }
}
