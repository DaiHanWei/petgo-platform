package com.tailtopia.admin.seed.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.seed.domain.SeedBatch;
import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.admin.seed.dto.BatchSummary;
import com.tailtopia.admin.seed.repository.SeedBatchRepository;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ImageSize;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批次与行的状态机（V1.1.6 Story 13.1 · AB-3K/3L）。
 *
 * <p><b>本 story 只交付"存下来但还没发"这套地基</b>：批次容器 + 行级状态机 + 聚合视图。
 * 录入界面（13-3）、校验预览（13-4）、到点发布（13-5）都在它之上，各自不再建存储。
 *
 * <p>🔴 <b>为什么必须先做这一条</b>：批次校验草稿态与定时排期态是**同一数据模型的不同状态**
 * （A-11）。先做成"只支持立即发布"、之后再补排期，会在**同一处返工两次** ——
 * 第一次把"确认发布"写成只能立即发，第二次再把它拆开支持排期。
 */
@Service
public class SeedBatchService {

    private final SeedBatchRepository batches;
    private final SeedBatchRowRepository rows;
    private final AdminAuditService audit;

    public SeedBatchService(SeedBatchRepository batches, SeedBatchRowRepository rows,
            AdminAuditService audit) {
        this.batches = batches;
        this.rows = rows;
        this.audit = audit;
    }

    // ——————————————————— 建 ———————————————————

    /** 开一个批次（还没有行）。 */
    @Transactional
    public SeedBatch openBatch(SeedBatch.Source source, long adminAccountId) {
        SeedBatch b = batches.save(SeedBatch.open(source, adminAccountId));
        audit.record(adminAccountId, "SEED_BATCH_OPEN", "seed_batch", String.valueOf(b.getId()),
                "source=" + source);
        return b;
    }

    /**
     * 往批次里加一行草稿。
     *
     * <p>{@code rowNo} 由调用方给 —— 它是**运营那份表格里的行号**，不是这里的自增序号。
     */
    @Transactional
    public SeedBatchRow addDraft(long batchId, int rowNo, long authorUserId, ContentType type,
            Long petId, String body, List<String> imageUrls, List<ImageSize> imageSizes) {
        return addDraft(batchId, rowNo, authorUserId, type, petId, body, imageUrls, imageSizes,
                null);
    }

    /** 带关联物种的版本（V1.1.6 Story 14.1）。 */
    @Transactional
    public SeedBatchRow addDraft(long batchId, int rowNo, long authorUserId, ContentType type,
            Long petId, String body, List<String> imageUrls, List<ImageSize> imageSizes,
            String species) {
        // 加了行就算「保存过」，批次自此进入列表（bug 20260826）。
        // 🛡 放在这里而不是各调用方：粘贴 / 手动加行 / Excel 导入三条路都汇到本方法，
        //    逐个去标记迟早漏一条，而漏掉的表现是「明明导入了却在列表里找不到」。
        batches.findById(batchId).ifPresent(b -> {
            b.markSaved();
            batches.save(b);
        });
        return rows.save(SeedBatchRow.draft(batchId, rowNo, authorUserId, type, petId, body,
                imageUrls, imageSizes, species));
    }

    // ——————————————————— 流转 ———————————————————

    /**
     * 校验通过。{@code DRAFT → VALIDATED}，并清掉上一次的校验错误。
     */
    @Transactional
    public void markValidated(long rowId) {
        SeedBatchRow r = require(rowId);
        r.setErrorMessage(null);
        r.transitionTo(SeedBatchRowStatus.VALIDATED);
        rows.save(r);
    }

    /**
     * 校验未通过：留在 {@code DRAFT} 并记下错误。
     *
     * <p>🛡 <b>不是一个状态流转</b> —— 校验失败的行本来就还是草稿。
     * 为它单开一个"校验失败"状态只会多一个和 DRAFT 行为完全一样的态。
     */
    @Transactional
    public void markValidationFailed(long rowId, String error) {
        SeedBatchRow r = require(rowId);
        if (r.getStatus() != SeedBatchRowStatus.DRAFT) {
            // 已经过了校验阶段的行不该再被写校验错误 —— 那说明调用方拿的是过期状态。
            throw AppException.validation("只有草稿行可以记录校验错误")
                    .code("admin.err.seedBatch.validationErrorOnlyDraft");
        }
        r.setErrorMessage(error);
        rows.save(r);
    }

