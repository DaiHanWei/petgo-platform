package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.profile.slot.domain.PetSlotRental;
import com.tailtopia.profile.slot.domain.PetSlotRentalSource;
import com.tailtopia.profile.slot.domain.PetSlotRentalStatus;
import com.tailtopia.profile.slot.repository.PetSlotRentalRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * L0：V1.4.0 multi-pet Story 2.3 · AC1 ~ AC6 —— {@link PetSlotExpiryService}（注入固定 {@code now}）。
 *
 * <p>名额仓储用<b>内存假实现</b>（Mockito answer 按与 JPQL 同口径的条件读写同一组实体），事务用「失败即把名额状态还原」的假
 * {@link TransactionOperations}——这样「重复执行不重复通知」「中途失败下一轮重做」（架构 §5 幂等可重放）能在 L0 真实重放。
 * 文案用真实 {@code i18n/messages*.properties}。
 */
class PetSlotExpiryServiceTest {

    /** WIB 10:00（2026-10-13）。 */
    private static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");
    private static final Locale ID = Locale.forLanguageTag("id");

    private final PetSlotRentalRepository repo = mock(PetSlotRentalRepository.class);
    private final PetSlotLock lock = mock(PetSlotLock.class);
    private final PetSlotStateService state = mock(PetSlotStateService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final FakeTx tx = new FakeTx();
    private final List<PetSlotRental> rows = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    /** sendWithCopy 被调用时是否还在事务里（应恒为 false：通知在提交之后发）。 */
    private final AtomicBoolean sentInsideTx = new AtomicBoolean();

    private PetSlotExpiryService service;

    /** 假事务：执行回调；抛异常 = 回滚（把所有名额的状态列还原到进入前），并把异常抛给调用方。 */
    private final class FakeTx implements TransactionOperations {
        boolean inTx;
        int commits;
        int rollbacks;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            Map<PetSlotRental, Object[]> snapshot = new HashMap<>();
            for (PetSlotRental r : rows) {
                snapshot.put(r, new Object[] {r.getStatus(), r.getExpiresAt(), r.getRemindedAt(), r.getExpiredAt()});
            }
            inTx = true;
            try {
                T out = action.doInTransaction(null);
                commits++;
                return out;
            } catch (RuntimeException e) {
                rollbacks++;
                snapshot.forEach((r, v) -> {
                    ReflectionTestUtils.setField(r, "status", v[0]);
                    ReflectionTestUtils.setField(r, "expiresAt", v[1]);
                    ReflectionTestUtils.setField(r, "remindedAt", v[2]);
                    ReflectionTestUtils.setField(r, "expiredAt", v[3]);
                });
                throw e;
            } finally {
                inTx = false;
            }
        }
    }

    private static ReloadableResourceBundleMessageSource messages() {
        ReloadableResourceBundleMessageSource s = new ReloadableResourceBundleMessageSource();
        s.setBasename("classpath:i18n/messages");
        s.setDefaultEncoding(StandardCharsets.UTF_8.name());
        s.setFallbackToSystemLocale(false);
        return s;
    }

    private PetSlotRental rental(long userId, Instant expiresAt) {
        PetSlotRental r = PetSlotRental.start("tok" + ids.incrementAndGet(), userId, PetSlotRentalSource.RENT,
                expiresAt.minus(Duration.ofDays(30)), 30, 1L);
        ReflectionTestUtils.setField(r, "id", ids.get());
        rows.add(r);
        return r;
    }

    private static boolean due(PetSlotRental r, Instant now) {
        return r.getStatus() == PetSlotRentalStatus.ACTIVE && !r.getExpiresAt().isAfter(now);
    }

    private static boolean remindable(PetSlotRental r, Instant now, Instant until) {
        return r.getStatus() == PetSlotRentalStatus.ACTIVE && r.getRemindedAt() == null
                && r.getExpiresAt().isAfter(now) && !r.getExpiresAt().isAfter(until);
    }

