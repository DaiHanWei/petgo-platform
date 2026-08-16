package com.tailtopia.moderation.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.auth.service.AuthService;
import com.tailtopia.moderation.domain.AccountDisposal;
import com.tailtopia.moderation.domain.AccountDisposalType;
import com.tailtopia.moderation.domain.AccountReport;
import com.tailtopia.moderation.domain.AccountReportStatus;
import com.tailtopia.moderation.repository.AccountDisposalRepository;
import com.tailtopia.moderation.repository.AccountReportRepository;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号级处置（Story 3.2，FR-58）：**警告** 与 **封号** 两档，外加工单侧的「无需处置」。
 *
 * <h2>顺序与事务（AC2）</h2>
 * <b>先写 {@code account_disposals} 一行，再触发用户侧后果，全程同一事务。</b>
 * 反过来（先停用再记录）一旦中途失败，就会出现<b>「用户登不上了，但工单页显示从没处置过」</b> ——
 * 运营查不到自己做过什么，用户也无从申诉。
 *
 * <h2>⚠️ 警告不合并、不去重、不自动升级（AC4）</h2>
 * 同一账号被警告十次就是<b>十条记录 + 十条通知</b>。累计到任何次数都<b>不会自动封号、也不会自动置顶工单</b> ——
 * 是否升级完全由运营看到历史记录后人工判断。这是刻意的：自动升级等于把处置权交给一个谁都没审过的阈值。
 *
 * <h2>⚠️ 封号 ≠ 注销（高风险点 R5 / 架构 S3）</h2>
 * 封号只置 {@code users.status = DEACTIVATED}（可逆）+ 撤销 refresh 句柄。它<b>不删存量内容、
 * 不做任何注销才有的匿名化</b>；被封号的人在别人的黑名单里<b>照常展示昵称头像、不做特殊标记、仍可被拉黑</b>。
 * 代码里这两个概念命名几乎一样（{@code UserStatus.DEACTIVATED} vs {@code User.deletedAt}），
 * <b>以中文定义为准，别凭字段名推断语义</b>。
 */
@Service
public class AccountDisposalService {

    private final AccountDisposalRepository disposals;
    private final AccountReportRepository reports;
    private final AuthService authService;
    private final NotificationService notificationService;
    private final AdminAuditService auditService;

