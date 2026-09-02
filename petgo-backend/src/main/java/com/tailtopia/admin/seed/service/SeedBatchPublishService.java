package com.tailtopia.admin.seed.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.seed.domain.SeedBatch;
import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.admin.seed.dto.RowValidation;
import com.tailtopia.admin.seed.repository.SeedBatchRepository;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import com.tailtopia.admin.virtual.domain.SeedContentHash;
import com.tailtopia.admin.virtual.repository.SeedContentHashRepository;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 确认发布（V1.1.6 Story 13.4 · AC2/AC5/AC6）。
 *
 * <h2>🔴 本 story 修掉的是"提交即上线"</h2>
 * 此前**没有校验预览、没有确认入库** —— 50 行错 3 行就是 3 条线上真帖，
 * 只能逐条去找、逐条下架。现在：先预览 → 只发通过的行 → 失败行留草稿可改后重提。
 *
 * <h2>发还是排期，由计划时间决定</h2>
 * 有计划时间 ⇒ 转 {@code SCHEDULED}（到点由 13-5 发）；没有 ⇒ 立即发。
 * ⚠️ 立即发走 {@code VALIDATED → PUBLISHED}，**不硬走 SCHEDULED** ——
 * 那样得编一个假的计划时间，排期列表里会出现一堆从未被排期的行（见 13-1 的状态机注释）。
 */
@Service
public class SeedBatchPublishService {

    private static final Logger log = LoggerFactory.getLogger(SeedBatchPublishService.class);

    private final SeedBatchRepository batches;
    private final SeedBatchRowRepository rows;
    private final SeedBatchValidator validator;
    private final SeedBatchService stateMachine;
    private final ContentService contentService;
    private final SeedContentHashRepository hashes;
    private final com.tailtopia.auth.repository.UserRepository users;
    private final com.tailtopia.admin.virtual.service.AdminPublishIdentityService identities;
    private final AdminAuditService audit;
    /** 指纹图片键解析（bug 20260901-467）：URL → 素材内容哈希，三条发布路径同一判据。 */
    private final SeedBatchAssetService assetService;

    public SeedBatchPublishService(SeedBatchRepository batches, SeedBatchRowRepository rows,
            SeedBatchValidator validator, SeedBatchService stateMachine,
            ContentService contentService, SeedContentHashRepository hashes,
            com.tailtopia.auth.repository.UserRepository users,
            com.tailtopia.admin.virtual.service.AdminPublishIdentityService identities,
            AdminAuditService audit, SeedBatchAssetService assetService) {
        this.batches = batches;
        this.rows = rows;
        this.validator = validator;
        this.stateMachine = stateMachine;
        this.contentService = contentService;
        this.hashes = hashes;
        this.users = users;
        this.identities = identities;
        this.audit = audit;
        this.assetService = assetService;
    }

    /** 预览：逐行校验结果（AC1）。 */
    @Transactional(readOnly = true)
    public List<RowValidation> preview(long batchId) {
        SeedBatch batch = requireBatch(batchId);
        return validator.validate(batch, rows.findByBatchIdOrderByRowNoAsc(batchId));
    }

    /** 确认发布的结果。 */
    public record PublishOutcome(int published, int scheduled, int skippedByError,
            int skippedByDuplicate, int failed,
            /** bug 20260901-473：此前已发布/已排期而本次跳过的行数 —— 原先不计数不提示，
             *  运营把预览刷两遍再点确认时，汇总看起来像「通过的那行凭空消失」。 */
            int alreadyDone) {
    }

    /**
     * 确认发布（AC2）。
     *
     * @param includeDuplicates 运营是否明确选择"重复的也发"。
     *                          🔴 <b>默认 false</b>：去重命中在预览里是提示，
     *                          但"提示了却默认照发"等于没提示。
     */
    @Transactional
    public PublishOutcome confirm(long batchId, long adminAccountId, boolean includeDuplicates) {
        SeedBatch batch = requireBatch(batchId);
        List<SeedBatchRow> all = rows.findByBatchIdOrderByRowNoAsc(batchId);
        List<RowValidation> checks = validator.validate(batch, all);

        int published = 0;
        int scheduled = 0;
        int skippedByError = 0;
        int skippedByDuplicate = 0;
        int failed = 0;
        int alreadyDone = 0;

        for (RowValidation check : checks) {
            SeedBatchRow row = check.row();
            // 已经发过 / 已经排期的行不重复处理 —— 运营可能把预览页刷两遍再点确认。
            if (row.getStatus() == SeedBatchRowStatus.PUBLISHED
                    || row.getStatus() == SeedBatchRowStatus.SCHEDULED) {
                // bug 20260901-473：这个桶必须计数并出现在结果提示里 —— 静默跳过会让
                // 「表格里明明有一条 Pass、汇总却说 0 条发布」，运营读成计数坏了。
                alreadyDone++;
                continue;
            }
            if (!check.passes()) {
                // 🛡 失败行**留在草稿态**，不阻塞整批（AC2）。它的错误信息已经在行上。
                skippedByError++;
                continue;
            }
            if (check.duplicate() && !includeDuplicates) {
                skippedByDuplicate++;
                continue;
            }
            try {
                stateMachine.markValidated(row.getId());
                // ⚠️ 计划时间已经过了 ⇒ **立即发**，而不是排一个已经过期的期。
                //    运营昨天排的、今天才点确认，属常见情形；排进去也只是让扫描器
                //    在下一轮把它发出来，中间多一次状态跳转、还多一条"逾期"的观感。
                if (row.getScheduledAt() != null && row.getScheduledAt().isAfter(Instant.now())) {
                    stateMachine.schedule(row.getId(), row.getScheduledAt(), adminAccountId);
                    scheduled++;
                } else {
                    publishRowNow(row, adminAccountId);
                    published++;
                }
            } catch (RuntimeException e) {
                // 🛡 单行失败不拖垮整批：记原因、转 FAILED，其余行照发。
                //    ⚠️ 这里刻意捕获 RuntimeException 而不是 AppException ——
                //    对象存储抖动之类会抛别的类型，而"一行挂了整批回滚"是最糟的结果。
                log.warn("批量发布单行失败 rowId={} : {}", row.getId(), e.toString());
                safelyFail(row.getId(), e.getMessage());
                failed++;
            }
        }
        audit.record(adminAccountId, "SEED_BATCH_CONFIRM", "seed_batch", String.valueOf(batchId),
                "published=" + published + " scheduled=" + scheduled
                        + " skippedByError=" + skippedByError
                        + " skippedByDuplicate=" + skippedByDuplicate + " failed=" + failed
                        + " alreadyDone=" + alreadyDone);
        return new PublishOutcome(published, scheduled, skippedByError, skippedByDuplicate, failed,
                alreadyDone);
    }

