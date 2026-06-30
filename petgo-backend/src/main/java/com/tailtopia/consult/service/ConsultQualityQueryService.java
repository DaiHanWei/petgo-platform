package com.tailtopia.consult.service;

import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.dto.UnratedReason;
import com.tailtopia.consult.dto.VetQualitySummary;
import com.tailtopia.consult.dto.VetUnratedConsult;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 兽医评分质量只读聚合（Story 6.1，AB-6A）。**consult 模块边界内**合法用自身两 repo；admin.rating 经本 service 调用。
 * <b>仅运营可见</b>，不对 App 公开。口径见 {@link VetQualitySummary}：以会话 created_at 为单一时间窗，
 * rated＝CLOSED 且存在评分行、unrated＝CLOSED 无评分行（INTERRUPTED 不计）。
 *
 * <p>V1 兽医数少：按兽医 + 逐会话查评分（N+1）可接受；若量增可改 group by 聚合查询（仍在 consult.service 内）。
 */
@Service
public class ConsultQualityQueryService {

    private final ConsultSessionRepository sessions;
    private final ConsultRatingRepository ratings;

    public ConsultQualityQueryService(ConsultSessionRepository sessions, ConsultRatingRepository ratings) {
        this.sessions = sessions;
        this.ratings = ratings;
    }

    /**
     * 某兽医评分质量摘要（可选时间窗，作用于会话 created_at；null 表示不限）。
     */
    @Transactional(readOnly = true)
    public VetQualitySummary qualitySummary(long vetId, Instant from, Instant to) {
        List<ConsultSession> closed = sessions.findByVetIdAndStatusInOrderByCreatedAtDesc(
                vetId, List.of(SessionStatus.CLOSED));
        int rated = 0;
        int unrated = 0;
        long starSum = 0;
        for (ConsultSession s : closed) {
            Instant at = s.getCreatedAt();
            if (from != null && at.isBefore(from)) {
                continue;
            }
            if (to != null && !at.isBefore(to)) {
                continue;
            }
            var rating = ratings.findBySessionId(s.getId()).orElse(null);
            if (rating != null) {
                rated++;
                starSum += rating.getStars();
            } else {
                unrated++;
            }
        }
        double avg = rated == 0 ? 0.0
                : BigDecimal.valueOf((double) starSum / rated).setScale(1, RoundingMode.HALF_UP).doubleValue();
        return new VetQualitySummary(rated, unrated, avg);
    }

    /**
     * 某兽医未评问诊列表（Story 6.2）：终态会话（CLOSED/INTERRUPTED）中无评分行的，按 terminalAt 倒序。
     * 原因如实映射（不杜撰）：CLOSED→TIMEOUT_UNRATED（30min 超时未评）、INTERRUPTED→INTERRUPTED（封禁中断）。
     * 已评（存在评分行，含 UNRATED 后补评）不入此列。
     */
    @Transactional(readOnly = true)
    public List<VetUnratedConsult> unratedConsults(long vetId) {
        List<ConsultSession> terminal = sessions.findByVetIdAndStatusInOrderByCreatedAtDesc(
                vetId, List.of(SessionStatus.CLOSED, SessionStatus.INTERRUPTED));
        List<VetUnratedConsult> out = new ArrayList<>();
        for (ConsultSession s : terminal) {
            if (ratings.existsBySessionId(s.getId())) {
                continue; // 已评（以是否存在评分行为权威）
            }
            UnratedReason reason = s.getStatus() == SessionStatus.INTERRUPTED
                    ? UnratedReason.INTERRUPTED
                    : UnratedReason.TIMEOUT_UNRATED;
            out.add(new VetUnratedConsult(s.getId(), s.terminalAt(), reason));
        }
        return out;
    }
}
