package com.tailtopia.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.audit.repository.AdminAuditLogRepository;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.moderation.domain.AccountReportReason;
import com.tailtopia.moderation.domain.AccountReportStatus;
import com.tailtopia.moderation.repository.AccountDisposalRepository;
import com.tailtopia.moderation.repository.AccountReportRepository;
import com.tailtopia.moderation.service.AccountDisposalService;
import com.tailtopia.moderation.service.AccountDisposalService.BatchAction;
import com.tailtopia.moderation.service.AccountReportService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：批量处置（Story 3.3）—— 需 Docker postgres+redis。
 *
 * <p>核心是四项边界：**上限 50 / 跨类型不可批 / 逐条独立事务 / 部分失败逐条回报不整批回滚**。
 * 这些边界防的是同一件事：<b>一次手滑不要封掉几百个人，也不要因为第 30 条失败把前 29 条一起回滚</b>
 * （那 29 个人已经收到通知了）。
 */
class AccountDisposalBatchIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AccountDisposalService disposalService;

    @Autowired
    private AccountDisposalRepository disposals;

    @Autowired
    private AccountReportService accountReports;

    @Autowired
    private AccountReportRepository reports;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private AdminAuditLogRepository auditLogs;

    /** 造一条待处理的账号举报工单，返回工单 id。 */
    private long ticketFor(User target) {
        accountReports.submit(newUser().getId(), target.getId(), AccountReportReason.SPAM, null);
        return reports.findByTargetUserId(target.getId()).orElseThrow().getId();
    }

    // ===== AC1 · 单次上限 50 =====

    /**
     * ⚠️ 服务端硬校验，**不能只靠前端**：勾选框在浏览器里随便改。
     * 不设上限的话，全选一个长队列再点封号，一次能封掉几百个账号。
     */
    @Test
    void ac1_moreThanFiftyIsRejectedByTheServer() {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 51; i++) {
            ids.add(i);
        }

        assertThatThrownBy(() -> disposalService.batch(ids, BatchAction.WARN, 1L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("50");
    }

    @Test
    void ac1_exactlyFiftyIsAllowed() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ids.add(ticketFor(newUser()));
        }

        var result = disposalService.batch(ids, BatchAction.DISMISS, 1L);

        assertThat(result.ok()).isEqualTo(50);
        assertThat(result.failed()).isEmpty();
    }

    // ===== AC4 / AC5 · 逐条独立事务、部分失败不整批回滚 =====

    /**
     * 20 条里 2 条是不存在的工单 → **18 条成功保持成功**，2 条逐条回报原因。
     *
     * <p>⚠️ 这条同时验着「逐条独立事务」：若批量开在一个大事务里，第一条失败就会把已成功的全部回滚，
     * 而那些人**已经收到通知了** —— 通知发出去是收不回来的，数据库回滚也追不回来。
     */
    @Test
    void ac5_partialFailureKeepsSuccessfulOnesAndReportsEachFailure() {
        List<Long> ids = new ArrayList<>();
        List<User> targets = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            User t = newUser();
            targets.add(t);
            ids.add(ticketFor(t));
        }
        ids.add(999_000_001L); // 不存在的工单
        ids.add(999_000_002L);

        var result = disposalService.batch(ids, BatchAction.WARN, 1L);

        assertThat(result.ok()).isEqualTo(18);
        assertThat(result.failedCount()).isEqualTo(2);
        // ⚠️ 明细要能说清「哪一条、为什么」——只报数量的话运营无从重试。
        assertThat(result.failed()).allSatisfy(f -> assertThat(f).contains("工单").contains("："));
        assertThat(result.failed().get(0)).contains("999000001");
        // 成功的 18 条真的落库了，没有被回滚。
        for (User t : targets) {
            assertThat(disposals.countByTargetUserId(t.getId())).isEqualTo(1);
        }
    }

    /** 批量封号：每条都走单条那套「先写记录再触发后果」，账号真的被停用。 */
    @Test
    void batchSuspendGoesThroughTheSameSingleTicketPath() {
        List<Long> ids = new ArrayList<>();
        List<User> targets = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            User t = newUser();
            targets.add(t);
            ids.add(ticketFor(t));
        }

        var result = disposalService.batch(ids, BatchAction.SUSPEND, 7L);

        assertThat(result.ok()).isEqualTo(3);
        for (User t : targets) {
            assertThat(userRepo.findById(t.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.DEACTIVATED);
            assertThat(disposals.countByTargetUserId(t.getId())).isEqualTo(1);
        }
    }

    /** 批量「无需处置」：工单收档，**账号一动不动、也不发通知**。 */
    @Test
    void batchDismissClosesTicketsWithoutTouchingAccounts() {
        List<Long> ids = new ArrayList<>();
        List<User> targets = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            User t = newUser();
            targets.add(t);
            ids.add(ticketFor(t));
        }

        disposalService.batch(ids, BatchAction.DISMISS, 1L);

        for (int i = 0; i < ids.size(); i++) {
            assertThat(reports.findById(ids.get(i)).orElseThrow().getStatus())
                    .isEqualTo(AccountReportStatus.DISMISSED);
            assertThat(disposals.countByTargetUserId(targets.get(i).getId())).isZero();
            assertThat(userRepo.findById(targets.get(i).getId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);
        }
    }

    // ===== AC6 · 逐条审计 =====

    /**
     * N 条批量 = **N 条独立审计**，不是一条汇总。
     *
     * <p>汇总审计在部分失败时说不清到底哪几条成了。⚠️ 代价是审计链有 advisory 锁 + 链尾查询，
     * 50 条 = 50 次取锁 —— 这也正是上限定在 50 的原因之一，<b>别放宽</b>。
     */
    @Test
    void ac6_eachItemGetsItsOwnAuditRow() {
        // ⚠️ 审计仓储是**窄接口**（只有 append + 查询，连 delete 都不暴露），没有 count()——
        // 那是刻意的：应用层无从篡改审计链。这里数的是本次新增的那几行。
        long before = auditLogs.findAllByOrderByIdAsc().size();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(ticketFor(newUser()));
        }

        disposalService.batch(ids, BatchAction.WARN, 1L);

        assertThat(auditLogs.findAllByOrderByIdAsc().size() - before).isEqualTo(4);
    }

    /** 空勾选：不报错、什么也不做（前端也禁了按钮，这里是兜底）。 */
    @Test
    void emptySelectionIsANoop() {
        var result = disposalService.batch(List.of(), BatchAction.WARN, 1L);
        assertThat(result.ok()).isZero();
        assertThat(result.failed()).isEmpty();
    }
}