    public AccountDisposalService(AccountDisposalRepository disposals, AccountReportRepository reports,
            AuthService authService, NotificationService notificationService,
            AdminAuditService auditService) {
        this.disposals = disposals;
        this.reports = reports;
        this.authService = authService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    /**
     * 警告一次。
     *
     * <p>用户会收到一条系统通知，但通知里<b>不说是谁举报的、因哪条内容、也不说这是第几次</b> ——
     * 说了就等于把举报人暴露给被举报人，而「第几次」会变成一个可以试探的计数器。
     * 警告<b>不影响使用</b>（能登录、能发内容、内容可见性不变），也<b>不给异议渠道</b>（与封号不同）。
     *
     * @param reportId 关联工单（可空：运营主动巡查也可以直接警告）
     */
    @Transactional
    public void warn(long targetUserId, Long reportId, long actorAccountId) {
        // ① 先落记录 —— 顺序不能反（见类注释）。
        disposals.save(AccountDisposal.create(targetUserId, AccountDisposalType.WARNING,
                actorAccountId, reportId));
        // ② 再触发后果。
        notificationService.send(targetUserId, NotificationType.ACCOUNT_WARNED,
                "账号警告", "你的账号因违反 TailTopia 社区规范收到一次警告，请遵守社区规范",
                NotificationType.ACCOUNT_WARNED.name(), null);
        resolveTicket(reportId, actorAccountId);
        // ⚠️ summary 里严禁 PII / 内容原文 / 令牌。
        auditService.record(actorAccountId, AuditActions.ACCOUNT_WARNED, "USER",
                String.valueOf(targetUserId), "账号警告（工单 " + (reportId == null ? "-" : reportId) + "）");
    }

    /**
     * 封号（运营停用，可逆）。
     *
     * <p>⚠️ <b>「即时无法登录」有一处既有缺口，本 story 沿用未改</b>：停用只做两件事 ——
     * 置状态位 + 物理删 refresh 行。<b>已经签发出去的 access JWT 在它自然过期前仍然可用</b>
     * （系统没有 token 黑名单）。刷新端有二次门控（{@code isActiveStatus} 不通过就 403），
     * 所以他<b>续不了期</b>，但当前这一张 access token 的剩余寿命内还能继续请求。
     * 建 token 黑名单是独立议题，不在本 story 范围。
     *
     * <p>⚠️ 这里<b>刻意不复用</b> {@code AdminUserService.deactivate}：那条路径还会
     * {@code consultInterrupt.interruptByUser(userId)} <b>强关进行中的问诊会话</b>。
     * 问诊是付费的，强关牵扯退款语义 —— 从一条社区举报工单顺手触发一次涉及金钱的副作用，
     * 风险远大于收益。需要这个行为的话由产品明确后再加，别默认发生。
     */
    @Transactional
    public void suspend(long targetUserId, Long reportId, long actorAccountId) {
        disposals.save(AccountDisposal.create(targetUserId, AccountDisposalType.SUSPEND,
                actorAccountId, reportId));
        authService.deactivateUser(targetUserId); // 置 DEACTIVATED + 撤销 refresh 句柄
        notificationService.send(targetUserId, NotificationType.ACCOUNT_SUSPENDED,
                "账号已停用", "你的账号因违反 TailTopia 社区规范已被停用。如有异议，请联系客服团队。",
                NotificationType.ACCOUNT_SUSPENDED.name(), null);
        resolveTicket(reportId, actorAccountId);
        auditService.record(actorAccountId, AuditActions.ACCOUNT_SUSPENDED, "USER",
                String.valueOf(targetUserId), "账号停用（工单 " + (reportId == null ? "-" : reportId) + "）");
    }

    /**
     * 无需处置：举报不成立，工单收档，<b>不对被举报账号做任何事、也不发任何通知</b>。
     *
     * <p>⚠️ 界面上这一档叫「无需处置」（C-103），但<b>数据层的值仍是 {@code DISMISSED}</b> ——
     * 对举报而言「驳回」是准确的（驳的是举报人的主张），改的只有展示层文案。
     */
    @Transactional
    public void dismiss(long reportId, long actorAccountId) {
        AccountReport report = reports.findById(reportId)
                .orElseThrow(() -> AppException.notFound("工单不存在"));
        report.handleBy(actorAccountId, AccountReportStatus.DISMISSED);
        reports.save(report);
        auditService.record(actorAccountId, AuditActions.ACCOUNT_REPORT_DISMISSED, "ACCOUNT_REPORT",
                String.valueOf(reportId), "账号举报工单无需处置");
    }

    /**
     * 工单流转到「已处理」并记下处理人与时间（AC7）。
     *
     * <p>⚠️ 这不影响历史处置记录：该账号<b>之后又被举报</b>时，同一条工单会翻回待处理
     * （Story 2.1 AC9），而 {@code account_disposals} 里已经写下的行<b>照常累计、一条都不减</b>。
     */
    private void resolveTicket(Long reportId, long actorAccountId) {
        if (reportId == null) {
            return; // 运营主动巡查的处置，没有关联工单
        }
        reports.findById(reportId).ifPresent(r -> {
            r.handleBy(actorAccountId, AccountReportStatus.RESOLVED);
            reports.save(r);
        });
    }

    /** 某账号历史被处置次数（含<b>每一次警告</b>——只数封号会漏掉「已经警告过三次」这种关键背景）。 */
    @Transactional(readOnly = true)
    public long disposalCountOf(long targetUserId) {
        return disposals.countByTargetUserId(targetUserId);
    }

    /** 供测试与展示：最近一次处置时刻（无处置返回 null）。 */
    @Transactional(readOnly = true)
    public Instant lastDisposalAt(long targetUserId) {
        return disposals.findByTargetUserIdOrderByCreatedAtDesc(targetUserId).stream()
                .findFirst().map(AccountDisposal::getCreatedAt).orElse(null);
    }
}
