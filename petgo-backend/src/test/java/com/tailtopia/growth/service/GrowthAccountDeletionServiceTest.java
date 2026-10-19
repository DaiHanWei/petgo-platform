package com.tailtopia.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** V1.4.0 ① Story 1.2 · AC7（L0）：注销删三张表；账户行先删（等行锁），反向流水先于原流水。 */
class GrowthAccountDeletionServiceTest {

    @Test
    void deletesAllThreeTablesReversalsFirst() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);

        new GrowthAccountDeletionService(jdbc).deleteByUserId(7L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(4)).update(sql.capture(), anyMap());
        List<String> s = sql.getAllValues();
        // 账户行先删：等进行中的入账（持有行锁）提交后再删流水，不留孤儿。
        assertThat(s.get(0)).contains("growth_accounts");
        assertThat(s.get(1)).contains("growth_ledger").contains("reverses_id IS NOT NULL");
        assertThat(s.get(2)).contains("DELETE FROM growth_ledger WHERE user_id = :u");
        assertThat(s.get(3)).contains("growth_daily_counters");
        verify(jdbc, times(4)).update(anyString(), anyMap());
    }
}
