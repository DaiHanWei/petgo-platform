package com.tailtopia.admin.risk.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.risk.domain.RedOverageReview;
import com.tailtopia.admin.risk.dto.RedOverageRow;
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

    /** 人工标记（纯注记 + 审计，绝不触发自动处置）。 */
    @Transactional
    public void mark(long userId, String status, String note, long adminId) {
        if (!RedOverageReview.TO_VERIFY.equals(status) && !RedOverageReview.RESOLVED.equals(status)) {
            throw AppException.validation("非法复核状态");
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