    @BeforeEach
    void setUp() {
        service = new PetSlotExpiryService(repo, lock, state, notifications, accounts, new PetSlotExpiryCopy(messages()),
                tx);
        when(accounts.localeOf(anyLong())).thenReturn(ID);
        when(state.reconcile(anyLong(), any())).thenReturn(ReconcileResult.none());
        when(repo.findUserIdsWithDueExpiry(any(), any(), any(Pageable.class))).thenAnswer(inv -> {
            Instant now = inv.getArgument(1);
            return rows.stream().filter(r -> due(r, now)).map(PetSlotRental::getUserId).distinct().sorted().toList();
        });
        when(repo.findByUserIdAndStatusAndExpiresAtLessThanEqualOrderByIdAsc(anyLong(), any(), any())).thenAnswer(inv -> {
            long uid = inv.getArgument(0);
            Instant now = inv.getArgument(2);
            return rows.stream().filter(r -> r.getUserId() == uid && due(r, now)).toList();
        });
        when(repo.saveAllAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findUserIdsWithDueReminder(any(), any(), any(), any(Pageable.class))).thenAnswer(inv -> {
            Instant now = inv.getArgument(1);
            Instant until = inv.getArgument(2);
            return rows.stream().filter(r -> remindable(r, now, until)).map(PetSlotRental::getUserId).distinct()
                    .sorted().toList();
        });
        when(repo.findReminderCandidates(anyLong(), any(), any(), any())).thenAnswer(inv -> {
            long uid = inv.getArgument(0);
            Instant now = inv.getArgument(2);
            Instant until = inv.getArgument(3);
            return rows.stream().filter(r -> r.getUserId() == uid && remindable(r, now, until)).toList();
        });
        when(repo.markReminded(anyLong(), any(), any(), any())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            Instant now = inv.getArgument(2);
            Instant until = inv.getArgument(3);
            for (PetSlotRental r : rows) {
                if (Objects.equals(r.getId(), id) && remindable(r, now, until)) {
                    ReflectionTestUtils.setField(r, "remindedAt", now);
                    return 1;
                }
            }
            return 0;
        });
        when(notifications.sendWithCopy(anyLong(), any(), anyString(), any(), anyString(), anyString(), isNull()))
                .thenAnswer(inv -> {
                    if (tx.inTx) {
                        sentInsideTx.set(true);
                    }
                    return null;
                });
    }

    private void demotes(long userId, String... names) {
        List<ReconcileResult.PetRef> refs = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            refs.add(new ReconcileResult.PetRef(1000 + i, names[i]));
        }
        when(state.reconcile(userId, PetSlotTrigger.RENTAL_EXPIRED)).thenReturn(new ReconcileResult(refs, List.of()));
    }

    private void verifySent(long userId, NotificationType type, String title, String body, Object... args) {
        verify(notifications).sendWithCopy(eq(userId), eq(type), eq(type.name()), eq(args), eq(title), eq(body),
                isNull());
    }

    @Nested
    class 到期 {

        @Test
        void 到期名额置EXPIRED并reconcile_转只读则提交后发一条EXPIRED() {
            PetSlotRental r = rental(1L, NOW.minusSeconds(60));
            demotes(1L, "Kopi");

            PetSlotExpiryService.ScanResult res = service.scan(NOW);

            assertThat(r.getStatus()).isEqualTo(PetSlotRentalStatus.EXPIRED);
            assertThat(r.getExpiredAt()).isEqualTo(NOW);
            verify(state).reconcile(1L, PetSlotTrigger.RENTAL_EXPIRED);
            verifySent(1L, NotificationType.PET_SLOT_EXPIRED, "Slot hewan sudah berakhir",
                    "Kopi sekarang hanya-baca. Perpanjang slot untuk mengaktifkannya lagi.", "Kopi");
            assertThat(sentInsideTx).isFalse();
            assertThat(res.expiredUsers()).isEqualTo(1);
            assertThat(res.expiredRentals()).isEqualTo(1);
            assertThat(res.expiredNotices()).isEqualTo(1);
        }

        @Test
        void 恰好到期时刻算到期() {
            PetSlotRental r = rental(1L, NOW);
            service.scan(NOW);
            assertThat(r.getStatus()).isEqualTo(PetSlotRentalStatus.EXPIRED);
        }

        @Test
        void 先取账号锁再重读再reconcile() {
            rental(1L, NOW.minusSeconds(60));
            service.scan(NOW);
            InOrder o = inOrder(lock, repo, state);
            o.verify(lock).lockUser(1L);
            o.verify(repo).findByUserIdAndStatusAndExpiresAtLessThanEqualOrderByIdAsc(1L, PetSlotRentalStatus.ACTIVE,
                    NOW);
            o.verify(repo).saveAllAndFlush(any());
            o.verify(state).reconcile(1L, PetSlotTrigger.RENTAL_EXPIRED);
        }

        @Test
        void 多只转只读_一条通知_宠物名连接() {
            rental(1L, NOW.minusSeconds(60));
            rental(1L, NOW.minusSeconds(30));
            demotes(1L, "Mochi", "Kopi", "Teh");
            service.scan(NOW);
            verifySent(1L, NotificationType.PET_SLOT_EXPIRED, "Slot hewan sudah berakhir",
                    "Mochi, Kopi & Teh sekarang hanya-baca. Perpanjang slot untuk mengaktifkannya lagi.",
                    "Mochi, Kopi & Teh");
            verify(state, times(1)).reconcile(1L, PetSlotTrigger.RENTAL_EXPIRED);
        }

        @Test
        void 英文账号文案为英文() {
            rental(1L, NOW.minusSeconds(60));
            demotes(1L, "Kopi");
            when(accounts.localeOf(1L)).thenReturn(Locale.ENGLISH);
            service.scan(NOW);
            verifySent(1L, NotificationType.PET_SLOT_EXPIRED, "Pet slot has expired",
                    "Kopi is now read-only. Renew the slot to reactivate it.", "Kopi");
        }

        @Test
        void 无宠物因此只读_不发通知() {
            PetSlotRental r = rental(1L, NOW.minusSeconds(60));
            service.scan(NOW);
            assertThat(r.getStatus()).isEqualTo(PetSlotRentalStatus.EXPIRED);
            verify(state).reconcile(1L, PetSlotTrigger.RENTAL_EXPIRED);
            verify(notifications, never()).sendWithCopy(anyLong(), any(), anyString(), any(), anyString(), anyString(),
                    any());
        }

        @Test
        void 加锁期间续租已推后到期日_不置EXPIRED也不reconcile() {
            PetSlotRental r = rental(1L, NOW.minusSeconds(60));
            // 模拟：等锁期间续租到账提交，把 expires_at 推后 30 天
            doAnswer(inv -> {
                ReflectionTestUtils.setField(r, "expiresAt", r.getExpiresAt().plus(Duration.ofDays(30)));
                return null;
            }).when(lock).lockUser(1L);
            PetSlotExpiryService.ScanResult res = service.scan(NOW);
            assertThat(r.getStatus()).isEqualTo(PetSlotRentalStatus.ACTIVE);
            assertThat(r.getExpiredAt()).isNull();
            verify(state, never()).reconcile(anyLong(), any());
            assertThat(res.expiredRentals()).isZero();
        }

        @Test
        void 单用户失败回滚且不影响其他用户() {
            PetSlotRental r1 = rental(1L, NOW.minusSeconds(60));
            PetSlotRental r2 = rental(2L, NOW.minusSeconds(60));
            when(state.reconcile(1L, PetSlotTrigger.RENTAL_EXPIRED)).thenThrow(new IllegalStateException("boom"));
            demotes(2L, "Kopi");

            PetSlotExpiryService.ScanResult res = service.scan(NOW);

            assertThat(r1.getStatus()).isEqualTo(PetSlotRentalStatus.ACTIVE); // 回滚，下一轮重做
            assertThat(r1.getExpiredAt()).isNull();
            assertThat(r2.getStatus()).isEqualTo(PetSlotRentalStatus.EXPIRED);
            verify(notifications, never()).sendWithCopy(eq(1L), any(), anyString(), any(), anyString(), anyString(),
                    any());
            verify(notifications).sendWithCopy(eq(2L), eq(NotificationType.PET_SLOT_EXPIRED), anyString(), any(),
                    anyString(), anyString(), isNull());
            assertThat(res.failedUsers()).isEqualTo(1);
            assertThat(tx.rollbacks).isEqualTo(1);
        }

        @Test
        void 通知发送失败只记日志_状态不回滚() {
            PetSlotRental r = rental(1L, NOW.minusSeconds(60));
            demotes(1L, "Kopi");
            when(notifications.sendWithCopy(eq(1L), any(), anyString(), any(), anyString(), anyString(), any()))
                    .thenThrow(new IllegalStateException("push down"));
            PetSlotExpiryService.ScanResult res = service.scan(NOW);
            assertThat(r.getStatus()).isEqualTo(PetSlotRentalStatus.EXPIRED);
            assertThat(res.failedUsers()).isZero();
            assertThat(res.expiredNotices()).isZero();
            // 再扫：已 EXPIRED 不再处理（at-most-once，不重发）
            service.scan(NOW.plusSeconds(3600));
            verify(state, times(1)).reconcile(1L, PetSlotTrigger.RENTAL_EXPIRED);
            verify(notifications, times(1)).sendWithCopy(eq(1L), any(), anyString(), any(), anyString(), anyString(),
                    any());
        }
    }

    @Nested
    class 提醒 {

        @Test
        void 三天窗口边界_含3天整_不含3天加1秒_已到期不提醒() {
            PetSlotRental hit = rental(1L, NOW.plus(Duration.ofDays(3)));
            PetSlotRental miss = rental(2L, NOW.plus(Duration.ofDays(3)).plusSeconds(1));
            PetSlotRental expired = rental(3L, NOW);

            PetSlotExpiryService.ScanResult res = service.scan(NOW);

            assertThat(hit.getRemindedAt()).isEqualTo(NOW);
            assertThat(miss.getRemindedAt()).isNull();
            assertThat(expired.getRemindedAt()).isNull();
            assertThat(expired.getStatus()).isEqualTo(PetSlotRentalStatus.EXPIRED);
            verifySent(1L, NotificationType.PET_SLOT_EXPIRING, "Slot hewan berakhir 16 Okt",
                    "Perpanjang sebelum 16 Okt agar semua hewanmu tetap aktif.", "16 Okt");
            verify(notifications, never()).sendWithCopy(eq(2L), any(), anyString(), any(), anyString(), anyString(),
                    any());
            verify(notifications, never()).sendWithCopy(eq(3L), eq(NotificationType.PET_SLOT_EXPIRING), anyString(),
                    any(), anyString(), anyString(), any());
            assertThat(res.remindedRentals()).isEqualTo(1);
            assertThat(res.remindNotices()).isEqualTo(1);
            assertThat(sentInsideTx).isFalse();
        }

        @Test
        void 同一WIB到期日合并_不同日分开() {
            rental(1L, NOW.plus(Duration.ofDays(1)));                     // 14 Okt 10:00 WIB
            rental(1L, NOW.plus(Duration.ofDays(1)).plusSeconds(3600));   // 14 Okt 11:00 WIB
            rental(1L, NOW.plus(Duration.ofDays(2)));                     // 15 Okt

            PetSlotExpiryService.RemindOutcome o = service.remindForUser(1L, NOW);

            assertThat(o.remindedRentals()).isEqualTo(3);
            assertThat(o.notices()).isEqualTo(2);
            ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
            verify(notifications, times(2)).sendWithCopy(eq(1L), eq(NotificationType.PET_SLOT_EXPIRING),
                    eq("PET_SLOT_EXPIRING"), args.capture(), anyString(), anyString(), isNull());
            assertThat(args.getAllValues()).extracting(a -> a[0]).containsExactly("14 Okt", "15 Okt");
        }

        /** 2.3 复审：同一 WIB 到期日的另一个名额在之前轮次已提醒 → 本轮只置位不重发。 */
        @Test
        void 同一到期日之前轮次已提醒_本轮不重发() {
            rental(1L, NOW.plus(Duration.ofDays(1)).plusSeconds(3600));   // 14 Okt 11:00 WIB，本轮进窗口
            when(repo.findRemindedExpiries(eq(1L), any(), any()))
                    .thenReturn(List.of(NOW.plus(Duration.ofDays(1))));     // 14 Okt 10:00 WIB，上一轮已提醒

            PetSlotExpiryService.RemindOutcome o = service.remindForUser(1L, NOW);

            assertThat(o.remindedRentals()).isEqualTo(1);
            assertThat(o.notices()).isZero();
            verify(notifications, never()).sendWithCopy(anyLong(), any(), anyString(), any(), anyString(),
                    anyString(), any());
        }

        @Test
        void 合并按WIB自然日_UTC同日但WIB跨日分开() {
            // UTC 10-14 16:30 = WIB 23:30（14 日）；UTC 10-14 17:30 = WIB 00:30（15 日）
            rental(1L, Instant.parse("2026-10-14T16:30:00Z"));
            rental(1L, Instant.parse("2026-10-14T17:30:00Z"));
            assertThat(service.remindForUser(1L, NOW).notices()).isEqualTo(2);
        }

        @Test
        void 英文账号日期为英文() {
            rental(1L, NOW.plus(Duration.ofDays(3)));
            when(accounts.localeOf(1L)).thenReturn(Locale.ENGLISH);
            service.scan(NOW);
            verifySent(1L, NotificationType.PET_SLOT_EXPIRING, "Pet slot ends 16 Oct",
                    "Renew before 16 Oct to keep all your pets active.", "16 Oct");
        }

        @Test
        void 条件更新返回0行_不发() {
            PetSlotRental r = rental(1L, NOW.plus(Duration.ofDays(1)));
            when(repo.markReminded(eq(r.getId()), any(), any(), any())).thenReturn(0); // 并发已处理 / 已续租出窗口
            PetSlotExpiryService.RemindOutcome o = service.remindForUser(1L, NOW);
            assertThat(o.remindedRentals()).isZero();
            verify(notifications, never()).sendWithCopy(anyLong(), any(), anyString(), any(), anyString(), anyString(),
                    any());
        }

        @Test
        void 条件更新带窗口与状态参数() {
            PetSlotRental r = rental(1L, NOW.plus(Duration.ofDays(1)));
            service.remindForUser(1L, NOW);
            verify(repo).markReminded(r.getId(), PetSlotRentalStatus.ACTIVE, NOW, NOW.plus(Duration.ofDays(3)));
        }

        @Test
        void 续租清空reminded_at后新到期日窗口内再提醒一次() {
            PetSlotRental r = rental(1L, NOW.plus(Duration.ofDays(2)));
            service.scan(NOW);
            r.extend(30); // 2.2 续租：+30 天并清空 reminded_at
            service.scan(NOW.plus(Duration.ofDays(1)));        // 新到期日不在窗口 → 不提醒
            service.scan(NOW.plus(Duration.ofDays(29)));       // 新到期日前 3 天内 → 再提醒一次
            service.scan(NOW.plus(Duration.ofDays(29)).plusSeconds(3600));
            verify(notifications, times(2)).sendWithCopy(eq(1L), eq(NotificationType.PET_SLOT_EXPIRING), anyString(),
                    any(), anyString(), anyString(), isNull());
        }
    }

    @Nested
    class 顺序与幂等重放 {

        @Test
        void 先到期后提醒_同次扫描已到期的不再收到即将到期() {
            PetSlotRental expired = rental(1L, NOW.minusSeconds(1));
            rental(1L, NOW.plus(Duration.ofDays(2)));
            demotes(1L, "Kopi");

            service.scan(NOW);

            InOrder o = inOrder(state, notifications);
            o.verify(state).reconcile(1L, PetSlotTrigger.RENTAL_EXPIRED);
            o.verify(notifications).sendWithCopy(eq(1L), eq(NotificationType.PET_SLOT_EXPIRED), anyString(), any(),
                    anyString(), anyString(), isNull());
            o.verify(notifications).sendWithCopy(eq(1L), eq(NotificationType.PET_SLOT_EXPIRING), anyString(), any(),
                    anyString(), anyString(), isNull());
            assertThat(expired.getRemindedAt()).isNull();
        }

        @Test
        void 扫描执行两次_不重复处理不重复通知() {
            rental(1L, NOW.minusSeconds(60));
            rental(2L, NOW.plus(Duration.ofDays(1)));
            demotes(1L, "Kopi");

            service.scan(NOW);
            PetSlotExpiryService.ScanResult second = service.scan(NOW.plusSeconds(3600));

            assertThat(second.hasWork()).isFalse();
            verify(state, times(1)).reconcile(1L, PetSlotTrigger.RENTAL_EXPIRED);
            verify(notifications, times(1)).sendWithCopy(eq(1L), eq(NotificationType.PET_SLOT_EXPIRED), anyString(),
                    any(), anyString(), anyString(), isNull());
            verify(notifications, times(1)).sendWithCopy(eq(2L), eq(NotificationType.PET_SLOT_EXPIRING), anyString(),
                    any(), anyString(), anyString(), isNull());
        }

        @Test
        void 中途失败_下一轮只重做失败用户_各用户恰好一条() {
            rental(1L, NOW.minusSeconds(60));
            rental(2L, NOW.minusSeconds(60));
            demotes(2L, "Teh");
            when(state.reconcile(1L, PetSlotTrigger.RENTAL_EXPIRED))
                    .thenThrow(new IllegalStateException("boom"))
                    .thenReturn(new ReconcileResult(List.of(new ReconcileResult.PetRef(9, "Kopi")), List.of()));

            PetSlotExpiryService.ScanResult first = service.scan(NOW);
            PetSlotExpiryService.ScanResult second = service.scan(NOW.plusSeconds(3600));
            PetSlotExpiryService.ScanResult third = service.scan(NOW.plusSeconds(7200));

            assertThat(first.failedUsers()).isEqualTo(1);
            assertThat(second.expiredUsers()).isEqualTo(1);
            assertThat(third.hasWork()).isFalse();
            assertThat(rows).allMatch(r -> r.getStatus() == PetSlotRentalStatus.EXPIRED);
            verify(state, times(1)).reconcile(2L, PetSlotTrigger.RENTAL_EXPIRED);
            verify(notifications, times(1)).sendWithCopy(eq(1L), eq(NotificationType.PET_SLOT_EXPIRED), anyString(),
                    any(), anyString(), anyString(), isNull());
            verify(notifications, times(1)).sendWithCopy(eq(2L), eq(NotificationType.PET_SLOT_EXPIRED), anyString(),
                    any(), anyString(), anyString(), isNull());
        }

        @Test
        void 每阶段一批最多500个用户() {
            service.scan(NOW);
            ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
            verify(repo).findUserIdsWithDueExpiry(eq(PetSlotRentalStatus.ACTIVE), eq(NOW), page.capture());
            assertThat(page.getValue().getPageSize()).isEqualTo(500);
            assertThat(page.getValue().getPageNumber()).isZero();
            verify(repo).findUserIdsWithDueReminder(eq(PetSlotRentalStatus.ACTIVE), eq(NOW),
                    eq(NOW.plus(Duration.ofDays(3))), any(Pageable.class));
        }

        @Test
        void 每用户一个独立事务() {
            rental(1L, NOW.minusSeconds(60));
            rental(2L, NOW.minusSeconds(60));
            service.scan(NOW);
            assertThat(tx.commits).isEqualTo(2);
        }

        @Test
        void 生产构造为REQUIRES_NEW() {
            PetSlotExpiryService s = new PetSlotExpiryService(repo, lock, state, notifications, accounts,
                    new PetSlotExpiryCopy(messages()),
                    mock(org.springframework.transaction.PlatformTransactionManager.class));
            Object op = ReflectionTestUtils.getField(s, "newTx");
            assertThat(((TransactionDefinition) op).getPropagationBehavior())
                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }
}
