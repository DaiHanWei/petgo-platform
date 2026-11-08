package com.tailtopia.admin.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.dailyreport.LarkWebhookClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** L0（V1.4.2 shop-admin Story 1.3 · AD-5）：三群分流、未配置只 WARN、去重、提交后发、失败不抛、文案打码。 */
class OpsAlertServiceTest {

    private final LarkWebhookClient lark = mock(LarkWebhookClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
    private OpsAlertProperties props;
    private OpsAlertService service;

    @BeforeEach
    void setUp() {
        props = new OpsAlertProperties();
        props.getCs().setWebhookUrl("https://cs.example/hook");
        props.getCs().setSecret("cs-secret");
        props.getOps().setWebhookUrl("https://ops.example/hook");
        props.getTech().setWebhookUrl("");
        service = new OpsAlertService(props, lark, jdbc, txm);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void eachChannelGoesToItsOwnWebhook() {
        service.send(OpsChannel.CS, null, "客服提醒");
        service.send(OpsChannel.OPS, null, "运营提醒");
        verify(lark).sendTo(eq("https://cs.example/hook"), eq("cs-secret"), anyMap());
        verify(lark).sendTo(eq("https://ops.example/hook"), eq(""), anyMap());
    }

    @Test
    void unconfiguredChannelOnlyWarnsAndNeverCallsLarkOrDedupe() {
        service.send(OpsChannel.TECH, "k", "技术提醒");
        verify(lark, never()).sendTo(anyString(), anyString(), anyMap());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void sameDedupeKeyIsSentOnlyOnce() {
        when(jdbc.update(startsWith("INSERT INTO ops_alert_log"), eq("late-paid:T1"), eq("CS"))).thenReturn(1, 0);
        service.send(OpsChannel.CS, "late-paid:T1", "第一次");
        service.send(OpsChannel.CS, "late-paid:T1", "第二次");
        verify(lark, org.mockito.Mockito.times(1)).sendTo(anyString(), anyString(), anyMap());
    }

    @Test
    void sendFailureNeverThrowsAndReleasesTheDedupeRow() {
        when(jdbc.update(startsWith("INSERT INTO ops_alert_log"), eq("claim:T2"), eq("OPS"))).thenReturn(1);
        doThrow(new LarkWebhookClient.LarkWebhookException("boom")).when(lark).sendTo(anyString(), anyString(), anyMap());
        assertThatCode(() -> service.send(OpsChannel.OPS, "claim:T2", "待索赔")).doesNotThrowAnyException();
        verify(jdbc).update(startsWith("DELETE FROM ops_alert_log"), eq("claim:T2"));
    }

    @Test
    void insideATransactionItWaitsForCommitAndRollbackSendsNothing() {
        TransactionSynchronizationManager.initSynchronization();
        service.send(OpsChannel.OPS, null, "提交后才发");
        verify(lark, never()).sendTo(anyString(), anyString(), anyMap());
        var syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);
        // 回滚：只走 afterCompletion(ROLLED_BACK)，afterCommit 不触发 → 不发
        syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(lark, never()).sendTo(anyString(), anyString(), anyMap());
        // 提交
        syncs.forEach(TransactionSynchronization::afterCommit);
        verify(lark).sendTo(eq("https://ops.example/hook"), anyString(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void textNeverCarriesPhoneOrAccountNumbers() {
        service.send(OpsChannel.CS, null, "订单 TOKO-20260819-000673 用户 +62 812-3456-7890 账号 1234567890123 金额 Rp 1,250,000");
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(lark).sendTo(anyString(), anyString(), payload.capture());
        String text = String.valueOf(((Map<String, Object>) payload.getValue().get("content")).get("text"));
        assertThat(payload.getValue()).containsEntry("msg_type", "text");
        assertThat(text).contains("TOKO-20260819-000673").contains("Rp 1,250,000");
        assertThat(text).doesNotContain("812-3456-7890").doesNotContain("1234567890123");
        assertThat(text).contains("****7890").contains("****0123");
    }

    @Test
    void redactKeepsOrderNumbersAndShortNumbers() {
        assertThat(OpsAlertService.redact("intentId=600，5 分钟内重下运单 3 次")).isEqualTo("intentId=600，5 分钟内重下运单 3 次");
        assertThat(OpsAlertService.redact("Biteship 订单 5dd599ebdefcd4158eb8470b")).contains("5dd599ebdefcd4158eb8470b");
        assertThat(OpsAlertService.redact("0812 3456 7890")).isEqualTo("****7890");
        assertThat(OpsAlertService.redact("081234567890")).isEqualTo("****7890");
        assertThat(OpsAlertService.redact("于 2026-11-06 10:00 失败，原因 HTTP 500 1234 5678"))
                .isEqualTo("于 2026-11-06 10:00 失败，原因 HTTP 500 1234 5678");
    }

    /** AC5：电商线告警按调用点分流（源码扫描钉住分流表，归类见 story Completion Notes）。 */
    @Test
    void shopAlertsAreRoutedPerCallSite() throws Exception {
        // V1.4.2 shop-admin 6.2 · AC9：确认收货清除异常的提醒改走 OpsAlertService.send（OPS + 去重键），不再经 ShopPaymentAlerts
        assertRouted("shop/order/service/ShopOrderFulfillmentService.java", 0, 0);
        assertThat(Files.readString(Path.of("src/main/java/com/tailtopia/shop/order/service/ShopOrderFulfillmentService.java"),
                StandardCharsets.UTF_8)).contains("opsAlerts.send(com.tailtopia.admin.alert.OpsChannel.OPS, \"receipt-cleared:\"");
        assertRouted("shop/order/refund/ShopOrderRefundService.java", 1, 0);
        assertRouted("shop/logistics/BiteshipShipmentService.java", 2, 1);
        assertRouted("shop/logistics/BiteshipTrackingService.java", 4, 1);
        assertRouted("shop/logistics/LogisticsStallJob.java", 1, 0);
        assertRouted("shop/logistics/ShopLogisticsExceptionService.java", 1, 0);
        assertRouted("shop/order/service/ShopOrderPaymentService.java", 0, 2);
        assertRouted("shop/order/service/ShopOrderPaidHandler.java", 0, 3);
    }

    private static void assertRouted(String file, int ops, int tech) throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/tailtopia", file), StandardCharsets.UTF_8);
        assertThat(count(src, "alerts.alert(com.tailtopia.admin.alert.OpsChannel.OPS")).as(file + " OPS").isEqualTo(ops);
        assertThat(count(src, "alerts.alert(com.tailtopia.admin.alert.OpsChannel.TECH")).as(file + " TECH").isEqualTo(tech);
        assertThat(count(src, "alerts.alert(")).as(file + " 无未分流调用").isEqualTo(ops + tech);
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
