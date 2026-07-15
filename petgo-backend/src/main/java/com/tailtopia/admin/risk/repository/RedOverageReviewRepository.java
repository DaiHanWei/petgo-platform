package com.tailtopia.admin.risk.repository;

import com.tailtopia.admin.risk.domain.RedOverageReview;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 红色超额复核仓储（Story 9.6）。user 维度单行。 */
public interface RedOverageReviewRepository extends JpaRepository<RedOverageReview, Long> {

    List<RedOverageReview> findByUserIdIn(List<Long> userIds);
}
