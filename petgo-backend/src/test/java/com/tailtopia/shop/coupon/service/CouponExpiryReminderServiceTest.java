package com.tailtopia.shop.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.preference.PushPreferenceService;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.shop.coupon.repository.CouponExpiryNotifyLogDao;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

/**
 * L0：v1.4.2 shop Story 2.3 AC5.5 ~ AC5.9 —— {@link CouponExpiryReminderService#remindUser}（Mockito；
 * 事务用直执行替身，记录是否被置 rollback-only；文案用真实 {@code i18n/messages*.properties}）。
 */
class CouponExpiryReminderServiceTest {

    private static final long UID = 42L;
    /** WIB 2026-11-05 10:00。 */
    private static final Instant NOW = Instant.parse("2026-11-05T03:00:00Z");
    private static final Instant UNTIL = NOW.plus(Duration.ofHours(48));
    private static final LocalDate WIB_TODAY = LocalDate.of(2026, 11, 5);
    private static final Locale ID = Locale.forLanguageTag("id");

    private final CouponLedgerService ledger = mock(CouponLedgerService.class);
    private final CouponExpiryNotifyLogDao logDao = mock(CouponExpiryNotifyLogDao.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final PushPreferenceService prefs = mock(PushPreferenceService.class);
    private final AnalyticsClient analytics = mock(AnalyticsClient.class);

    private int txCount;
    private boolean lastRolledBack;
    private CouponExpiryReminderService service;

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource s = new ReloadableResourceBundleMessageSource();
        s.setBasename("classpath:i18n/messages");
        s.setDefaultEncoding(StandardCharsets.UTF_8.name());
        s.setFallbackToSystemLocale(false);
        TransactionOperations newTx = new TransactionOperations() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                txCount++;
                SimpleTransactionStatus status = new SimpleTransactionStatus();
                T r = action.doInTransaction(status);
                lastRolledBack = status.isRollbackOnly();
                return r;
            }
        };
        service = new CouponExpiryReminderService(ledger, logDao, notifications, accounts, prefs, analytics,
                new CouponExpiryCopy(s), newTx);
        when(accounts.isActiveAccount(UID)).thenReturn(true);
        when(accounts.isVirtual(UID)).thenReturn(false);
        when(accounts.localeOf(UID)).thenReturn(ID);
        when(prefs.isEnabled(UID, NotificationType.COUPON_EXPIRING)).thenReturn(true);
        when(logDao.insertIfAbsent(UID, WIB_TODAY)).thenReturn(1);
    }

    private void marks(Instant... expiries) {
        when(ledger.markExpiryNotified(UID, NOW, UNTIL)).thenReturn(List.of(expiries));
    }

    private void verifyNothingSent() {
        verify(notifications, never()).sendWithCopy(anyLong(), any(), anyString(), any(), anyString(), anyString(),
                anyString());
        verify(analytics, never()).capture(anyString(), anyString(), anyMap());
    }

    @Test
    void 今天已提醒过_不置位不发送() {
        when(logDao.insertIfAbsent(UID, WIB_TODAY)).thenReturn(0);
        assertThat(service.remindUser(UID, NOW, UNTIL)).isEqualTo(CouponExpiryReminderService.Outcome.SKIPPED);
        verify(ledger, never()).markExpiryNotified(anyLong(), any(), any());
        verify(logDao, never()).updateCount(anyLong(), any(), anyInt());
        verifyNothingSent();
    }

    @Test
    void 置位0张_回滚且不发送() {
        marks();
        assertThat(service.remindUser(UID, NOW, UNTIL)).isEqualTo(CouponExpiryReminderService.Outcome.SKIPPED);
        assertThat(lastRolledBack).as("日志行随事务一并撤销").isTrue();
        verify(logDao, never()).updateCount(anyLong(), any(), anyInt());
        verifyNothingSent();
    }

    @Test
    void 置位3张_合并一条_张数与最早到期() {
        // 最早到期：2026-11-06T16:59Z = WIB 6 Nov 23:59
        marks(Instant.parse("2026-11-06T20:00:00Z"), Instant.parse("2026-11-06T16:59:00Z"),
                Instant.parse("2026-11-07T02:00:00Z"));
        assertThat(service.remindUser(UID, NOW, UNTIL)).isEqualTo(CouponExpiryReminderService.Outcome.SENT);
        assertThat(txCount).isEqualTo(1);
        assertThat(lastRolledBack).isFalse();
        verify(logDao).updateCount(UID, WIB_TODAY, 3);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(notifications, times(1)).sendWithCopy(eq(UID), eq(NotificationType.COUPON_EXPIRING),
                eq("COUPON_EXPIRING"), args.capture(), eq("3 kupon segera berakhir"),
                eq("Pakai sebelum 6 Nov, 23:59. Jangan sampai hangus!"), eq("3"));
        assertThat(args.getValue()).containsExactly("3", "6 Nov, 23:59");
        verify(analytics).capture(AnalyticsDistinctId.of(UID), "toko_coupon_expiry_push_sent",
                Map.of("coupon_count", 3));
    }

    @Test
    void 关闭PROMO_通知中心照写但不埋点() {
        marks(Instant.parse("2026-11-06T16:59:00Z"));
        when(prefs.isEnabled(UID, NotificationType.COUPON_EXPIRING)).thenReturn(false);
        assertThat(service.remindUser(UID, NOW, UNTIL)).isEqualTo(CouponExpiryReminderService.Outcome.SENT);
        verify(notifications).sendWithCopy(eq(UID), eq(NotificationType.COUPON_EXPIRING), eq("COUPON_EXPIRING"),
                any(), anyString(), anyString(), eq("1"));
        verify(analytics, never()).capture(anyString(), anyString(), anyMap());
    }

    @Test
    void 虚拟号_不埋点() {
        marks(Instant.parse("2026-11-06T16:59:00Z"));
        when(accounts.isVirtual(UID)).thenReturn(true);
        service.remindUser(UID, NOW, UNTIL);
        verify(notifications).sendWithCopy(eq(UID), eq(NotificationType.COUPON_EXPIRING), anyString(), any(),
                anyString(), anyString(), eq("1"));
        verify(analytics, never()).capture(anyString(), anyString(), anyMap());
    }

    @Test
    void 非有效账号_只置位不发送() {
        marks(Instant.parse("2026-11-06T16:59:00Z"));
        when(accounts.isActiveAccount(UID)).thenReturn(false);
        assertThat(service.remindUser(UID, NOW, UNTIL)).isEqualTo(CouponExpiryReminderService.Outcome.MARKED_ONLY);
        verify(ledger).markExpiryNotified(UID, NOW, UNTIL);
        verifyNothingSent();
    }

    @Test
    void 发送异常_返回FAILED_不抛出_不重试() {
        marks(Instant.parse("2026-11-06T16:59:00Z"));
        when(notifications.sendWithCopy(anyLong(), any(), anyString(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("boom"));
        assertThat(service.remindUser(UID, NOW, UNTIL)).isEqualTo(CouponExpiryReminderService.Outcome.FAILED);
        verify(notifications, times(1)).sendWithCopy(anyLong(), any(), anyString(), any(), anyString(), anyString(),
                anyString());
        verify(analytics, never()).capture(anyString(), anyString(), anyMap());
        assertThat(txCount).isEqualTo(1);
    }

    @Test
    void 英文收件人_英文文案() {
        marks(Instant.parse("2026-11-06T16:59:00Z"), Instant.parse("2026-11-06T18:00:00Z"));
        when(accounts.localeOf(UID)).thenReturn(Locale.ENGLISH);
        service.remindUser(UID, NOW, UNTIL);
        verify(notifications).sendWithCopy(eq(UID), eq(NotificationType.COUPON_EXPIRING), eq("COUPON_EXPIRING"),
                any(), eq("2 coupons expiring soon"),
                eq("Use them before 6 Nov, 23:59 so they don't go to waste!"), eq("2"));
    }
}
