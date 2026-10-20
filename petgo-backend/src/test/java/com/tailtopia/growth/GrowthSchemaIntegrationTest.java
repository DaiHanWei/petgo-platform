package com.tailtopia.growth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.4.0 ① Story 1.1 · AC1（L1，需 Docker postgres）：迁移 V20261018_1500 的 seed；
 * 上下文能起即证明 {@code ddl-auto=validate} 下实体与表一致。
 */
class GrowthSchemaIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void seedsRulesLevelsAndPassiveGroup() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_rules", Integer.class)).isEqualTo(19);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_levels", Integer.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT daily_cap FROM growth_rules WHERE code = 'PASSIVE_GROUP'",
                Integer.class)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT threshold FROM growth_levels WHERE level = 15", Long.class))
                .isEqualTo(12_400L);
        assertThat(jdbc.queryForObject("SELECT tier FROM growth_levels WHERE level = 10", String.class))
                .isEqualTo("GUARDIAN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_rules WHERE enabled = false", Integer.class))
                .isZero();
    }
}
