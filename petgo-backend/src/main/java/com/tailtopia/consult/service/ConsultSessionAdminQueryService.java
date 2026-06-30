package com.tailtopia.consult.service;

import com.tailtopia.consult.domain.ConsultRating;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.dto.ConsultSessionMetaRow;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台问诊会话元数据只读查询（Story 5.2，AB-4B）。**consult 模块边界内**合法使用自身 repo；
 * admin 经本 service 调用（不跨模块直访 repo）。**只读、无写、无审计**；返回元数据 + 评分，
 * 绝不映射 IM 正文 / AI 分诊 / 用户媒体列（NFR5）。
 */
@Service
public class ConsultSessionAdminQueryService {

    private final ConsultSessionRepository sessions;
    private final ConsultRatingRepository ratings;

    public ConsultSessionAdminQueryService(ConsultSessionRepository sessions,
            ConsultRatingRepository ratings) {
        this.sessions = sessions;
        this.ratings = ratings;
    }

    /**
     * 多维查询会话元数据（任意条件可空）。日期范围作用于 {@code created_at}（UTC）；createdAt 倒序。
     *
     * @param userId 用户 id（null=不限）
     * @param vetId  兽医 id（null=不限）
     * @param from   创建时间下界（含，null 忽略）
     * @param to     创建时间上界（不含，null 忽略）
     */
    @Transactional(readOnly = true)
    public List<ConsultSessionMetaRow> search(Long userId, Long vetId, Instant from, Instant to) {
        Specification<ConsultSession> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (userId != null) {
                ps.add(cb.equal(root.get("userId"), userId));
            }
            if (vetId != null) {
                ps.add(cb.equal(root.get("vetId"), vetId));
            }
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                ps.add(cb.lessThan(root.get("createdAt"), to));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return sessions.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(this::toRow)
                .toList();
    }

    private ConsultSessionMetaRow toRow(ConsultSession s) {
        ConsultRating rating = ratings.findBySessionId(s.getId()).orElse(null);
        return new ConsultSessionMetaRow(
                s.getId(), s.getUserId(), s.getVetId(), s.getCreatedAt(), s.terminalAt(),
                s.getStatus().name(),
                rating == null ? null : rating.getStars(),
                rating == null ? null : rating.getComment());
    }
}