    /**
     * 排期。**先设时间再流转** —— 时间为空时流转会被拒（否则"到点"永远不会到）。
     *
     * <p>⚠️ <b>这里不校验"时间必须在未来"</b>（V1.1.6 Story 13.5 · AC1）。
     * 那条校验放在**运营输入的入口**上（批次设置 / 行编辑 / 改排期时间）——
     * 因为 13-4 的"确认发布"可能在计划时间之后才被点（运营昨天排的、今天才确认），
     * 那种情形的正确行为是**立即发**，而不是报错拦住他。
     * 在这个漏斗上硬拦会把那条常见路径变成一次失败。
     */
    @Transactional
    public void schedule(long rowId, Instant at, long adminAccountId) {
        if (at == null) {
            throw AppException.validation("请指定计划发布时间")
                    .code("admin.err.seedBatch.scheduleTimeRequired");
        }
        SeedBatchRow r = require(rowId);
        r.setScheduledAt(at);
        transition(r, SeedBatchRowStatus.SCHEDULED);
        rows.save(r);
        audit.record(adminAccountId, "SEED_ROW_SCHEDULE", "seed_batch_row", String.valueOf(rowId),
                "at=" + at);
    }

    /**
     * 取消排期 → 回退草稿（AC1）。
     *
     * <p>⚠️ 回退时**清掉计划时间**（在 {@code transitionTo} 里做）：
     * 取消之后还留着一个计划时间，界面上会显示"未排期，计划 3 月 5 日发布"这种自相矛盾的东西。
     */
    @Transactional
    public void cancelSchedule(long rowId, long adminAccountId) {
        SeedBatchRow r = require(rowId);
        transition(r, SeedBatchRowStatus.DRAFT);
        rows.save(r);
        audit.record(adminAccountId, "SEED_ROW_CANCEL_SCHEDULE", "seed_batch_row",
                String.valueOf(rowId), "");
    }

    /**
     * 发布成功：回填内容 id 并转终态。
     *
     * <p>🔴 <b>内容 id 必须先回填</b>（{@code transitionTo} 会校验）——
     * 它是「整批撤回」（本版本不做，OQ-22 后移）唯一的抓手；
     * 漏了它，那条内容就再也和这一行对不上了。
     */
    @Transactional
    public void markPublished(long rowId, long contentPostId) {
        SeedBatchRow r = require(rowId);
        r.setContentPostId(contentPostId);
        transition(r, SeedBatchRowStatus.PUBLISHED);
        rows.save(r);
    }

    /** 发布失败：记原因并转 {@code FAILED}（不自动重试、不自动转草稿）。 */
    @Transactional
    public void markFailed(long rowId, String reason) {
        SeedBatchRow r = require(rowId);
        r.setErrorMessage(reason);
        transition(r, SeedBatchRowStatus.FAILED);
        rows.save(r);
    }

    /** 修错重提：{@code FAILED → DRAFT}（清掉失败原因）。 */
    @Transactional
    public void reopenForFix(long rowId, long adminAccountId) {
        SeedBatchRow r = require(rowId);
        transition(r, SeedBatchRowStatus.DRAFT);
        rows.save(r);
        audit.record(adminAccountId, "SEED_ROW_REOPEN", "seed_batch_row", String.valueOf(rowId), "");
    }

    // ——————————————————— 读 ———————————————————

    @Transactional(readOnly = true)
    public List<SeedBatchRow> rowsOf(long batchId) {
        return rows.findByBatchIdOrderByRowNoAsc(batchId);
    }

    /**
     * 批次列表 —— <b>按各行状态聚合</b>（AC2）。
     *
     * <p>⚠️ 一次把这些批次的行全查出来再在内存里分组，而不是每个批次一条 count 查询：
     * 后者是 N+1，而批次列表是运营最常打开的一页。
     */
    @Transactional(readOnly = true)
    public List<BatchSummary> recentBatches() {
        // 🔴 只列**已保存过**的批次（bug 20260826）——「新建批次」点开没填就走的空批次不占位。
        List<SeedBatch> recent = batches.findTop50BySavedAtIsNotNullOrderByIdDesc();
        if (recent.isEmpty()) {
            return List.of();
        }
        List<Long> ids = recent.stream().map(SeedBatch::getId).toList();
        var byBatch = rows.findByBatchIdInOrderByRowNoAsc(ids).stream()
                .collect(java.util.stream.Collectors.groupingBy(SeedBatchRow::getBatchId));
        List<BatchSummary> out = new ArrayList<>(recent.size());
        for (SeedBatch b : recent) {
            out.add(BatchSummary.of(b, byBatch.getOrDefault(b.getId(), List.of())));
        }
        return out;
    }

    // ——————————————————— 内部 ———————————————————

    private SeedBatchRow require(long rowId) {
        return rows.findById(rowId)
                .orElseThrow(() -> AppException.notFound("批量内容行不存在")
                        .code("admin.err.seedBatch.rowNotFound"));
    }

    /**
     * 把领域层的 {@link IllegalStateException} 翻成 {@link AppException}。
     *
     * <p>🛡 非法流转对**运营**来说是一次可解释的失败（"这条已经发出去了"），
     * 不该变成 500 白屏；但对**调用方代码**来说它是 bug，所以领域层仍然抛异常而不是返回 false。
     */
    private void transition(SeedBatchRow r, SeedBatchRowStatus target) {
        try {
            r.transitionTo(target);
        } catch (IllegalStateException e) {
            throw AppException.validation(e.getMessage());
        }
    }
}
