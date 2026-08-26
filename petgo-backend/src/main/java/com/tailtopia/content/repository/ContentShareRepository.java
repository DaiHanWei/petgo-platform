package com.tailtopia.content.repository;

import com.tailtopia.content.domain.ContentShare;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 单条内容分享（Story 9.3）。对外只按 token 查，绝不按顺序 id 对外寻址。 */
public interface ContentShareRepository extends JpaRepository<ContentShare, Long> {

    Optional<ContentShare> findByShareToken(String shareToken);

    Optional<ContentShare> findByContentPostId(long contentPostId);

    /** 账号注销 / 内容硬清理时一并删（分享链接不该比内容活得久）。 */
    void deleteByContentPostIdIn(java.util.Collection<Long> contentPostIds);
}
