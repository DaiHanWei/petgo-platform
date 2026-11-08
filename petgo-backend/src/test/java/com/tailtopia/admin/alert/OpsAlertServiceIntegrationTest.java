package com.tailtopia.admin.alert;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tailtopia.admin.dailyreport.LarkWebhookClient;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1（V1.4.2 shop-admin Story 1.3 AC2 / AC3）：真库上的去重（{@code ops_alert_log}）与「提交后才发、回滚不发」。
 * ⚠️ 需真实 PostgreSQL + Redis，云端只编译。
 */
class OpsAlertServiceIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txm;

    private OpsAlertService serviceWith(LarkWebhookClient lark) {
        OpsAlertProperties props = new OpsAlertProperties();
        props.getOps().setWebhookUrl("https://ops.example/hook");
        return new OpsAlertService(props, lark, jdbc, txm);
    }

    @Test
    void sameKeyIsSentOnceAcrossCalls() {
        LarkWebhookClient lark = mock(LarkWebhookClient.class);
        OpsAlertService service = serviceWith(lark);
        String key = "claim:it-" + UUID.randomUUID();
        service.send(OpsChannel.OPS, key, "待索赔");
        service.send(OpsChannel.OPS, key, "待索赔");
        verify(lark, times(1)).sendTo(anyString(), anyString(), anyMap());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ops_alert_log WHERE dedupe_key = ?", Long.class, key)).isEqualTo(1L);
    }

    @Test
    void rolledBackTransactionSendsNothingAndCommittedOneSends() {
        LarkWebhookClient lark = mock(LarkWebhookClient.class);
        OpsAlertService service = serviceWith(lark);
        TransactionTemplate tx = new TransactionTemplate(txm);
        tx.executeWithoutResult(st -> {
            service.send(OpsChannel.OPS, null, "回滚");
            st.setRollbackOnly();
        });
        verify(lark, never()).sendTo(anyString(), anyString(), anyMap());
        tx.executeWithoutResult(st -> service.send(OpsChannel.OPS, null, "提交"));
        verify(lark, times(1)).sendTo(anyString(), anyString(), anyMap());
    }
}
