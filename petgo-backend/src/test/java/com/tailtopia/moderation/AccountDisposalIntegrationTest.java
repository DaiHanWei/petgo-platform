package com.tailtopia.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.moderation.domain.AccountDisposalType;
import com.tailtopia.moderation.domain.AccountReportReason;
import com.tailtopia.moderation.domain.AccountReportStatus;
import com.tailtopia.moderation.repository.AccountDisposalRepository;
import com.tailtopia.moderation.repository.AccountReportRepository;
import com.tailtopia.moderation.service.AccountDisposalService;
import com.tailtopia.moderation.service.AccountReportService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：账号级处置（Story 3.2）—— 需 Docker postgres+redis。
 *
 * <p>覆盖 AC2 先写记录再触发后果 / AC3 警告的完整定义 / AC4 不合并不去重不自动升级 /
 * AC5 封号后果与告知 / AC6 封号 ≠ 注销 / AC7 工单流转 / AC8 零自动处置。
 */
class AccountDisposalIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AccountDisposalService disposalService;

    @Autowired
    private AccountDisposalRepository disposals;

    @Autowired
    private AccountReportService accountReports;

    @Autowired
    private AccountReportRepository reports;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private ContentPostRepository posts;

    private long reportedTicketFor(User target) {
        accountReports.submit(newUser().getId(), target.getId(), AccountReportReason.SPAM, null);
        return reports.findByTargetUserId(target.getId()).orElseThrow().getId();
    }

    private List<String> notificationTypesOf(long userId) {
        return notifications.findAll().stream()
                .filter(n -> n.getRecipientUserId() == userId)
                .map(n -> n.getType().name())
                .toList();
    }

    // ===== AC3 / AC2 · 警告 =====

    @Test
    void ac3_warningWritesRecordAndNotifiesWithoutTouchingTheAccount() {
        User target = newUser();
        long ticket = reportedTicketFor(target);

        disposalService.warn(target.getId(), ticket, 1L);

        // 记录落库（AC2 的「先写记录」——事务已提交，两样东西一起在）
        var rows = disposals.findByTargetUserIdOrderByCreatedAtDesc(target.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDisposalType()).isEqualTo(AccountDisposalType.WARNING);
        assertThat(rows.get(0).getReportId()).isEqualTo(ticket);
        // 通知到达
        assertThat(notificationTypesOf(target.getId())).containsExactly("ACCOUNT_WARNED");
        // ⚠️ 警告**不影响使用**：账号状态一动不动。
        assertThat(userRepo.findById(target.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
    }

    /**
     * ⚠️ AC4：警告可重复、**不合并、不去重、不自动升级**。
     *
     * <p>三次警告就是三条记录 + 三条通知。累计到任何次数都不会自动封号 ——
     * 自动升级等于把处置权交给一个谁都没审过的阈值，是否升级由运营看着历史记录人工判断。
     *
     * <p><b>2026-08-20 口径澄清</b>：这三次警告分别来自<b>三次举报</b>，不是对着同一张
     * 已处理工单连点三下。同一张工单处置完就转 RESOLVED，再处置会被
     * {@code requirePending} 挡掉（见下一条用例）—— 那道守卫防的是运营停在过期页面上
     * 重复提交、以及并发重放。该账号<b>又被举报</b>时，工单经 {@code reopenIfHandled()}
     * 翻回待处置，这才是「再警告一次」的正当入口。
     *
     * <p>工单粒度是「被举报账号」（{@code target_user_id} 唯一），所以「新工单」在库里
     * 仍是同一行被重开，不会长出第二行。
     */
    @Test
    void ac4_repeatedWarningsAreNeverMergedAndNeverAutoEscalate() {
        User target = newUser();

        // 每次警告前都有一次新举报把工单翻回待处置（reportedTicketFor 每次换一个举报人）。
        long ticket = reportedTicketFor(target);
        disposalService.warn(target.getId(), ticket, 1L);
        assertThat(reportedTicketFor(target)).isEqualTo(ticket); // 同一张工单被重开，不是新行
        disposalService.warn(target.getId(), ticket, 1L);
        reportedTicketFor(target);
        disposalService.warn(target.getId(), ticket, 1L);

        assertThat(disposals.countByTargetUserId(target.getId())).isEqualTo(3);
        assertThat(notificationTypesOf(target.getId()))
                .containsExactly("ACCOUNT_WARNED", "ACCOUNT_WARNED", "ACCOUNT_WARNED");
        // 三次警告之后账号依然是正常状态——没有任何自动封号。
        assertThat(userRepo.findById(target.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
    }

    /**
     * 🔒 同一张<b>已处理</b>工单不得再处置（过期页面重复提交 / 并发重放守卫）。
     *
     * <p>与上一条是一对：AC4 允许「多次警告」，但入口是<b>新举报把工单重开</b>，
     * 不是对着一张处理完的工单再点一次。这条把界线钉住 —— 少了它，谁都可能
     * 把守卫当成「妨碍 AC4」而放宽回去，于是运营刷新慢一步就白警告第二次。
     */
    @Test
    void samePendingTicketCannotBeDisposedTwice() {
        User target = newUser();
        long ticket = reportedTicketFor(target);

        disposalService.warn(target.getId(), ticket, 1L);

        assertThatThrownBy(() -> disposalService.warn(target.getId(), ticket, 1L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("已被处理");

        // 被挡掉的那次没有留下任何痕迹：一条记录、一条通知。
        assertThat(disposals.countByTargetUserId(target.getId())).isEqualTo(1);
        assertThat(notificationTypesOf(target.getId())).containsExactly("ACCOUNT_WARNED");
    }

    // ===== AC5 · 封号 =====

    @Test
    void ac5_suspendDeactivatesAccountAndNotifies() {
        User target = newUser();
        long ticket = reportedTicketFor(target);

        disposalService.suspend(target.getId(), ticket, 1L);

        assertThat(userRepo.findById(target.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.DEACTIVATED);
        assertThat(notificationTypesOf(target.getId())).containsExactly("ACCOUNT_SUSPENDED");
        assertThat(disposals.findByTargetUserIdOrderByCreatedAtDesc(target.getId()).get(0)
                .getDisposalType()).isEqualTo(AccountDisposalType.SUSPEND);
    }

    /**
     * ⚠️ AC6：**封号 ≠ 注销**（高风险点 R5）。
     *
     * <p>封号只置状态位（可逆）：存量内容<b>不删</b>、<b>不做任何注销才有的匿名化</b>。
     * 代码里这两个概念命名几乎一样（{@code UserStatus.DEACTIVATED} vs {@code User.deletedAt}），
     * 做反了就是把「封号」变成了「注销」——不可逆、且内容全没。
     */
    @Test
    void ac6_suspendIsNotAccountDeletion() {
        User target = newUser();
        ContentPost post = posts.save(
                ContentPost.publish(target.getId(), ContentType.DAILY, null, "存量正文", List.of()));

        disposalService.suspend(target.getId(), reportedTicketFor(target), 1L);

        User after = userRepo.findById(target.getId()).orElseThrow();
        assertThat(after.getDeletedAt()).isNull();          // 没被当成注销
        assertThat(after.getNickname()).isNotBlank();        // 没有匿名化
        // 存量内容照常在，且仍是 PUBLISHED（封号不删内容）。
        ContentPost stillThere = posts.findById(post.getId()).orElseThrow();
        assertThat(stillThere.getDeletedAt()).isNull();
        assertThat(stillThere.getStatus()).isEqualTo(PostStatus.PUBLISHED);
    }

    // ===== AC7 · 工单流转 =====

    @Test
    void ac7_ticketMovesToHandledWithOperatorAndTime() {
        User target = newUser();
        long ticket = reportedTicketFor(target);

        disposalService.warn(target.getId(), ticket, 42L);

        var report = reports.findById(ticket).orElseThrow();
        assertThat(report.getStatus()).isEqualTo(AccountReportStatus.RESOLVED);
        assertThat(report.getHandledBy()).isEqualTo(42L);
        assertThat(report.getHandledAt()).isNotNull();
    }

    /** 无需处置：工单收档，**被举报账号什么都没发生、也没有任何通知**。 */
    @Test
    void ac7_dismissClosesTicketWithoutTouchingTheAccount() {
        User target = newUser();
        long ticket = reportedTicketFor(target);

        disposalService.dismiss(ticket, 42L);

        assertThat(reports.findById(ticket).orElseThrow().getStatus())
                .isEqualTo(AccountReportStatus.DISMISSED);
        assertThat(disposals.countByTargetUserId(target.getId())).isZero();
        assertThat(notificationTypesOf(target.getId())).isEmpty();
        assertThat(userRepo.findById(target.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
    }

    /**
     * 处置完又被举报 → 工单翻回待处理（Story 2.1 AC9），
     * 而 <b>历史处置记录一条都不减</b>（AC7 后半句）。
     */
    @Test
    void ac7_reopenedTicketKeepsDisposalHistory() {
        User target = newUser();
        long ticket = reportedTicketFor(target);
        disposalService.warn(target.getId(), ticket, 1L);
        assertThat(reports.findById(ticket).orElseThrow().getStatus())
                .isEqualTo(AccountReportStatus.RESOLVED);

        accountReports.submit(newUser().getId(), target.getId(), AccountReportReason.HARASSMENT, null);

        assertThat(reports.findById(ticket).orElseThrow().getStatus())
                .isEqualTo(AccountReportStatus.PENDING);   // 翻回待处理
        assertThat(disposals.countByTargetUserId(target.getId())).isEqualTo(1); // 历史不受影响
    }

    // ===== AC8 · 零自动处置 =====

    /** 15 个人举报 → **不会自动警告、不会自动封号、内容也不会被自动挂起**。 */
    @Test
    void ac8_noAutomaticDisposalNoMatterHowManyReports() {
        User target = newUser();
        ContentPost post = posts.save(
                ContentPost.publish(target.getId(), ContentType.DAILY, null, "正文", List.of()));
        for (int i = 0; i < 15; i++) {
            accountReports.submit(newUser().getId(), target.getId(), AccountReportReason.SPAM, null);
        }

        assertThat(disposals.countByTargetUserId(target.getId())).isZero();
        assertThat(notificationTypesOf(target.getId())).isEmpty();
        assertThat(userRepo.findById(target.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
        assertThat(posts.findById(post.getId()).orElseThrow().getStatus())
                .isEqualTo(PostStatus.PUBLISHED);
    }

    /** 运营主动巡查（无关联工单）也能处置：reportId 为 null 不报错。 */
    @Test
    void disposalWithoutTicketIsAllowed() {
        User target = newUser();

        disposalService.warn(target.getId(), null, 1L);

        assertThat(disposals.findByTargetUserIdOrderByCreatedAtDesc(target.getId()).get(0)
                .getReportId()).isNull();
    }
}
