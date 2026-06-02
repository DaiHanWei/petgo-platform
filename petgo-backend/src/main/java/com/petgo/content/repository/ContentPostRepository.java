package com.petgo.content.repository;

import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentPostRepository extends JpaRepository<ContentPost, Long> {

    /** 成长时间线读：某作者某类型未删内容，createdAt 倒序游标分页（Story 2.4）。 */
    List<ContentPost> findByAuthorIdAndTypeAndDeletedAtIsNullAndCreatedAtLessThanOrderByCreatedAtDesc(
            long authorId, ContentType type, Instant before, Pageable pageable);
}
