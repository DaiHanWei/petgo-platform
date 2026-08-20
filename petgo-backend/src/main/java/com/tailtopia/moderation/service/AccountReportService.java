package com.tailtopia.moderation.service;

import com.tailtopia.moderation.domain.AccountReport;
import com.tailtopia.moderation.domain.AccountReportEntry;
import com.tailtopia.moderation.domain.AccountReportReason;
import com.tailtopia.moderation.repository.AccountReportEntryRepository;
import com.tailtopia.moderation.repository.AccountReportRepository;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.service.UserHideRelationService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号举报（Story 2.1，FR-58）。工单 upsert + 明细追加 + <b>举报即隐藏（同一事务）</b>。
 *
 * <h2>⚠️ 和既有内容举报 {@link ReportService} 反着来的三处</h2>
 * <ol>
 *   <li><b>重复举报不吞</b>：{@code ReportService.submit} 用两处裸 {@code return}（exists 前置短路 +
 *       并发撞唯一约束）把重复举报幂等吞掉，<b>什么都不写</b>。照抄会让次数、类型变化、补充说明全部丢失。
 *       本服务每次都追加一行明细。</li>
 *   <li><b>零自动预处置</b>：内容侧有两条自动通道（{@code ILLEGAL} 单次触发 / 举报人数 ≥ 10 → 挂起内容）。
 *       账号侧<b>一条都没有</b>，无论多少人举报都不自动处置（AD-17）。那两条通道本 story 一行未改，
 *       且继续只作用于内容举报。</li>
 *   <li><b>不设频率限制 / 冷却</b>（A-A23，主动决定而非遗漏）：同一举报人对同一账号可无限次举报。
 *       单个举报人对优先级分的贡献已由公式从结构上封顶 2 分，加冷却只会多一处逻辑、
 *       多一处需要向用户解释的静默失败。下面那道 5 秒去重防的是<b>误触</b>，不是限制用户意图。</li>
 * </ol>
 */
@Service
public class AccountReportService {

    /**
     * 秒级去重窗口（AC11）。<b>服务端不依赖前端防连点</b> —— 双击穿透、提交中的网络重试，
     * 都会在这个窗口内被视为同一次举报（不新增明细、接口照常返回成功）。
     *
     * <p>取 5 秒：足够盖住一次误触与一轮重试，又远短于任何真实的「我要再报他一次」。
     * 超出窗口的再次举报<b>照常追加一行</b>，每次的类型与补充说明独立留存（AC3 不受影响）。
     */
    static final Duration DUPLICATE_WINDOW = Duration.ofSeconds(5);

    /** 「其他」补充说明上限（与 DB 的 varchar(200) 一致）。 */
    static final int DETAIL_MAX_LENGTH = 200;

    private final AccountReportRepository reports;
    private final AccountReportEntryRepository entries;
    private final UserHideRelationService hideRelations;
    private final AccountQueryService accountQuery;

    public AccountReportService(AccountReportRepository reports, AccountReportEntryRepository entries,
            UserHideRelationService hideRelations, AccountQueryService accountQuery) {
        this.reports = reports;
        this.entries = entries;
        this.hideRelations = hideRelations;
        this.accountQuery = accountQuery;
    }

    /**
     * 提交一次账号举报。
     *
     * <p><b>整个方法在同一个事务里</b>（AC5）：工单 upsert、明细追加、以及那条
     * {@code source=REPORT} 的隐藏关系，任一失败整体回滚 ——
     * 绝不出现「有工单没隐藏」（用户举报完还天天看见他）或「有隐藏没工单」（内容消失了但运营看不到）。
     *
     * @param reporterId   举报人
     * @param targetUserId 被举报账号
     * @param reason       账号维度五类之一
     * @param detail       仅 {@code OTHER} 需要且必填（≤200 字）；其余四类一律不保存
     * @return 是否真的追加了一行明细（false = 命中 5 秒去重窗口）
     */
    @Transactional
    public boolean submit(long reporterId, long targetUserId, AccountReportReason reason,
            String detail) {
        if (reporterId == targetUserId) {
            throw AppException.validation("不能举报自己");
        }
        // 目标不存在（伪造/陈旧 id）→ 404：不校验的话 INSERT 撞 FK，事务被标记 rollback-only → 500。
        // 注销用户是软删（users 行仍在），照常可举报。
        if (accountQuery.findUserById(targetUserId).isEmpty()) {
            throw AppException.notFound("用户不存在");
        }
        String normalizedDetail = normalizeDetail(reason, detail);

        AccountReport report = upsertTicket(targetUserId);

        // ⚠️ 隐藏关系无论如何都要在（幂等）：命中去重窗口时也走一遍，避免「第一次写隐藏失败被重试、
        // 第二次却因去重跳过」这种缝隙里漏掉隐藏。同事务，失败则连工单一起回滚。
        hideRelations.hideByReport(reporterId, targetUserId);

        if (entries.existsByReportIdAndReporterIdAndCreatedAtAfter(
                report.getId(), reporterId, Instant.now().minus(DUPLICATE_WINDOW))) {
            return false; // 秒级误触：视为同一次举报，不新增明细，接口照常成功
        }
        entries.save(AccountReportEntry.create(report.getId(), reporterId, reason, normalizedDetail));
        return true;
    }

    /**
     * 找到该账号的工单，没有就建一条；<b>已处置的翻回待处置</b>（AC9），绝不新建第二条。
     *
     * <p>并发安全由数据库单语句 {@code ON CONFLICT DO NOTHING} 保证：并发首报时败方的插入
     * 会等胜方提交后落到 DO NOTHING，随后的 find 一定读到那条工单——与串行结果一致，
     * 全程无异常路径。⚠️ 不要改回「saveAndFlush + catch 唯一约束异常」：异常穿出 repo 代理时
     * 共享事务已被标记 rollback-only，catch 内的任何查询都在已 abort 的事务里再抛 → 500。
     */
    private AccountReport upsertTicket(long targetUserId) {
        reports.insertIfAbsent(targetUserId);
        // ⚠️ 取行级写锁再读（评审三轮 #5）：与运营处置路径串行化——处置进行中，本次提交阻塞到
        // 处置事务提交后才读到 RESOLVED，reopenIfHandled 才能正确翻回 PENDING，避免新举报静默丢失。
        AccountReport report = reports.findByTargetUserIdForUpdate(targetUserId)
                .orElseThrow(() -> AppException.conflict("举报提交冲突，请重试"));
        report.reopenIfHandled();
        return reports.save(report);
    }

    /**
     * 「其他」必须有补充说明，其余四类<b>不保存</b>补充说明。
     *
     * <p>后者取「静默丢弃」而不是「报错」：前端只在选了「其他」时才展示输入框，
     * 为一个用户根本看不到的字段回一个 422，除了制造线上噪声没有别的用处。
     */
    private static String normalizeDetail(AccountReportReason reason, String detail) {
        String trimmed = detail == null ? null : detail.trim();
        if (reason != AccountReportReason.OTHER) {
            return null;
        }
        if (trimmed == null || trimmed.isEmpty()) {
            throw AppException.validation("选择「其他」时请填写补充说明");
        }
        if (trimmed.length() > DETAIL_MAX_LENGTH) {
            throw AppException.validation("补充说明不能超过 " + DETAIL_MAX_LENGTH + " 字");
        }
        return trimmed;
    }
}