    /**
     * 立即发布一行。
     *
     * <p>🔴 <b>内容类型取自该行</b>（{@code DAILY} / {@code KNOWLEDGE}）。
     * 老路径把它硬编码成 {@code DAILY} —— 那是 V1.1.0 AB-1.1-02 的**实现偏差而非需求变更**，
     * 13-4 把它恢复。
     *
     * <p>🛡 <b>V1.1.6 Story 13.5 起，到点发布的扫描器调用的就是这个方法</b>（不是私有的了）。
     * 那条 AC 写着「走与即时发布**完全相同的链路**，含内容自动审核、无豁免」——
     * 而"另写一条到点发布的快路"是最容易被想到的省事做法。
     * 共用同一个方法是让那条 AC 在**结构上**成立，而不是靠两处代码碰巧一致。
     */
    public void publishRowNow(SeedBatchRow row, long adminAccountId) {
        // 🔴 V1.1.6 Story 13.5 · AC5：**发布那一刻**再确认账号还能用。
        //
        // 排期期间账号可能被移出身份池或被停用 —— 那正是 12-1 那条"移出前提示还有 N 条排期"
        // 要防的后果。确认发布时校验过一遍，但**排期是隔了几天才执行的**，
        // 中间的状态变化只有这里能看见。
        //
        // ⚠️ 这里**只查会变的状态**（在不在池内 / 有没有被停用），
        //    **不查 seed.publish_as_real 权限** —— 那是"谁授权了这次发布"的问题，
        //    在运营点确认那一刻就已经查过；扫描器手里压根没有操作人主体，
        //    在这里再查一次只能查出"没有权限"，把所有排期都毙掉。
        com.tailtopia.auth.domain.User author = users.findById(row.getAuthorUserId())
                .orElseThrow(() -> AppException.validation(
                        "发布账号 id=" + row.getAuthorUserId() + " 已不存在")
                        .code("admin.err.seedBatch.authorMissing",
                                String.valueOf(row.getAuthorUserId())));
        if (!identities.isInPool(author)) {
            throw AppException.validation(
                    "发布账号「" + author.getNickname() + "」已不在运营发布身份池内")
                    .code("admin.err.seedBatch.authorNotInPool", author.getNickname());
        }
        if (!author.isEnabled()) {
            throw AppException.validation("发布账号「" + author.getNickname() + "」已停用")
                    .code("admin.err.seedBatch.authorDisabled", author.getNickname());
        }
        ContentPostResponse saved = contentService.publish(row.getAuthorUserId(),
                new ContentPostCreateRequest(row.getContentType(), row.getPetId(), row.getBody(),
                        row.getImageUrls(), null, null, row.getImageSizes()),
                UUID.randomUUID().toString());
        // V1.1.6 Story 14.1：行上的关联物种落成**行级覆写** ——
        // 运营在录入时明确选过的值就是覆写；留空则不写，由读时推导回落到账号定位/档案。
        if (row.getSpecies() != null && !row.getSpecies().isBlank()) {
            contentService.setSpeciesOverride(saved.id(), row.getSpecies());
        }
        // 指纹：🔴 带**作者维度**（同一文案不同账号各自独立），并记下按发布键的后台账号。
        String hash = SeedContentFingerprint.of(row.getContentType(), row.getBody(),
                assetService.fingerprintKeys(row.getImageUrls()));
        if (!hashes.existsByContentHashAndAuthorId(hash, row.getAuthorUserId())) {
            hashes.save(SeedContentHash.of(hash, saved.id(), row.getAuthorUserId(), adminAccountId));
        }
        // 🔴 先回填内容 id 再转终态（13-1 的实体不变式会校验）——
        //    那个 id 是「整批撤回」唯一的抓手。
        stateMachine.markPublished(row.getId(), saved.id());
    }

    /**
     * 记失败。
     *
     * <p>⚠️ 用 {@code REQUIRES_NEW}：外层事务可能已经因为那次异常被标记回滚，
     * 在同一个事务里写"失败原因"会连带丢掉 —— 于是运营看到的是一行没有任何说明的失败。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void safelyFail(long rowId, String reason) {
        try {
            stateMachine.markFailed(rowId, reason == null ? "发布失败" : reason);
        } catch (RuntimeException ignored) {
            // 连失败都记不下就只留日志 —— 绝不让它把整批拖垮。
            log.warn("记录发布失败原因时又失败了 rowId={}", rowId);
        }
    }

    private SeedBatch requireBatch(long batchId) {
        return batches.findById(batchId)
                .orElseThrow(() -> AppException.notFound("批次不存在")
                        .code("admin.err.seedBatch.batchNotFound"));
    }
}
