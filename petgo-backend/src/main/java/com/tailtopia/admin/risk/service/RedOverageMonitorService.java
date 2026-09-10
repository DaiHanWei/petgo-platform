package com.tailtopia.admin.risk.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.risk.domain.RedOverageReview;
import com.tailtopia.admin.risk.dto.RedOverageRow;
import com.tailtopia.admin.risk.dto.RedOverageSummary;
import com.tailtopia.admin.risk.dto.RedTaskRow;
import com.tailtopia.admin.risk.repository.RedOverageReviewRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.triage.repository.RedCountProjection;
import com.tailtopia.triage.repository.TriageTaskRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 红色超额只读监控（Story 9.6，AB-7A）。**纯观测 + 人工标记**——按用户聚合 RED 分诊计数（降序），
 * join 复核态；标记 待核查/已处理 接审计。<b>绝不自动拦截/限流/封禁</b>（阈值 OPEN OQ-11，仅埋点）。
 */
@Service
public class RedOverageMonitorService {

    private final TriageTaskRepository triage;
    private final RedOverageReviewRepository reviews;
    private final AdminAuditService audit;

    public RedOverageMonitorService(TriageTaskRepository triage, RedOverageReviewRepository reviews,
            AdminAuditService audit) {
        this.triage = triage;
        this.reviews = reviews;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<RedOverageRow> list() {
        List<RedCountProjection> counts = triage.redCountsByUser();
        List<Long> userIds = counts.stream().map(RedCountProjection::getUserId).toList();
        Map<Long, RedOverageReview> byUser = userIds.isEmpty() ? Map.of()
                : reviews.findByUserIdIn(userIds).stream()
                        .collect(Collectors.toMap(RedOverageReview::getUserId, r -> r));
        return counts.stream().map(c -> {
            RedOverageReview r = byUser.get(c.getUserId());
            return new RedOverageRow(c.getUserId(), c.getRedCount(),
                    r == null ? "" : r.getStatus(), r == null ? null : r.getNote());
        }).toList();
    }

    /**
     * 摘要条（Story 8.5 · AC4）：待核查用户数。入参就是列表那一份 rows，不另查一遍。
     */
    public RedOverageSummary summary(List<RedOverageRow> rows) {
        return new RedOverageSummary(rows.stream()
                .filter(r -> RedOverageReview.TO_VERIFY.equals(r.reviewStatus())).count());
    }

    /**
     * 某用户的 RED 分诊历史（Story 8.5 · AC4 抽屉）。
     *
     * <p>🔴 **只取任务 id / 状态 / 时间**：症状文本、AI 解析结果、图片都是**健康数据**，
     * 不进后台展示（架构 §Enforcement）。本页要回答的是「红了几次、什么时候红的」，
     * 回答它不需要看症状，而摆出来就多一个泄漏面。
     */
    @Transactional(readOnly = true)
    public List<RedTaskRow> history(long userId) {
        return triage.findRedByUser(userId).stream()
                .map(t -> new RedTaskRow(t.getId(), t.getStatus().name(), t.getCreatedAt()))
                .toList();
    }

    /** 人工标记（纯注记 + 审计，绝不触发自动处置）。 */
    @Transactional
    public void mark(long userId, String status, String note, long adminId) {
        if (!RedOverageReview.TO_VERIFY.equals(status) && !RedOverageReview.RESOLVED.equals(status)) {
            throw AppException.validation("非法复核状态").code("admin.err.redOverage.statusInvalid");
        }
        RedOverageReview r = reviews.findById(userId)
                .map(existing -> {
                    existing.apply(status, note, adminId);
                    return existing;
                })
                .orElseGet(() -> RedOverageReview.of(userId, status, note, adminId));
        reviews.save(r);
        audit.record(adminId, "RED_OVERAGE_REVIEW", "user", String.valueOf(userId), "status=" + status);
    }
}
