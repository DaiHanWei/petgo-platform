package com.tailtopia.avatarmoderation.repository;

import com.tailtopia.avatarmoderation.domain.AvatarReview;
import com.tailtopia.avatarmoderation.domain.AvatarReviewStatus;
import com.tailtopia.avatarmoderation.domain.AvatarSubjectType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 头像审核记录仓储（内容审核 story 5）。 */
public interface AvatarReviewRepository extends JpaRepository<AvatarReview, Long> {

    /** 某对象全部指定状态记录（陈旧作废：新提交时把非终态置 STALE_DISCARDED）。 */
    List<AvatarReview> findBySubjectTypeAndSubjectIdAndStatusIn(
            AvatarSubjectType subjectType, long subjectId, List<AvatarReviewStatus> statuses);

    /** 人工队列列表（story 8 后台复用），按建记录时间升序。 */
    List<AvatarReview> findByStatusOrderByCreatedAtAsc(AvatarReviewStatus status);
}
