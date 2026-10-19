package com.tailtopia.admin.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.growth.service.AdminGrowthBatchService.Preview;
import com.tailtopia.admin.growth.service.AdminGrowthBatchService.Result;
import com.tailtopia.growth.service.AdminAdjustCommand;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.GrowthLedgerService.Eligibility;
import com.tailtopia.shared.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/** V1.4.0 ① Story 4.3 · AC3 ~ AC7（L0）：批量发放的归类 / 批次号 / 逐人执行 / 审计。 */
class AdminGrowthBatchServiceTest {

    /** 2026-10-18 05:07 UTC = 12:07 WIB。 */
    private static final Instant AT = Instant.parse("2026-10-18T05:07:00Z");
    private static final String BATCH = "GB-202610181207-ABC123";

    private final GrowthLedgerService ledger = mock(GrowthLedgerService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final AdminAccountRepository adminAccounts = mock(AdminAccountRepository.class);
    private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    private final AdminGrowthBatchService service = new AdminGrowthBatchService(ledger, audit, adminAccounts, tm,
            Clock.fixed(AT, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(ledger.campaignEnabled()).thenReturn(true);
        when(ledger.batchMatches(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        Map<Long, Eligibility> m = new LinkedHashMap<>();
        m.put(1L, Eligibility.ELIGIBLE);
        m.put(2L, Eligibility.ELIGIBLE);
        m.put(3L, Eligibility.ELIGIBLE);
        m.put(4L, Eligibility.NOT_FOUND);
        m.put(5L, Eligibility.DELETED);
        m.put(6L, Eligibility.DEACTIVATED);
        m.put(7L, Eligibility.VIRTUAL);
        m.put(8L, Eligibility.NOT_PARTICIPATING);
        when(ledger.classify(any())).thenReturn(m);
        when(adminAccounts.findById(anyLong())).thenReturn(Optional.empty());
    }

    private static String code(Throwable t) {
        return ((AppException) t).getMessageCode();
    }

    @Test
    void previewClassifiesAndTotals() {
        Preview p = service.preview(" 周年庆 ", "50", "1 2 3 4 5 6 7 8 abc 1", null);

        assertThat(p.validIds()).containsExactly(1L, 2L, 3L);
        assertThat(p.invalid()).isEqualTo(4); // 不存在 / 注销 / 停用 / 格式不正确
        assertThat(p.excluded()).isEqualTo(2); // 虚拟号 + 官方号（拍板：与虚拟号一样排除）
        assertThat(p.total()).isEqualTo(150);
        assertThat(p.campaign()).isEqualTo("周年庆");
        assertThat(p.problems()).extracting(AdminGrowthBatchService.Problem::reason)
                .containsExactly("NOT_FOUND", "DELETED", "DEACTIVATED", "VIRTUAL", "NOT_PARTICIPATING", "MALFORMED");
        assertThat(p.moreProblems()).isZero();
        verify(ledger, times(1)).classify(any());
    }

    @Test
    void batchNoFormatFollowsAd17() {
        Preview p = service.preview("x", "1", "1", null);
        assertThat(p.batchNo()).matches("^GB-\\d{12}-[A-Z0-9]{6}$").startsWith("GB-202610181207-");
        assertThat(p.batchNo().length()).isLessThanOrEqualTo(GrowthLedgerService.BATCH_NO_MAX_LENGTH);
        assertThat(service.newBatchNo()).isNotEqualTo(service.newBatchNo());
    }

    @Test
    void problemListIsCappedAt200() {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 230; i++) {
            ids.append("bad").append(i).append(' ');
        }
        Preview p = service.preview("x", "1", ids.toString(), null);
        assertThat(p.problems()).hasSize(200);
        assertThat(p.moreProblems()).isEqualTo(30);
        assertThat(p.invalid()).isEqualTo(230);
    }

    @Test
    void fillValidations() {
        assertThatThrownBy(() -> service.preview(" ", "1", "1", null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.batch.campaignRequired"));
        assertThatThrownBy(() -> service.preview("x".repeat(201), "1", "1", null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.batch.campaignTooLong"));
        for (String bad : new String[] {"0", "-1", "1.5", "abc", null}) {
            assertThatThrownBy(() -> service.preview("x", bad, "1", null))
                    .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.batch.expInvalid"));
        }
        assertThatThrownBy(() -> service.preview("x", "1", "", null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.batch.idsRequired"));
        when(ledger.campaignEnabled()).thenReturn(false);
        assertThatThrownBy(() -> service.preview("x", "1", "1", null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.campaignDisabled"));
    }

    @Test
    void confirmReclassifiesAndRejectsZeroValid() {
        Preview p = service.prepareConfirm(BATCH, "x", "5", "1,2,7");
        assertThat(p.valid()).isEqualTo(2);
        assertThat(p.batchNo()).isEqualTo(BATCH);

        assertThatThrownBy(() -> service.prepareConfirm(BATCH, "x", "5", "4,7"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.batch.noValid"));
        assertThatThrownBy(() -> service.prepareConfirm("GB-1-x", "x", "5", "1"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.batch.batchNoInvalid"));
    }

    @Test
    void executeGrantsEachValidUserOnceWithBatchKeyInItsOwnTransaction() {
        when(ledger.adjust(any())).thenReturn(new AdminAdjustCommand.Result(0, 50, 1, 1, false));

        Result r = service.execute(BATCH, "周年庆", "50", "1,2,3,4,7", 99L);

        ArgumentCaptor<AdminAdjustCommand> c = ArgumentCaptor.forClass(AdminAdjustCommand.class);
        verify(ledger, times(3)).adjust(c.capture());
        assertThat(c.getAllValues()).extracting(AdminAdjustCommand::idemKey)
                .containsExactly("BATCH:" + BATCH + ":1", "BATCH:" + BATCH + ":2", "BATCH:" + BATCH + ":3");
        assertThat(c.getAllValues()).allSatisfy(cmd -> {
            assertThat(cmd.type()).isEqualTo(AdminAdjustCommand.AdjustType.OPS_CAMPAIGN);
            assertThat(cmd.delta()).isEqualTo(50);
            assertThat(cmd.batchNo()).isEqualTo(BATCH);
            assertThat(cmd.operatorAdminId()).isEqualTo(99L);
            assertThat(cmd.note()).isEqualTo("周年庆");
        });
        // 逐人 REQUIRES_NEW（3 人）+ 审计 1 个独立事务
        verify(tm, times(4)).getTransaction(argThat(d -> d.getPropagationBehavior()
                == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        assertThat(r.granted()).isEqualTo(3);
        assertThat(r.invalid()).isEqualTo(1);
        assertThat(r.excluded()).isEqualTo(1);
        assertThat(r.totalExp()).isEqualTo(150);
        assertThat(r.operator()).isEqualTo("#99");
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(99L), eq(AuditActions.GROWTH_BATCH_GRANTED), eq("growth_batch"), eq(BATCH),
                summary.capture());
        assertThat(summary.getValue())
                .isEqualTo("活动=周年庆；每人=50；入账=3；合计=150；无效=1；虚拟号/官方号=1；已发过=0；失败=0");
    }

    @Test
    void oneFailureDoesNotBlockTheRest() {
        when(ledger.adjust(any())).thenAnswer(i -> {
            AdminAdjustCommand cmd = i.getArgument(0);
            if (cmd.userId() == 2L) {
                throw new DataAccessResourceFailureException("boom");
            }
            return new AdminAdjustCommand.Result(0, 5, 1, 1, false);
        });

        Result r = service.execute(BATCH, "x", "5", "1,2,3", 99L);

        assertThat(r.granted()).isEqualTo(2);
        assertThat(r.failed()).isEqualTo(1);
        verify(ledger, times(3)).adjust(any());
        verify(audit).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void userTurnedIneligibleDuringExecutionCountsAsInvalid() {
        when(ledger.adjust(any())).thenAnswer(i -> {
            AdminAdjustCommand cmd = i.getArgument(0);
            if (cmd.userId() == 3L) {
                throw AppException.validation("x").code("admin.err.growth.userInactive");
            }
            return new AdminAdjustCommand.Result(0, 5, 1, 1, false);
        });

        Result r = service.execute(BATCH, "x", "5", "1,3", 99L);

        assertThat(r.granted()).isEqualTo(1);
        assertThat(r.invalid()).isEqualTo(1);
        assertThat(r.failed()).isZero();
    }

    @Test
    void pureReplayCountsAlreadyGrantedAndWritesReplayAudit() {
        when(ledger.adjust(any())).thenReturn(new AdminAdjustCommand.Result(50, 50, 1, 1, true));

        Result r = service.execute(BATCH, "x", "50", "1,2", 99L);

        assertThat(r.granted()).isZero();
        assertThat(r.alreadyGranted()).isEqualTo(2);
        assertThat(r.replayOnly()).isTrue();
        // 重复提交也留痕，但用单独动作码（「批量发放」条数仍 = 真实发放次数）
        verify(audit).record(eq(99L), eq(AuditActions.GROWTH_BATCH_REPLAYED), eq("growth_batch"), eq(BATCH),
                org.mockito.ArgumentMatchers.contains("已发过=2"));
        verify(audit, never()).record(anyLong(), eq(AuditActions.GROWTH_BATCH_GRANTED), anyString(), anyString(),
                anyString());
    }

    @Test
    void executeAlsoRejectsDisabledCampaignRule() {
        when(ledger.campaignEnabled()).thenReturn(false);
        assertThatThrownBy(() -> service.execute(BATCH, "x", "5", "1", 99L))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.campaignDisabled"));
        verify(ledger, never()).adjust(any());
    }

    @Test
    void unexpectedClassifyFailurePropagates() {
        when(ledger.classify(any())).thenThrow(new DataAccessResourceFailureException("db down"));
        assertThatThrownBy(() -> service.execute(BATCH, "x", "5", "1", 99L))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void malformedOnlyInputHasNoClassifyCall() {
        Preview p = service.preview("x", "1", "abc", null);
        assertThat(p.valid()).isZero();
        assertThat(p.invalid()).isEqualTo(1);
        verify(ledger, never()).classify(any());
        assertThat(List.of(p.normalizedIds())).containsExactly("abc");
    }

    @Test
    void reusedBatchNoWithDifferentCampaignOrAmountIsRejected() {
        when(ledger.batchMatches(BATCH, "x", 99)).thenReturn(false);
        assertThatThrownBy(() -> service.execute(BATCH, "x", "99", "1", 99L))
                .satisfies(t -> assertThat(code(t)).isEqualTo("admin.err.growth.batch.batchMismatch"));
        verify(ledger, never()).adjust(any());
    }

    @Test
    void auditFailureStillReturnsTheResult() {
        when(ledger.adjust(any())).thenReturn(new AdminAdjustCommand.Result(0, 5, 1, 1, false));
        when(audit.record(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("audit down"));

        Result r = service.execute(BATCH, "x", "5", "1,2", 99L);

        assertThat(r.granted()).isEqualTo(2);
    }

    @Test
    void campaignDisabledMidRunCountsAsFailedNotInvalid() {
        when(ledger.adjust(any())).thenThrow(AppException.validation("x").code("admin.err.growth.campaignDisabled"));

        Result r = service.execute(BATCH, "x", "5", "1,2", 99L);

        assertThat(r.failed()).isEqualTo(2);
        assertThat(r.invalid()).isZero();
    }
}
